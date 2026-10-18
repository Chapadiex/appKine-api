// Sembrado POR LA API de lo que necesita la carga de F9 (AKINE-09.03), ENCIMA del centro de G-10.
//
//   perf/stack.sh sembrar            # primero el centro (sembrado.json)
//   perf/stack.sh sembrar-actividad  # despues esto     (sembrado-actividad.json)
//
// Lee /perf/salida/sembrado.json y agrega: una oferta GRUPAL que cobra POR_PACK, un producto pack
// con creditos de sobra, un pase vendido a cada persona del padron, clases futuras vacias (donde
// la carga inscribe) y clases EN CURSO con inscriptos (donde la carga toma lista en lote). Nada por
// SQL, por la misma razon que el sembrado de G-10: lo que se mide despues es el producto.

import http from 'k6/http';

import { API, conToken, exigir, fechaLocal, ingresar, nuevaClave, sumarDias } from './lib.js';

const centro = JSON.parse(open('/perf/salida/sembrado.json'));

/** Clases futuras donde la carga inscribe. Muchas, para que las inscripciones no choquen todas. */
const CLASES_FUTURAS = parseInt(__ENV.AKINE_ACTIVIDAD_CLASES_FUTURAS || '60', 10);
/** Clases ya iniciadas donde la carga toma lista en lote. */
const CLASES_EN_CURSO = parseInt(__ENV.AKINE_ACTIVIDAD_CLASES_EN_CURSO || '20', 10);
/** Inscriptos de cada clase en curso: el tamano del lote. */
const INSCRIPTOS_POR_CLASE = parseInt(__ENV.AKINE_ACTIVIDAD_INSCRIPTOS || '25', 10);
/** Cupo de cada clase. La oferta lo acota tambien. */
const CAPACIDAD = parseInt(__ENV.AKINE_ACTIVIDAD_CAPACIDAD || '30', 10);

export const options = {
  setupTimeout: '20m',
  iterations: 1,
  vus: 1,
  summaryTrendStats: ['avg', 'p(95)'],
};

const sufijo = `${Date.now().toString(36)}`;

export function setup() {
  const token = ingresar(centro.cuentaAdmin, centro.organizationId);
  const c = centro.consultorioId;
  const h = { headers: conToken(token) };

  const [servicio] = exigir(http.get(`${API}/api/v1/servicios`, h), 'servicios');
  const oferta = exigir(
    http.post(
      `${API}/api/v1/consultorios/${c}/ofertas`,
      JSON.stringify({
        servicioId: servicio.id,
        nombreComercial: `Pilates carga ${sufijo}`,
        modalidad: 'GRUPAL',
        capacidad: CAPACIDAD,
        duracionMinutos: 60,
        requiereProfesional: false,
        requiereEspacio: false,
        esquemaCobro: 'POR_PACK',
      }),
      h,
    ),
    'oferta grupal',
  );

  const hoy = fechaLocal(centro.timezone);
  const producto = exigir(
    http.post(
      `${API}/api/v1/consultorios/${c}/productos`,
      JSON.stringify({
        ofertaId: oferta.id,
        nombre: `Pack carga ${sufijo}`,
        // Creditos de sobra: la asistencia en lote alterna presente y ausente, y un pase agotado
        // haria que la carga midiera "sin pase elegible" en vez del consumo.
        creditos: 5000,
        precio: '40000.00',
        moneda: 'ARS',
        vigenciaDias: 365,
        vigenciaDesde: sumarDias(hoy, -1),
      }),
      h,
    ),
    'producto pack',
  );

  console.log(`[sembrado-actividad] ${centro.personas.length} pases`);
  for (const personaId of centro.personas) {
    exigir(
      http.post(
        `${API}/api/v1/consultorios/${c}/pases`,
        JSON.stringify({ productoId: producto.id, personaId, idempotencyKey: nuevaClave() }),
        h,
      ),
      'venta de pase',
      [201],
    );
  }

  // Las clases no ocupan profesional ni box: pueden superponerse sin chocar. Una por hora desde
  // manana a las 08:00 UTC, para no depender de la hora a la que corra el sembrado.
  const manana = Date.parse(`${sumarDias(hoy, 1)}T08:00:00Z`);
  const programar = (i) =>
    exigir(
      http.post(
        `${API}/api/v1/consultorios/${c}/clases/ofertas/${oferta.id}`,
        JSON.stringify({
          inicio: new Date(manana + i * 3600000).toISOString(),
          fin: new Date(manana + i * 3600000 + 3600000).toISOString(),
          capacidad: CAPACIDAD,
          titulo: `Clase carga ${i}`,
          idempotencyKey: nuevaClave(),
        }),
        h,
      ),
      `clase ${i}`,
    ).id;

  console.log(`[sembrado-actividad] ${CLASES_FUTURAS} clases futuras y ${CLASES_EN_CURSO} en curso`);
  const clasesFuturas = [];
  for (let i = 0; i < CLASES_FUTURAS; i++) clasesFuturas.push(programar(i));

  const clasesEnCurso = [];
  let k = 0;
  for (let i = 0; i < CLASES_EN_CURSO; i++) {
    const claseId = programar(CLASES_FUTURAS + i);
    const inscripciones = [];
    for (let j = 0; j < INSCRIPTOS_POR_CLASE; j++) {
      const r = exigir(
        http.post(
          `${API}/api/v1/consultorios/${c}/clases/${claseId}/inscripciones`,
          JSON.stringify({ personaId: centro.personas[k++ % centro.personas.length], idempotencyKey: nuevaClave() }),
          h,
        ),
        'inscripcion',
        [201],
      );
      inscripciones.push(r.inscripcion.id);
    }
    // Iniciar no mira la hora: la clase de manana se toma como en curso.
    exigir(http.post(`${API}/api/v1/consultorios/${c}/clases/${claseId}/inicio`, null, h), 'inicio de clase');
    clasesEnCurso.push({ id: claseId, inscripciones });
  }

  const actividad = {
    sufijo,
    ofertaId: oferta.id,
    productoId: producto.id,
    clasesFuturas,
    clasesEnCurso,
    sembradoEn: new Date().toISOString(),
  };
  console.log('[sembrado-actividad] listo');
  return actividad;
}

export default function () {}

export function handleSummary(data) {
  return {
    ...(data.setup_data
      ? { '/perf/salida/sembrado-actividad.json': JSON.stringify(data.setup_data, null, 2) }
      : {}),
    stdout: 'sembrado escrito en perf/salida/sembrado-actividad.json\n',
  };
}
