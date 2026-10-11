// Carga contra los SLO de ADR-0016 (AKINE G-10).
//
//   docker run --rm --network g10-net -v "$PWD/perf:/perf" grafana/k6:2.3.0 \
//     run /perf/k6/carga.js
//
// Necesita /perf/salida/sembrado.json (lo escribe `sembrado.js`). Escribe el resumen en
// /perf/salida/carga-resumen.json y una tabla por endpoint en /perf/salida/carga-resumen.md.
//
// Tres escenarios a la vez:
//
//   operacion  usuarios de mostrador (ramping-vus, hasta 500 por defecto) que alternan agenda del
//              dia, busqueda de slots, recepcion, ficha 360, cobro y reporte, con tiempo de
//              lectura entre pedidos. Es el "500 usuarios concurrentes" de ADR-0016.
//   reservas   reservas a tasa fija sobre un conjunto CHICO de horarios que se desplaza con el
//              tiempo: muchos pedidos compiten por el mismo slot y casi todos pierden. El 409
//              (`slot-completo`, `recurso-ocupado`) es la respuesta correcta y NO cuenta como error.
//   login      ingresos a 20 por minuto con cuentas propias: el limite es 30/min/IP y el setup ya
//              consumio algunos. Nunca un login por iteracion de los otros escenarios: esos
//              reutilizan los tokens del setup.
//
// El access token dura 10 minutos y no se refresca: la corrida entera (setup + escenarios) tiene
// que caber en ese tiempo. Los defaults suman 6m30s.

import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';

import {
  API,
  JSON_HEADERS,
  PASSWORD,
  alAzar,
  conToken,
  entre,
  exigir,
  fechaLocal,
  ingresar,
  nuevaClave,
  sumarDias,
} from './lib.js';

const centro = JSON.parse(open('/perf/salida/sembrado.json'));

const VUS = parseInt(__ENV.AKINE_CARGA_VUS || '500', 10);
const RESERVAS_POR_SEGUNDO = parseInt(__ENV.AKINE_CARGA_RESERVAS_RPS || '10', 10);
const PENSAR_MIN = parseFloat(__ENV.AKINE_CARGA_PENSAR_MIN || '1');
const PENSAR_MAX = parseFloat(__ENV.AKINE_CARGA_PENSAR_MAX || '5');
/** Cuantos horarios compiten a la vez, y cada cuanto se pasa al grupo siguiente. */
const SLOTS_CALIENTES = parseInt(__ENV.AKINE_CARGA_SLOTS_CALIENTES || '40', 10);
const SEGUNDOS_POR_GRUPO = parseInt(__ENV.AKINE_CARGA_SEGUNDOS_POR_GRUPO || '30', 10);
const SOLO_LECTURA = __ENV.AKINE_CARGA_SOLO_LECTURA === '1';

/** Los endpoints que se miden, con el nombre que lleva su tag `endpoint`. */
const ENDPOINTS = [
  'login',
  'agenda_dia',
  'buscar_slots',
  'recepcion_dia',
  'paciente_360',
  'cobro',
  'reporte',
  'reserva',
];

/** Lo que el SLO de ADR-0016 pide a la API interactiva: p95 <= 300 ms, p99 <= 800 ms. */
const SLO = ['p(95)<300', 'p(99)<800'];

const thresholds = {
  // Tasa de error tecnico < 1 % (ADR-0016). `http_req_failed` ya excluye el 409 de la reserva:
  // ver `ESPERADOS_EN_RESERVA`.
  http_req_failed: ['rate<0.01'],
  errores_tecnicos: ['rate<0.01'],
  'http_req_duration{tipo:interactivo}': SLO,
};
for (const e of ENDPOINTS) {
  thresholds[`http_req_duration{endpoint:${e}}`] = SLO;
  // Sin umbral no hay submetrica en el resumen: este solo existe para poder contar por endpoint.
  thresholds[`http_reqs{endpoint:${e}}`] = ['count>=0'];
}

export const options = {
  setupTimeout: '3m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  thresholds,
  scenarios: escenarios(),
};

/**
 * Los tres escenarios. Con `AKINE_CARGA_SOLO_LECTURA=1` se saca la reserva y el cobro: sirve para
 * medir las lecturas sin las escrituras que toman una segunda conexion del pool (ver docs/carga.md).
 */
function escenarios() {
  const todos = {
    operacion: {
      executor: 'ramping-vus',
      exec: 'operacion',
      startVUs: 0,
      stages: [
        { duration: __ENV.AKINE_CARGA_RAMPA1 || '30s', target: Math.min(100, VUS) },
        { duration: __ENV.AKINE_CARGA_MESETA1 || '1m30s', target: Math.min(100, VUS) },
        { duration: __ENV.AKINE_CARGA_RAMPA2 || '1m', target: VUS },
        { duration: __ENV.AKINE_CARGA_MESETA2 || '3m', target: VUS },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
    reservas: {
      executor: 'constant-arrival-rate',
      exec: 'reserva',
      rate: RESERVAS_POR_SEGUNDO,
      timeUnit: '1s',
      duration: __ENV.AKINE_CARGA_DURACION_RESERVAS || '6m',
      preAllocatedVUs: 30,
      maxVUs: 200,
    },
    login: {
      executor: 'constant-arrival-rate',
      exec: 'login',
      rate: 1,
      timeUnit: '3s',
      // Arranca pasado el minuto: la ventana del rate limit es fija de 1 minuto y el setup acaba
      // de gastar un login por cuenta operadora.
      startTime: '65s',
      duration: __ENV.AKINE_CARGA_DURACION_LOGIN || '5m',
      preAllocatedVUs: 2,
      maxVUs: 5,
    },
  };
  if (SOLO_LECTURA) {
    delete todos.reservas;
  }
  return todos;
}

const erroresTecnicos = new Rate('errores_tecnicos');
const reservasGanadas = new Counter('reservas_201');
const reservasPerdidas = new Counter('reservas_409');
const tokenVencido = new Counter('respuestas_401');

const ESPERADOS_EN_RESERVA = http.expectedStatuses({ min: 200, max: 299 }, 409);

/**
 * Setup: un login por cuenta operadora, espaciado, y los horarios que van a disputar las reservas.
 * Todo VU reutiliza estos tokens: ningun escenario de lectura vuelve a ingresar.
 */
export function setup() {
  const cuentas = [centro.cuentaAdmin, ...centro.operadores];
  const tokens = [];
  for (const c of cuentas) {
    tokens.push(ingresar(c, centro.organizationId, { fase: 'setup' }));
    sleep(1);
  }

  // Horarios de reserva: desde el dia 8 (despues de los dias precargados) y por 40 dias. Solo
  // vienen los libres, asi que una corrida nueva no disputa los que gano la anterior.
  const desde = fechaLocal(centro.timezone, 8);
  const agenda = exigir(
    http.get(
      `${API}/api/v1/consultorios/${centro.consultorioId}/ofertas/${centro.ofertaId}/agenda?desde=${desde}&hasta=${sumarDias(desde, 40)}`,
      { headers: conToken(tokens[0]), tags: { fase: 'setup' } },
    ),
    'agenda para las reservas',
  );
  const slots = [];
  for (const dia of agenda.dias) for (const s of dia.slots) slots.push(s.desde);
  console.log(`[carga] ${tokens.length} tokens, ${slots.length} horarios para disputar`);
  return { tokens, slots, inicio: Date.now() };
}

function registrar(r, endpoint, esperados) {
  const ok = esperados.includes(r.status);
  check(r, { [`${endpoint} responde ${esperados.join('/')}`]: () => ok });
  erroresTecnicos.add(!ok, { endpoint });
  if (r.status === 401) tokenVencido.add(1);
  return ok;
}

function get(token, ruta, endpoint) {
  const r = http.get(`${API}${ruta}`, {
    headers: conToken(token),
    tags: { endpoint, tipo: 'interactivo' },
  });
  registrar(r, endpoint, [200]);
  return r;
}

const REPORTES = ['OPERATIVO', 'TURNOS', 'CLINICO', 'ECONOMICO', 'FINANCIADORES'];

export function operacion(datos) {
  const token = alAzar(datos.tokens);
  const c = centro.consultorioId;
  const tz = centro.timezone;
  const dado = Math.random();

  if (dado < 0.25) {
    const fecha = fechaLocal(tz, Math.floor(Math.random() * 7));
    get(token, `/api/v1/consultorios/${c}/agenda?fecha=${fecha}`, 'agenda_dia');
  } else if (dado < 0.45) {
    const desde = fechaLocal(tz, Math.floor(Math.random() * 14));
    get(
      token,
      `/api/v1/consultorios/${c}/ofertas/${centro.ofertaId}/agenda?desde=${desde}&hasta=${sumarDias(desde, 7)}`,
      'buscar_slots',
    );
  } else if (dado < 0.65) {
    // La recepcion mira sobre todo HOY; a veces el dia siguiente.
    const fecha = fechaLocal(tz, Math.random() < 0.8 ? 0 : 1);
    get(token, `/api/v1/consultorios/${c}/turnos?fecha=${fecha}`, 'recepcion_dia');
  } else if (dado < 0.85) {
    get(token, `/api/v1/personas/${alAzar(centro.personas)}/resumen`, 'paciente_360');
  } else if (dado < 0.95 && SOLO_LECTURA) {
    get(token, `/api/v1/personas/${alAzar(centro.personas)}/resumen`, 'paciente_360');
  } else if (dado < 0.95) {
    const total = 1000 + Math.floor(Math.random() * 9) * 500;
    const r = http.post(
      `${API}/api/v1/consultorios/${c}/cobros`,
      JSON.stringify({
        personaId: alAzar(centro.personas),
        total,
        anticipo: total,
        moneda: 'ARS',
        medios: [{ medio: Math.random() < 0.6 ? 'EFECTIVO' : 'TRANSFERENCIA', importe: total }],
        idempotencyKey: nuevaClave(),
      }),
      { headers: conToken(token), tags: { endpoint: 'cobro', tipo: 'interactivo' } },
    );
    registrar(r, 'cobro', [201]);
  } else {
    const hasta = fechaLocal(tz);
    get(
      token,
      `/api/v1/consultorios/${c}/reportes/${alAzar(REPORTES)}?desde=${sumarDias(hasta, -30)}&hasta=${hasta}`,
      'reporte',
    );
  }
  sleep(entre(PENSAR_MIN, PENSAR_MAX));
}

/**
 * Reserva sobre el grupo caliente del momento. El grupo avanza cada SEGUNDOS_POR_GRUPO: los
 * primeros pedidos de cada grupo ganan su horario y el resto choca contra el mismo slot.
 */
export function reserva(datos) {
  const grupo = Math.floor((Date.now() - datos.inicio) / 1000 / SEGUNDOS_POR_GRUPO);
  const base = (grupo * SLOTS_CALIENTES) % Math.max(1, datos.slots.length - SLOTS_CALIENTES);
  const inicio = datos.slots[base + Math.floor(Math.random() * SLOTS_CALIENTES)];
  const r = http.post(
    `${API}/api/v1/consultorios/${centro.consultorioId}/turnos/ofertas/${centro.ofertaId}`,
    JSON.stringify({
      inicio,
      personaId: alAzar(centro.personas),
      profesionalId: centro.profesionalId,
      idempotencyKey: nuevaClave(),
    }),
    {
      headers: conToken(alAzar(datos.tokens)),
      tags: { endpoint: 'reserva', tipo: 'interactivo' },
      responseCallback: ESPERADOS_EN_RESERVA,
    },
  );
  registrar(r, 'reserva', [201, 409]);
  if (r.status === 201) reservasGanadas.add(1);
  if (r.status === 409) reservasPerdidas.add(1, { problema: tipoDeProblema(r) });
}

function tipoDeProblema(r) {
  try {
    return String(r.json('type')).replace('https://akine.app/problems/', '');
  } catch (_e) {
    return 'sin-cuerpo';
  }
}

/** Solo el POST de credenciales: es lo que paga bcrypt y lo que limita el rate limit. */
export function login() {
  const cuenta = centro.cuentasDeLogin[exec.scenario.iterationInTest % centro.cuentasDeLogin.length];
  const r = http.post(`${API}/api/v1/auth/login`, JSON.stringify({ email: cuenta, password: PASSWORD }), {
    headers: JSON_HEADERS,
    tags: { endpoint: 'login', tipo: 'interactivo' },
  });
  registrar(r, 'login', [200]);
}

// ---------------------------------------------------------------------------------------------
// Resumen
// ---------------------------------------------------------------------------------------------

function fila(nombre, d, reqs, segundos) {
  if (!d) return `| ${nombre} | — | — | — | — | — | — |`;
  const v = d.values;
  const rps = reqs ? (reqs.values.count / segundos).toFixed(1) : '—';
  const cumple = v['p(95)'] <= 300 && v['p(99)'] <= 800 ? 'si' : '**NO**';
  return `| ${nombre} | ${v.count} | ${rps} | ${v.med.toFixed(0)} | ${v['p(95)'].toFixed(0)} | ${v['p(99)'].toFixed(0)} | ${cumple} |`;
}

export function handleSummary(data) {
  const m = data.metrics;
  const segundos = data.state.testRunDurationMs / 1000;
  const lineas = [
    `# Carga G-10 — ${new Date().toISOString()}`,
    '',
    `Duracion ${segundos.toFixed(0)} s · VUs max ${m.vus_max ? m.vus_max.values.max : '—'} · ` +
      `${m.http_reqs.values.count} pedidos · ${m.http_reqs.values.rate.toFixed(1)} req/s`,
    '',
    '| Endpoint | Pedidos | req/s | p50 ms | p95 ms | p99 ms | SLO (p95≤300, p99≤800) |',
    '|---|---:|---:|---:|---:|---:|---|',
    ...ENDPOINTS.map((e) =>
      fila(e, m[`http_req_duration{endpoint:${e}}`], m[`http_reqs{endpoint:${e}}`], segundos),
    ),
    fila('**todo lo interactivo**', m['http_req_duration{tipo:interactivo}'], null, segundos),
    '',
    `Errores tecnicos: ${(m.errores_tecnicos.values.rate * 100).toFixed(2)} % · ` +
      `http_req_failed: ${(m.http_req_failed.values.rate * 100).toFixed(2)} % · ` +
      `reservas 201: ${m.reservas_201 ? m.reservas_201.values.count : 0} · ` +
      `reservas 409: ${m.reservas_409 ? m.reservas_409.values.count : 0} · ` +
      `401: ${m.respuestas_401 ? m.respuestas_401.values.count : 0}`,
    '',
  ];
  const md = lineas.join('\n');
  return {
    '/perf/salida/carga-resumen.json': JSON.stringify(data, null, 2),
    '/perf/salida/carga-resumen.md': md,
    stdout: `${md}\n`,
  };
}
