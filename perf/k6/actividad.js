// Carga de F9 contra los SLO de ADR-0016 (AKINE-09.03): inscribir, asistencia en lote y venta de
// pases, con lecturas de la grilla de clases, participantes y pases de fondo.
//
//   perf/stack.sh actividad          # -e VAR=valor para k6, como `carga`
//
// Necesita /perf/salida/sembrado.json y /perf/salida/sembrado-actividad.json. Escribe
// /perf/salida/actividad-resumen.{md,json}. Corre SOLA, no junto con `carga.js`: lo que se mide es
// cuanto aguanta F9, no la mezcla de las dos.
//
// Cuatro escenarios a la vez:
//
//   inscribir   tasa fija de altas en clases futuras, persona al azar y lista de espera aceptada.
//               El 409 `inscripcion-duplicada` (la persona ya estaba) es la respuesta correcta y no
//               cuenta como error.
//   lote        tasa fija de asistencias en lote sobre las clases en curso, en ronda: cada lote
//               marca a todos los inscriptos de una clase con un resultado al azar —presente o
//               ausente—, asi que casi todo item es una correccion que consume o devuelve un
//               credito. La ronda hace que dos lotes no caigan sobre la misma clase a la vez, que
//               es lo que pasa en un mostrador real (un instructor por clase).
//   venta       tasa fija de ventas de pases del mismo producto, con clave nueva cada una.
//   lecturas    usuarios (ramping-vus) que miran la grilla de la semana, los participantes de una
//               clase y los pases de una persona, con tiempo de lectura entre pedidos.

import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Rate } from 'k6/metrics';

import { API, alAzar, conToken, entre, fechaLocal, ingresar, nuevaClave, sumarDias } from './lib.js';

const centro = JSON.parse(open('/perf/salida/sembrado.json'));
const actividad = JSON.parse(open('/perf/salida/sembrado-actividad.json'));

const INSCRIPCIONES_RPS = parseInt(__ENV.AKINE_ACTIVIDAD_INSCRIPCIONES_RPS || '10', 10);
const LOTES_RPS = parseInt(__ENV.AKINE_ACTIVIDAD_LOTES_RPS || '2', 10);
const VENTAS_RPS = parseInt(__ENV.AKINE_ACTIVIDAD_VENTAS_RPS || '5', 10);
const VUS = parseInt(__ENV.AKINE_ACTIVIDAD_VUS || '200', 10);
const DURACION = __ENV.AKINE_ACTIVIDAD_DURACION || '5m';

const ENDPOINTS = ['inscribir', 'asistencia_lote', 'vender_pase', 'clases_semana', 'participantes', 'pases_persona'];
const SLO = ['p(95)<300', 'p(99)<800'];

const thresholds = {
  http_req_failed: ['rate<0.01'],
  errores_tecnicos: ['rate<0.01'],
  'http_req_duration{tipo:interactivo}': SLO,
};
for (const e of ENDPOINTS) {
  thresholds[`http_req_duration{endpoint:${e}}`] = SLO;
  thresholds[`http_reqs{endpoint:${e}}`] = ['count>=0'];
}

export const options = {
  setupTimeout: '2m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  thresholds,
  scenarios: {
    inscribir: {
      executor: 'constant-arrival-rate',
      exec: 'inscribir',
      rate: INSCRIPCIONES_RPS,
      timeUnit: '1s',
      duration: DURACION,
      preAllocatedVUs: 20,
      maxVUs: 200,
    },
    lote: {
      executor: 'constant-arrival-rate',
      exec: 'lote',
      rate: LOTES_RPS,
      timeUnit: '1s',
      duration: DURACION,
      preAllocatedVUs: 10,
      maxVUs: 100,
    },
    venta: {
      executor: 'constant-arrival-rate',
      exec: 'venta',
      rate: VENTAS_RPS,
      timeUnit: '1s',
      duration: DURACION,
      preAllocatedVUs: 10,
      maxVUs: 100,
    },
    lecturas: {
      executor: 'ramping-vus',
      exec: 'lecturas',
      startVUs: 0,
      stages: [
        { duration: '30s', target: VUS },
        { duration: DURACION, target: VUS },
        { duration: '15s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
};

const erroresTecnicos = new Rate('errores_tecnicos');
const itemsConError = new Rate('items_de_lote_con_error');

const INSCRIBIR_ESPERADOS = http.expectedStatuses({ min: 200, max: 201 }, 409);

export function setup() {
  const cuentas = [centro.cuentaAdmin, ...centro.operadores];
  const tokens = [];
  for (const c of cuentas) {
    tokens.push(ingresar(c, centro.organizationId, { fase: 'setup' }));
    sleep(1);
  }
  return { tokens };
}

function registrar(r, endpoint, esperados) {
  const ok = esperados.includes(r.status);
  check(r, { [`${endpoint} responde ${esperados.join('/')}`]: () => ok });
  erroresTecnicos.add(!ok, { endpoint });
  return ok;
}

function post(token, ruta, cuerpo, endpoint, esperados, responseCallback) {
  const r = http.post(`${API}${ruta}`, JSON.stringify(cuerpo), {
    headers: conToken(token),
    tags: { endpoint, tipo: 'interactivo' },
    ...(responseCallback ? { responseCallback } : {}),
  });
  registrar(r, endpoint, esperados);
  return r;
}

export function inscribir(datos) {
  const clase = alAzar(actividad.clasesFuturas);
  post(
    alAzar(datos.tokens),
    `/api/v1/consultorios/${centro.consultorioId}/clases/${clase}/inscripciones`,
    { personaId: alAzar(centro.personas), aceptaListaEspera: true, idempotencyKey: nuevaClave() },
    'inscribir',
    [200, 201, 409],
    INSCRIBIR_ESPERADOS,
  );
}

export function lote(datos) {
  const clases = actividad.clasesEnCurso;
  const clase = clases[exec.scenario.iterationInTest % clases.length];
  const items = clase.inscripciones.map((inscripcionId) => ({
    inscripcionId,
    resultado: Math.random() < 0.5 ? 'PRESENTE' : 'AUSENTE',
    motivo: 'Correccion de la prueba de carga',
  }));
  const r = post(
    alAzar(datos.tokens),
    `/api/v1/consultorios/${centro.consultorioId}/clases/${clase.id}/asistencias/lote`,
    { items },
    'asistencia_lote',
    [200],
  );
  if (r.status === 200) {
    for (const item of r.json('items')) itemsConError.add(item.problemType != null);
  }
}

export function venta(datos) {
  post(
    alAzar(datos.tokens),
    `/api/v1/consultorios/${centro.consultorioId}/pases`,
    { productoId: actividad.productoId, personaId: alAzar(centro.personas), idempotencyKey: nuevaClave() },
    'vender_pase',
    [201],
  );
}

function get(token, ruta, endpoint) {
  const r = http.get(`${API}${ruta}`, { headers: conToken(token), tags: { endpoint, tipo: 'interactivo' } });
  registrar(r, endpoint, [200]);
}

export function lecturas(datos) {
  const token = alAzar(datos.tokens);
  const c = centro.consultorioId;
  const dado = Math.random();
  if (dado < 0.4) {
    const desde = `${fechaLocal(centro.timezone)}T00:00:00Z`;
    const hasta = `${sumarDias(fechaLocal(centro.timezone), 7)}T00:00:00Z`;
    get(token, `/api/v1/consultorios/${c}/clases?desde=${desde}&hasta=${hasta}`, 'clases_semana');
  } else if (dado < 0.7) {
    const clase = alAzar(actividad.clasesEnCurso).id;
    get(token, `/api/v1/consultorios/${c}/clases/${clase}/inscripciones`, 'participantes');
  } else {
    get(token, `/api/v1/consultorios/${c}/personas/${alAzar(centro.personas)}/pases`, 'pases_persona');
  }
  sleep(entre(1, 5));
}

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
  const md = [
    `# Carga F9 (09.03) — ${new Date().toISOString()}`,
    '',
    `Duracion ${segundos.toFixed(0)} s · VUs max ${m.vus_max ? m.vus_max.values.max : '—'} · ` +
      `${m.http_reqs.values.count} pedidos · ${m.http_reqs.values.rate.toFixed(1)} req/s`,
    '',
    '| Endpoint | Pedidos | req/s | p50 ms | p95 ms | p99 ms | SLO (p95≤300, p99≤800) |',
    '|---|---:|---:|---:|---:|---:|---|',
    ...ENDPOINTS.map((e) => fila(e, m[`http_req_duration{endpoint:${e}}`], m[`http_reqs{endpoint:${e}}`], segundos)),
    fila('**todo lo interactivo**', m['http_req_duration{tipo:interactivo}'], null, segundos),
    '',
    `Errores tecnicos: ${(m.errores_tecnicos.values.rate * 100).toFixed(2)} % · ` +
      `http_req_failed: ${(m.http_req_failed.values.rate * 100).toFixed(2)} % · ` +
      `items de lote con error: ${m.items_de_lote_con_error ? (m.items_de_lote_con_error.values.rate * 100).toFixed(2) : '—'} %`,
    '',
  ].join('\n');
  return {
    '/perf/salida/actividad-resumen.json': JSON.stringify(data, null, 2),
    '/perf/salida/actividad-resumen.md': md,
    stdout: `${md}\n`,
  };
}
