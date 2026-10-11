// Sembrado POR LA API del centro sobre el que corre la carga (AKINE G-10).
//
//   docker run --rm --network g10-net -v "$PWD/perf:/perf" grafana/k6:2.3.0 \
//     run /perf/k6/sembrado.js
//
// Escribe /perf/salida/sembrado.json, que lee `carga.js`. Nada pasa por SQL: es el mismo camino
// que `appKine-web/e2e/support/sembrado.ts` (alta self-service, activacion por el correo que llega
// a Mailpit, membership, disponibilidad, oferta, padron) y por eso lo que se mide despues es el
// producto y no un atajo.
//
// Tarda ~3 minutos y casi todo es espera: el alta self-service admite 5 por minuto y por IP
// (`register-max-attempts`), y el sembrado la respeta en vez de apagarla.

import http from 'k6/http';
import { sleep } from 'k6';

import {
  API,
  EMAIL_PLATAFORMA,
  JSON_HEADERS,
  MAILPIT,
  PASSWORD,
  conReintentoPor429,
  conToken,
  exigir,
  fechaLocal,
  ingresar,
  nuevaClave,
  sumarDias,
} from './lib.js';

/** Cuentas que operan durante la carga, ademas de la administradora (CONSULTORIO_ADMIN). */
// El plan BASICO del alta self-service admite 5 miembros activos: administradora + profesional + 3.
const OPERADORES = parseInt(__ENV.AKINE_CARGA_OPERADORES || '3', 10);
/** Cuentas que SOLO ingresan, para medir el login sin chocar con las que operan. */
const CUENTAS_DE_LOGIN = parseInt(__ENV.AKINE_CARGA_CUENTAS_LOGIN || '3', 10);
/** Personas del padron con perfil de paciente. */
const PERSONAS = parseInt(__ENV.AKINE_CARGA_PERSONAS || '150', 10);
/** Dias (desde hoy) que se pre-cargan con turnos, para que agenda y recepcion tengan filas. */
const DIAS_PRECARGADOS = parseInt(__ENV.AKINE_CARGA_DIAS_PRECARGADOS || '7', 10);

export const options = {
  setupTimeout: '20m',
  iterations: 1,
  vus: 1,
  // El sembrado no se mide: que no ensucie el resumen con umbrales.
  summaryTrendStats: ['avg', 'p(95)'],
};

const sufijo = `${Date.now().toString(36)}`;

function email(rol, i = 0) {
  return `carga.${rol}${i}.${sufijo}@ejemplo.test`;
}

function registrar(cuenta, organizacion) {
  const r = conReintentoPor429(() =>
    http.post(
      `${API}/api/v1/auth/register`,
      JSON.stringify({
        email: cuenta,
        firstName: 'Carga',
        lastName: `Sintetica ${sufijo}`,
        organizationName: organizacion,
        password: PASSWORD,
      }),
      { headers: { ...JSON_HEADERS, 'Idempotency-Key': nuevaClave() } },
    ),
  );
  exigir(r, `alta de ${cuenta}`, [202]);
}

/** Token del enlace de activacion mas reciente que Mailpit tiene para `cuenta`. */
function tokenDeActivacion(cuenta, esperaSegundos = 60) {
  const query = encodeURIComponent(`to:"${cuenta}" subject:"Activa"`);
  for (let i = 0; i < esperaSegundos; i++) {
    const r = http.get(`${MAILPIT}/api/v1/search?query=${query}`);
    if (r.status === 200) {
      const { messages } = r.json();
      if (messages && messages.length > 0) {
        const msg = http.get(`${MAILPIT}/api/v1/message/${messages[0].ID}`).json();
        const m = /[?&]token=([A-Za-z0-9_-]+)/.exec(msg.Text);
        if (m) {
          return m[1];
        }
      }
    }
    sleep(1);
  }
  return undefined;
}

function activar(cuenta, conPassword = false) {
  const token = tokenDeActivacion(cuenta);
  if (!token) {
    throw new Error(`no llego el correo de activacion de ${cuenta} a ${MAILPIT}`);
  }
  const cuerpo = conPassword ? { token, password: PASSWORD } : { token };
  exigir(
    http.post(`${API}/api/v1/auth/activate`, JSON.stringify(cuerpo), { headers: JSON_HEADERS }),
    `activacion de ${cuenta}`,
    [204],
  );
}

/** Un servicio del catalogo global; si esta vacio (base nueva) lo crea el admin de plataforma. */
function servicioAgendable(token) {
  const servicios = exigir(http.get(`${API}/api/v1/servicios`, { headers: conToken(token) }), 'servicios');
  if (servicios.length > 0) {
    return servicios[0].id;
  }
  let login = http.post(
    `${API}/api/v1/auth/login`,
    JSON.stringify({ email: EMAIL_PLATAFORMA, password: PASSWORD }),
    { headers: JSON_HEADERS },
  );
  if (login.status !== 200) {
    activar(EMAIL_PLATAFORMA, true);
    login = http.post(
      `${API}/api/v1/auth/login`,
      JSON.stringify({ email: EMAIL_PLATAFORMA, password: PASSWORD }),
      { headers: JSON_HEADERS },
    );
  }
  const plataforma = exigir(login, 'login del admin de plataforma', [200]).accessToken;
  exigir(
    http.post(
      `${API}/api/v1/servicios`,
      JSON.stringify({
        codigo: 'CARGA_KINESIOLOGIA',
        nombre: 'Kinesiologia (carga)',
        naturaleza: 'TERAPEUTICO',
        modalidadDefault: 'INDIVIDUAL',
        descripcion: 'Servicio sintetico sembrado por la prueba de carga G-10',
      }),
      { headers: conToken(plataforma) },
    ),
    'alta del servicio global',
    [201, 409],
  );
  return exigir(http.get(`${API}/api/v1/servicios`, { headers: conToken(token) }), 'servicios')[0].id;
}

export function setup() {
  const admin = email('admin');
  const profesional = email('profesional');
  const operadores = Array.from({ length: OPERADORES }, (_, i) => email('operador', i));
  const deLogin = Array.from({ length: CUENTAS_DE_LOGIN }, (_, i) => email('login', i));
  const nombreCentro = `Centro Carga G10 ${sufijo}`;

  console.log(`[sembrado] altas self-service: ${2 + OPERADORES + CUENTAS_DE_LOGIN} (5/min por IP)`);
  registrar(admin, nombreCentro);
  registrar(profesional, `Consultorio propio ${sufijo}`);
  for (const c of operadores) registrar(c, `Consultorio operador ${c}`);
  for (const c of deLogin) registrar(c, `Consultorio login ${c}`);
  for (const c of [admin, profesional, ...operadores, ...deLogin]) activar(c);

  const token = ingresar(admin);
  const [contexto] = exigir(http.get(`${API}/api/v1/me/contexts`, { headers: conToken(token) }), 'contextos');
  const { organizationId, consultorioId } = contexto;
  const sede = exigir(
    http.get(`${API}/api/v1/organizations/${organizationId}/consultorios/${consultorioId}`, {
      headers: conToken(token),
    }),
    'sede',
  );
  const timezone = sede.timezone;

  console.log('[sembrado] memberships, disponibilidad y oferta');
  const membership = (cuenta, roleCode) =>
    exigir(
      conReintentoPor429(() =>
        http.post(
          `${API}/api/v1/memberships`,
          JSON.stringify({ email: cuenta, roleCode, consultorioId, reason: 'Sembrado de la prueba de carga G-10' }),
          { headers: conToken(token) },
        ),
      ),
      `membership de ${cuenta}`,
    );
  const profesionalId = membership(profesional, 'PROFESIONAL').membershipId;
  for (const c of operadores) membership(c, 'CONSULTORIO_ADMIN');

  // De 00:00 a 23:45 los siete dias, como el sembrado de los E2E: la recepcion y la reserva
  // necesitan horarios todavia futuros a cualquier hora de la corrida.
  for (let diaSemana = 1; diaSemana <= 7; diaSemana++) {
    exigir(
      http.post(
        `${API}/api/v1/consultorios/${consultorioId}/profesionales/${profesionalId}/disponibilidad`,
        JSON.stringify({ diaSemana, horaDesde: '00:00', horaHasta: '23:45' }),
        { headers: conToken(token) },
      ),
      `disponibilidad ${diaSemana}`,
    );
  }

  const servicioId = servicioAgendable(token);
  const oferta = exigir(
    http.post(
      `${API}/api/v1/consultorios/${consultorioId}/ofertas`,
      JSON.stringify({
        servicioId,
        nombreComercial: `Kinesio carga ${sufijo}`,
        capacidad: 1,
        duracionMinutos: 30,
        requiereProfesional: true,
        requiereEspacio: false,
      }),
      { headers: conToken(token) },
    ),
    'oferta',
  );
  // Sin habilitacion explicita la oferta la presta cualquier PROFESIONAL de la sede: alcanza, y
  // evita que la habilitacion nacida AHORA no valga para hoy (se evalua al mediodia local).

  console.log(`[sembrado] ${PERSONAS} personas con perfil de paciente`);
  const personas = [];
  for (let i = 0; i < PERSONAS; i++) {
    const p = exigir(
      http.post(
        `${API}/api/v1/personas`,
        JSON.stringify({ nombre: 'Paciente', apellido: `Carga${sufijo}${i}` }),
        { headers: conToken(token) },
      ),
      'persona',
    );
    exigir(
      http.post(`${API}/api/v1/personas/${p.id}/perfil-paciente`, '{}', { headers: conToken(token) }),
      'perfil de paciente',
    );
    personas.push(p.id);
  }

  console.log('[sembrado] caja abierta');
  exigir(
    http.post(
      `${API}/api/v1/consultorios/${consultorioId}/caja/jornadas`,
      JSON.stringify({ moneda: 'ARS', saldoInicial: 0 }),
      { headers: conToken(token) },
    ),
    'apertura de caja',
    [200, 201, 409],
  );

  // Turnos de los proximos dias: la mitad de los horarios futuros de cada dia, para que la agenda
  // del dia y la recepcion devuelvan filas de verdad y no listas vacias.
  console.log(`[sembrado] turnos de ${DIAS_PRECARGADOS} dias`);
  const hoy = fechaLocal(timezone);
  const agenda = exigir(
    http.get(
      `${API}/api/v1/consultorios/${consultorioId}/ofertas/${oferta.id}/agenda?desde=${hoy}&hasta=${sumarDias(hoy, DIAS_PRECARGADOS)}`,
      { headers: conToken(token) },
    ),
    'agenda de la oferta',
  );
  let turnos = 0;
  let k = 0;
  for (const dia of agenda.dias) {
    const futuros = dia.slots.filter((s) => new Date(s.desde).getTime() > Date.now() + 120000);
    for (let i = 0; i < futuros.length; i += 2) {
      const r = http.post(
        `${API}/api/v1/consultorios/${consultorioId}/turnos/ofertas/${oferta.id}`,
        JSON.stringify({
          inicio: futuros[i].desde,
          personaId: personas[k++ % personas.length],
          profesionalId,
          idempotencyKey: nuevaClave(),
        }),
        { headers: conToken(token) },
      );
      exigir(r, `reserva ${futuros[i].desde}`, [201, 409]);
      if (r.status === 201) turnos++;
    }
  }

  // Algunos anticipos, para que la ficha 360 tenga situacion economica.
  for (let i = 0; i < Math.min(30, personas.length); i++) {
    exigir(
      http.post(
        `${API}/api/v1/consultorios/${consultorioId}/cobros`,
        JSON.stringify({
          personaId: personas[i],
          total: 5000,
          anticipo: 5000,
          moneda: 'ARS',
          medios: [{ medio: 'EFECTIVO', importe: 5000 }],
          idempotencyKey: nuevaClave(),
        }),
        { headers: conToken(token) },
      ),
      'cobro de sembrado',
    );
  }

  const centro = {
    api: API,
    sufijo,
    timezone,
    organizationId,
    consultorioId,
    ofertaId: oferta.id,
    profesionalId,
    cuentaAdmin: admin,
    operadores,
    cuentasDeLogin: deLogin,
    personas,
    turnosPrecargados: turnos,
    sembradoEn: new Date().toISOString(),
  };
  console.log(`[sembrado] listo: ${turnos} turnos, ${personas.length} personas`);
  return centro;
}

export default function () {}

export function handleSummary(data) {
  return {
    ...(data.setup_data ? { '/perf/salida/sembrado.json': JSON.stringify(data.setup_data, null, 2) } : {}),
    stdout: `sembrado escrito en perf/salida/sembrado.json\n`,
  };
}
