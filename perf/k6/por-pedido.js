// Cuantas sentencias y transacciones cuesta UN pedido de cada endpoint (docs/carga.md §9).
//
// Lo corre `perf/stack.sh por-pedido`, que fotografia los contadores de MySQL antes y despues de
// cada endpoint y divide por la cantidad de pedidos. Un solo usuario, en serie y sin pausa: lo
// que se mide es el costo de cada pedido, no la concurrencia.
//
//   AKINE_POR_PEDIDO_ENDPOINT=token     hace el login una vez y deja el token en salida/token.json
//   AKINE_POR_PEDIDO_ENDPOINT=<nombre>  repite ese endpoint AKINE_POR_PEDIDO_N veces con ese token
//
// El login va aparte para que su costo no se mezcle con el del endpoint que se mide. El token
// dura 10 minutos: la ronda entera cabe holgada.

import http from 'k6/http';

import {
  API,
  JSON_HEADERS,
  PASSWORD,
  alAzar,
  conToken,
  fechaLocal,
  ingresar,
  nuevaClave,
  sumarDias,
} from './lib.js';

const centro = JSON.parse(open('/perf/salida/sembrado.json'));
const ENDPOINT = __ENV.AKINE_POR_PEDIDO_ENDPOINT || 'token';
const N = parseInt(__ENV.AKINE_POR_PEDIDO_N || '100', 10);
const TOKEN = ENDPOINT === 'token' ? null : JSON.parse(open('/perf/salida/token.json')).token;

export const options = {
  scenarios: {
    serie: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: ENDPOINT === 'token' ? 1 : N,
      maxDuration: '5m',
    },
  },
};

let reservas = 0;

function pedir(endpoint, i) {
  const c = centro.consultorioId;
  const tz = centro.timezone;
  const persona = centro.personas[i % centro.personas.length];
  switch (endpoint) {
    case 'login':
      return http.post(
        `${API}/api/v1/auth/login`,
        JSON.stringify({ email: alAzar(centro.cuentasDeLogin), password: PASSWORD }),
        { headers: JSON_HEADERS },
      );
    case 'agenda_dia':
      return http.get(`${API}/api/v1/consultorios/${c}/agenda?fecha=${fechaLocal(tz, i % 7)}`, {
        headers: conToken(TOKEN),
      });
    case 'buscar_slots': {
      const desde = fechaLocal(tz, i % 14);
      return http.get(
        `${API}/api/v1/consultorios/${c}/ofertas/${centro.ofertaId}/agenda?desde=${desde}&hasta=${sumarDias(desde, 7)}`,
        { headers: conToken(TOKEN) },
      );
    }
    case 'recepcion_dia':
      return http.get(`${API}/api/v1/consultorios/${c}/turnos?fecha=${fechaLocal(tz, 0)}`, {
        headers: conToken(TOKEN),
      });
    case 'paciente_360':
      return http.get(`${API}/api/v1/personas/${persona}/resumen`, { headers: conToken(TOKEN) });
    case 'cobro': {
      const total = 1500;
      return http.post(
        `${API}/api/v1/consultorios/${c}/cobros`,
        JSON.stringify({
          personaId: persona,
          total,
          anticipo: total,
          moneda: 'ARS',
          medios: [{ medio: 'EFECTIVO', importe: total }],
          idempotencyKey: nuevaClave(),
        }),
        { headers: conToken(TOKEN) },
      );
    }
    case 'reporte': {
      const hasta = fechaLocal(tz);
      return http.get(
        `${API}/api/v1/consultorios/${c}/reportes/OPERATIVO?desde=${sumarDias(hasta, -30)}&hasta=${hasta}`,
        { headers: conToken(TOKEN) },
      );
    }
    case 'reserva': {
      // Un horario libre distinto por pedido, lejos de los que disputa la carga: todos ganan.
      const dia = sumarDias(fechaLocal(tz, 60), Math.floor(reservas / 40));
      const minuto = (reservas % 40) * 30;
      reservas++;
      const hh = String(Math.floor(minuto / 60)).padStart(2, '0');
      const mm = String(minuto % 60).padStart(2, '0');
      const offset = String(Math.abs(-3)).padStart(2, '0');
      return http.post(
        `${API}/api/v1/consultorios/${c}/turnos/ofertas/${centro.ofertaId}`,
        JSON.stringify({
          inicio: `${dia}T${hh}:${mm}:00-${offset}:00`,
          personaId: persona,
          profesionalId: centro.profesionalId,
          idempotencyKey: nuevaClave(),
        }),
        { headers: conToken(TOKEN) },
      );
    }
    default:
      throw new Error(`endpoint desconocido: ${endpoint}`);
  }
}

export function setup() {
  if (ENDPOINT !== 'token') return {};
  return { token: ingresar(centro.cuentaAdmin, centro.organizationId) };
}

const estados = {};

export default function () {
  if (ENDPOINT === 'token') return;
  const i = __ITER;
  const r = pedir(ENDPOINT, i);
  estados[r.status] = (estados[r.status] || 0) + 1;
  if (i === N - 1) console.log(`[por-pedido] ${ENDPOINT}: ${JSON.stringify(estados)}`);
}

export function handleSummary(data) {
  if (ENDPOINT === 'token') {
    return { '/perf/salida/token.json': JSON.stringify({ token: data.setup_data.token }) };
  }
  return { stdout: '' };
}
