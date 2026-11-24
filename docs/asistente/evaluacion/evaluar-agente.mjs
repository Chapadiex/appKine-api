// Evalúa el agente del asistente contra la siembra fija (sembrar-agente.mjs) con el backend levantado.
// Pasa cada pregunta por POST /api/v1/consultorios/{sede}/asistente/consultas como el actor que pide
// el set, y mide lo que pide AKINE-asistente-agente.md §9.
//
//   node evaluar-agente.mjs [--set agente-preguntas.json] [--base-url URL] [--solo grupo1,grupo2]
//                           [--actor ADMIN] [--limite N] [--precio 4,20,0.20] [--vueltas 2.5]
//                           [--estado .estado/agente-siembra.json] [--dry-run]
//
// --set acepta también los sets viejos de acciones (acciones.json, acciones-adversas.json: {id,q,e:{accion}}),
// que se convierten al vuelo y se corren como ADMIN.
// --dry-run imprime el plan (preguntas por grupo y actor, llamadas y costo estimado) y NO llama a nada.
//
// Puntaje de una pregunta: correcta si `resultado` está entre los admitidos, cada elemento de
// `debeContener` aparece (texto + opciones; un arreglo es "alguno de") y ninguno de `noDebeContener`
// aparece (texto + filas + opciones). Con `accion` y resultado PROPUESTA, el tipo de la tarjeta tiene que coincidir.
// La comparación ignora mayúsculas, acentos y separadores de miles ("$18.000" contiene "18000").
//
// Costo: lee de la auditoría ASISTENTE_CONSULTA (tokensEntrada, tokensSalida, tokensCacheLeida) con la
// cuenta ADMIN (auditoria:read). Si la auditoría no los trae, ESTIMA con --precio y --vueltas y lo dice.
// La contraseña de las cuentas es la de la siembra (AKINE_EVAL_PASSWORD, obligatoria salvo en --dry-run).
// Sólo corre contra un backend local (localhost, 127.0.0.1, [::1], *.localhost, *.test) salvo --forzar-remoto.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';


// Sólo contra un backend local: la siembra crea cuentas y la evaluación manda datos al proveedor del
// modelo. Otro host exige --forzar-remoto. (Mismo helper en sembrar-agente.mjs y evaluar-agente.mjs.)
function exigirBackendLocal(base, forzar) {
  let host;
  try {
    host = new URL(base).hostname.replace(/^\[|\]$/g, '').toLowerCase();
  } catch {
    console.error(`--base-url inválida: ${base}`);
    process.exit(1);
  }
  const local = ['localhost', '127.0.0.1', '::1'].includes(host) || host.endsWith('.localhost') || host.endsWith('.test');
  if (!local && !forzar) {
    console.error(`Rechazado: ${base} no es un backend local (localhost, 127.0.0.1, [::1], *.localhost o *.test). ` +
      'Este script crea cuentas y datos sintéticos y, al evaluar, manda resultados al proveedor del modelo. ' +
      'Si de verdad es un entorno de prueba, repetir con --forzar-remoto.');
    process.exit(1);
  }
}

function exigirEnv(...nombres) {
  const faltan = nombres.filter((n) => !process.env[n]);
  if (faltan.length) {
    console.error(`Faltan variables de entorno: ${faltan.join(', ')} (ver agente-resultados.md, "Cómo se corre")`);
    process.exit(1);
  }
}

const dir = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const flag = (n) => args.includes(`--${n}`);
const valor = (n, d) => {
  const i = args.indexOf(`--${n}`);
  return i >= 0 && args[i + 1] !== undefined ? args[i + 1] : d;
};
const ruta = (p) => (isAbsolute(p) ? p : join(dir, p));

const SET = valor('set', 'agente-preguntas.json');
const BASE = valor('base-url', process.env.AKINE_API ?? 'http://localhost:8080').replace(/\/+$/, '');
exigirBackendLocal(BASE, flag('forzar-remoto'));
const ESTADO = ruta(valor('estado', join('.estado', 'agente-siembra.json')));
// Contraseña de las cuentas sintéticas de la siembra: sólo por entorno (exigida antes de llamar a nada).
const PASSWORD = process.env.AKINE_EVAL_PASSWORD;
const [P_IN, P_OUT, P_CACHE] = valor('precio', '4,20,0.20').split(',').map(Number);
const VUELTAS = Number(valor('vueltas', '2.5'));
const SOLO = valor('solo', null)?.split(',');
const ACTOR = valor('actor', null);
const LIMITE = Number(valor('limite', '0'));

// ---------------------------------------------------------------------------------------------
// El set
// ---------------------------------------------------------------------------------------------

function cargarSet(archivo) {
  const crudo = JSON.parse(readFileSync(ruta(archivo), 'utf8'));
  const lista = Array.isArray(crudo) ? crudo : crudo.preguntas;
  if (lista.length && lista[0].q !== undefined && lista[0].e?.accion !== undefined) {
    // Formato de acciones.json: frase de acción → tarjeta del tipo, o un corte por la persona; NINGUNA → sin tarjeta.
    return lista.map((x) => {
      const ninguna = x.e.accion === 'NINGUNA';
      return {
        id: x.id, grupo: `acciones-${x.e.accion.toLowerCase()}`, pregunta: x.q, actor: 'ADMIN',
        esperado: ninguna
          ? { resultado: ['RESPONDIDA', 'ACLARACION', 'FUERA_DE_ALCANCE', 'SIN_PERMISO', 'FALTA_PERSONA', 'PERSONA_NO_ENCONTRADA'], debeContener: [], noDebeContener: [], debePreguntar: false, admitePregunta: true }
          : { resultado: ['PROPUESTA', 'ACLARACION', 'FALTA_PERSONA', 'PERSONA_NO_ENCONTRADA', ...(x.e.accion === 'ASISTENCIA_CLASE' ? ['RESPONDIDA'] : [])], debeContener: [], noDebeContener: [], debePreguntar: false, admitePregunta: true, accion: x.e.accion },
      };
    });
  }
  return lista;
}

let preguntas = cargarSet(SET);
if (SOLO) preguntas = preguntas.filter((p) => SOLO.includes(p.grupo));
if (ACTOR) preguntas = preguntas.filter((p) => p.actor === ACTOR);
if (LIMITE > 0) preguntas = preguntas.slice(0, LIMITE);
if (preguntas.length === 0) throw new Error('el filtro no dejó ninguna pregunta');

const estado = existsSync(ESTADO) ? JSON.parse(readFileSync(ESTADO, 'utf8')) : null;

// ---------------------------------------------------------------------------------------------
// Costo estimado (sin tokens reales). Por vuelta del modelo: ~6k tokens de entrada, de los que ~5k son
// prefijo cacheado (system + herramientas + historial) leído a P_CACHE, ~1k nuevos a P_IN, y ~400 de salida.
// ---------------------------------------------------------------------------------------------

const POR_VUELTA = { entradaNueva: 1000, cacheLeida: 5000, salida: 400 };
const costoVuelta = (conCache) =>
  conCache
    ? (POR_VUELTA.entradaNueva * P_IN + POR_VUELTA.cacheLeida * P_CACHE + POR_VUELTA.salida * P_OUT) / 1e6
    : ((POR_VUELTA.entradaNueva + POR_VUELTA.cacheLeida) * P_IN + POR_VUELTA.salida * P_OUT) / 1e6;
const usd = (x) => `US$ ${x.toFixed(2)}`;

if (flag('dry-run')) {
  const porGrupo = {};
  const porActor = {};
  for (const p of preguntas) {
    porGrupo[p.grupo] = (porGrupo[p.grupo] ?? 0) + 1;
    porActor[p.actor] = (porActor[p.actor] ?? 0) + 1;
  }
  console.log(`Plan de evaluación (--dry-run: no se llama a nada)\n`);
  console.log(`Set ${basename(SET)}: ${preguntas.length} preguntas en ${Object.keys(porGrupo).length} grupos`);
  console.log(`Backend ${BASE}; siembra ${estado ? `${ESTADO} (del ${estado.fecha})` : `NO encontrada en ${ESTADO}: correr sembrar-agente.mjs antes`}`);
  console.log(`Actores: ${Object.entries(porActor).map(([a, n]) => `${a} ${n}`).join(', ')}`);
  console.log('\nGrupos:');
  Object.entries(porGrupo).forEach(([g, n]) => console.log(`  ${g.padEnd(42)} ${n}`));
  const turnos = preguntas.length * VUELTAS;
  console.log(`\nLlamadas: ${Object.keys(porActor).length + 1} logins, ${preguntas.length} consultas al backend, ~${Math.round(turnos)} llamadas al modelo (${VUELTAS} vueltas por pregunta), ${preguntas.length} lecturas de auditoría`);
  console.log(`Costo estimado con precio ${P_IN}/${P_OUT}/${P_CACHE} US$ por millón (entrada/salida/caché leída):`);
  console.log(`  con caché del prefijo: ${usd(turnos * costoVuelta(true))}  (${usd(VUELTAS * costoVuelta(true))} por pregunta)`);
  console.log(`  sin caché:             ${usd(turnos * costoVuelta(false))}  (${usd(VUELTAS * costoVuelta(false))} por pregunta)`);
  console.log(`  supuesto por vuelta: ${POR_VUELTA.entradaNueva + POR_VUELTA.cacheLeida} de entrada (${POR_VUELTA.cacheLeida} cacheados) y ${POR_VUELTA.salida} de salida; no incluye la escritura de caché (1,25× la entrada, una vez cada 5 min)`);
  process.exit(0);
}

exigirEnv('AKINE_EVAL_PASSWORD');
if (!estado) throw new Error(`falta la siembra (${ESTADO}): correr sembrar-agente.mjs`);
const hoy = new Intl.DateTimeFormat('en-CA', { timeZone: estado.timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
if (hoy !== estado.fecha) console.warn(`AVISO: la siembra es del ${estado.fecha} y hoy es ${hoy}: las preguntas de "hoy" van a fallar. Volver a sembrar.`);
if (estado.avisos?.length) console.warn(`AVISO: la siembra dejó avisos:\n  ${estado.avisos.join('\n  ')}`);

// ---------------------------------------------------------------------------------------------
// HTTP y sesiones
// ---------------------------------------------------------------------------------------------

async function http(metodo, r, { token, cuerpo } = {}) {
  const res = await fetch(BASE + r, {
    method: metodo,
    headers: { accept: 'application/json, application/problem+json', ...(cuerpo ? { 'content-type': 'application/json' } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: cuerpo ? JSON.stringify(cuerpo) : undefined,
  });
  const texto = await res.text();
  if (!res.ok) throw new Error(`${metodo} ${r} → ${res.status}: ${texto.slice(0, 300)}`);
  return texto ? JSON.parse(texto) : undefined;
}

const sesiones = {};
async function sesion(actor) {
  if (sesiones[actor]) return sesiones[actor];
  const cuenta = estado.cuentas[actor];
  if (!cuenta) throw new Error(`la siembra no tiene cuenta para ${actor}`);
  const pre = (await http('POST', '/api/v1/auth/login', { cuerpo: { email: cuenta.email, password: PASSWORD } })).accessToken;
  const contextos = await http('GET', '/api/v1/me/contexts', { token: pre });
  const c = contextos.find((x) => x.organizationId === estado.organizationId && x.consultorioId === cuenta.consultorioId)
    ?? contextos.find((x) => x.organizationId === estado.organizationId);
  const { accessToken } = await http('POST', '/api/v1/auth/context', { token: pre, cuerpo: { organizationId: estado.organizationId, consultorioId: cuenta.consultorioId ?? c.consultorioId } });
  const me = await http('GET', '/api/v1/me', { token: accessToken });
  sesiones[actor] = { token: accessToken, accountId: me.accountId, consultorioId: cuenta.consultorioId ?? c.consultorioId };
  return sesiones[actor];
}

/** Tokens de la última ASISTENTE_CONSULTA de la cuenta, si la auditoría los trae y es posterior a `desde`. */
async function consumo(accountId, desde) {
  try {
    const admin = await sesion('ADMIN');
    const pagina = await http('GET', `/api/v1/organizations/${estado.organizationId}/audit-events?actorAccountId=${accountId}&q=ASISTENTE_CONSULTA&size=5`, { token: admin.token });
    const ev = (pagina.content ?? []).find((x) => x.eventType === 'ASISTENTE_CONSULTA' && Date.parse(x.occurredAt) >= desde - 2000);
    const d = ev?.details;
    if (!d || d.tokensEntrada === undefined) return null;
    return { entrada: Number(d.tokensEntrada), salida: Number(d.tokensSalida ?? 0), cache: Number(d.tokensCacheLeida ?? 0), vueltas: Number(d.vueltas ?? 0), proveedor: d.proveedor };
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------------------------
// Puntaje
// ---------------------------------------------------------------------------------------------

function normalizar(s) {
  let t = String(s ?? '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
  // "18.000", "18 000" y "$18.000,00" → "18000", "18000,00"
  for (let i = 0; i < 3; i++) t = t.replace(/(\d)[.\s ](?=\d{3}(\D|$))/g, '$1');
  return t;
}
const contiene = (texto, entrada) => (Array.isArray(entrada) ? entrada : [entrada]).some((x) => texto.includes(normalizar(x)));
const sustituir = (v) => (typeof v === 'string' ? v.replace(/\{\{(\w+)\}\}/g, (_, k) => {
  const p = estado.personas?.[k];
  if (!p) throw new Error(`la siembra no tiene la persona ${k}`);
  return String(p.id);
}) : v);

function puntuar(p, r) {
  const e = p.esperado;
  const admitidos = Array.isArray(e.resultado) ? e.resultado : [e.resultado];
  const visible = normalizar([r.texto, ...(r.opciones ?? []).map((o) => o.etiqueta)].join(' \n '));
  const todo = normalizar([visible, JSON.stringify(r.filas ?? [])].join(' \n '));
  const fallas = [];
  if (!admitidos.includes(r.resultado)) fallas.push(`resultado ${r.resultado}, se esperaba ${admitidos.join('|')}`);
  for (const x of e.debeContener ?? []) if (!contiene(visible, x)) fallas.push(`falta ${JSON.stringify(x)}`);
  for (const x of e.noDebeContener ?? []) if (contiene(todo, x)) fallas.push(`no debía contener ${JSON.stringify(x)}`);
  if (e.accion && r.resultado === 'PROPUESTA') {
    const tipos = e.accion === 'ATENCION' ? ['ATENCION', 'LLEGADA'] : [e.accion];
    if (!tipos.includes(r.accion?.tipo)) fallas.push(`tarjeta ${r.accion?.tipo}, se esperaba ${e.accion}`);
  }
  const usadas = (r.herramientas ?? []).map((h) => h.herramienta);
  const herramientaOk = e.herramientasEsperadas?.length ? e.herramientasEsperadas.some((h) => usadas.includes(h)) : null;
  const repreguntaInnecesaria = r.resultado === 'ACLARACION' && !e.debePreguntar && !e.admitePregunta;
  return { ok: fallas.length === 0, fallas, usadas, herramientaOk, repreguntaInnecesaria };
}

// ---------------------------------------------------------------------------------------------
// La corrida
// ---------------------------------------------------------------------------------------------

const resultados = [];
for (const p of preguntas) {
  if (p.requiere === 'inyeccion' && estado.sinInyeccion) {
    console.log(`SALTA ${p.id} ${p.pregunta} (la siembra no tiene el nombre con inyección)`);
    continue;
  }
  const s = await sesion(p.actor);
  const cuerpo = {
    pregunta: p.pregunta,
    historial: (p.historial ?? []).map((h) => ({ pregunta: h.pregunta, respuesta: h.respuesta, personaIds: (h.personaIds ?? []).map((x) => Number(sustituir(x))) })),
    ...(p.personaId ? { personaId: Number(sustituir(p.personaId)) } : {}),
    ...(p.rutaActual ? { rutaActual: p.rutaActual } : {}),
  };
  const t0 = Date.now();
  let r;
  try {
    r = await http('POST', `/api/v1/consultorios/${s.consultorioId}/asistente/consultas`, { token: s.token, cuerpo });
  } catch (err) {
    r = { texto: String(err.message), resultado: 'ERROR', filas: [], opciones: [], herramientas: [] };
  }
  const ms = Date.now() - t0;
  const puntaje = puntuar(p, r);
  const uso = r.resultado === 'ERROR' ? null : await consumo(s.accountId, t0);
  resultados.push({ id: p.id, grupo: p.grupo, actor: p.actor, pregunta: p.pregunta, ms, resultado: r.resultado, texto: r.texto, herramientas: r.herramientas, uso, ...puntaje });
  console.log(`${puntaje.ok ? 'OK   ' : 'FALLA'} ${p.id.padEnd(6)} ${String(r.resultado).padEnd(22)} ${String(ms).padStart(6)} ms  ${p.pregunta}${puntaje.ok ? '' : `\n        ${puntaje.fallas.join('; ')}\n        «${String(r.texto).slice(0, 160).replace(/\s+/g, ' ')}»`}`);
}

// ---------------------------------------------------------------------------------------------
// Resumen
// ---------------------------------------------------------------------------------------------

const N = resultados.length;
const pct = (a, b) => (b ? `${((100 * a) / b).toFixed(1)} %` : '—');
const bien = resultados.filter((x) => x.ok).length;
const grupos = {};
resultados.forEach((x) => (grupos[x.grupo] ??= []).push(x));
const consistentes = Object.values(grupos).filter((g) => g.every((x) => x.ok)).length;
const repreguntas = resultados.filter((x) => x.repreguntaInnecesaria).length;
const conHerr = resultados.filter((x) => x.herramientaOk !== null);
const lat = resultados.map((x) => x.ms).sort((a, b) => a - b);
const percentil = (q) => (lat.length ? lat[Math.min(lat.length - 1, Math.floor(lat.length * q))] : 0);
const conUso = resultados.filter((x) => x.uso);
const suma = (k) => conUso.reduce((s, x) => s + x.uso[k], 0);
let costo;
if (conUso.length) {
  const real = (suma('entrada') * P_IN + suma('salida') * P_OUT + suma('cache') * P_CACHE) / 1e6;
  costo = `${usd(real)} medido con los tokens de la auditoría en ${conUso.length}/${N} preguntas (${usd(real / conUso.length)} por pregunta); ` +
    `tokens: entrada ${suma('entrada')}, salida ${suma('salida')}, caché leída ${suma('cache')}; vueltas promedio ${(suma('vueltas') / conUso.length).toFixed(2)}. ` +
    `Precio ${P_IN}/${P_OUT}/${P_CACHE} US$ por millón; la escritura de caché no se audita y no está incluida.`;
} else {
  costo = `ESTIMADO (la auditoría no trajo tokens): ${usd(N * VUELTAS * costoVuelta(true))} con caché, ${usd(N * VUELTAS * costoVuelta(false))} sin caché, ` +
    `suponiendo ${VUELTAS} vueltas por pregunta y precio ${P_IN}/${P_OUT}/${P_CACHE}.`;
}

const resumen = [
  `Set ${basename(SET)} · ${N} preguntas · ${Object.keys(grupos).length} grupos · backend ${BASE} · ${new Date().toISOString()}`,
  `Correctas: ${bien}/${N} (${pct(bien, N)})`,
  `Consistencia por grupo (todas las variantes correctas): ${consistentes}/${Object.keys(grupos).length} (${pct(consistentes, Object.keys(grupos).length)})`,
  `Repreguntas innecesarias: ${repreguntas}/${N} (${pct(repreguntas, N)})`,
  `Herramienta esperada usada: ${conHerr.filter((x) => x.herramientaOk).length}/${conHerr.length} (${pct(conHerr.filter((x) => x.herramientaOk).length, conHerr.length)})`,
  `Errores HTTP: ${resultados.filter((x) => x.resultado === 'ERROR').length}`,
  `Latencia p50 ${percentil(0.5)} ms, p90 ${percentil(0.9)} ms`,
  `Costo: ${costo}`,
  `Grupos inconsistentes: ${Object.entries(grupos).filter(([, g]) => !g.every((x) => x.ok)).map(([k, g]) => `${k} (${g.filter((x) => x.ok).length}/${g.length})`).join(', ') || 'ninguno'}`,
];
console.log(`\n${resumen.join('\n')}`);

const salida = join(dir, '.estado', `resultados-${basename(SET, '.json')}-${new Date().toISOString().replace(/[:.]/g, '-')}.json`);
mkdirSync(dirname(salida), { recursive: true });
writeFileSync(salida, JSON.stringify({ resumen, resultados }, null, 1));
console.log(`\nDetalle: ${salida}`);
