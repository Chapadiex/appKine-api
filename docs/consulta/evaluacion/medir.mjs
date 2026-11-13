// Mide la consulta a la biblioteca contra un backend vivo con el set de consultas.json.
// Uso: node medir.mjs --base http://localhost:8098 --salida resultados.md
//        [--consultas consultas.json] [--cuentas <ruta a rag-cuentas.md>] [--solo C01,A02]
// Node 24, sin dependencias. Entiende los eventos de v1 (espera, fuentes, texto, fin, error) y los de
// v2 (aclaracion, etapa). La contraseña se lee del archivo de cuentas y nunca se imprime.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const aqui = dirname(fileURLToPath(import.meta.url));
const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]]] : acc), []),
);
const base = (args.base ?? 'http://localhost:8098').replace(/\/$/, '') + '/api/v1';
const salida = args.salida ?? resolve(aqui, 'resultados.md');
const archivoConsultas = args.consultas ?? resolve(aqui, 'consultas.json');
const archivoCuentas = args.cuentas ?? resolve(aqui, '../../../../.claude/rag-cuentas.md');
const solo = args.solo ? new Set(args.solo.split(',')) : null;

const SIN_MATERIAL = 'No encontré material en la biblioteca para responder eso.';

function credenciales() {
  const fila = readFileSync(archivoCuentas, 'utf8').split('\n').find((l) => l.includes('admin.rag@'));
  if (!fila) throw new Error(`No hay fila admin.rag@ en ${archivoCuentas}`);
  const [, , email, password] = fila.split('|').map((s) => s.trim());
  return { email, password };
}

async function json(path, opts = {}) {
  const r = await fetch(base + path, { ...opts, headers: { 'content-type': 'application/json', ...(opts.headers ?? {}) } });
  if (!r.ok) throw new Error(`${path} -> HTTP ${r.status}`);
  return r.json();
}

async function entrar() {
  const login = await json('/auth/login', { method: 'POST', body: JSON.stringify(credenciales()) });
  const pre = { authorization: `Bearer ${login.accessToken}` };
  const contextos = await json('/me/contexts', { headers: pre });
  const c = Array.isArray(contextos) ? contextos[0] : (contextos.contexts ?? contextos.items)[0];
  const ctx = await json('/auth/context', {
    method: 'POST', headers: pre,
    body: JSON.stringify({ organizationId: c.organizationId, consultorioId: c.consultorioId }),
  });
  return { orgId: c.organizationId, auth: { authorization: `Bearer ${ctx.accessToken}` } };
}

// Lee el flujo SSE: bloques separados por línea en blanco, "event:" y "data:" sin espacio obligatorio,
// líneas que empiezan con ":" son comentarios (keep-alive).
async function consultar(sesion, consulta, historial) {
  const t0 = performance.now();
  const ms = () => Math.round(performance.now() - t0);
  const r = await fetch(`${base}/organizations/${sesion.orgId}/biblioteca/consultas`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'text/event-stream', ...sesion.auth },
    body: JSON.stringify({ pregunta: consulta, historial }),
  });
  const out = { http: r.status, primerEventoMs: null, primeraPalabraMs: null, totalMs: null, fuentes: [], texto: '',
    aclaracion: null, sinMaterial: null, camino: null, error: null, etapas: [] };
  if (!r.ok) { out.error = `HTTP ${r.status}`; out.totalMs = ms(); return out; }
  const dec = new TextDecoder();
  let buf = '';
  const procesar = (bloque) => {
    let ev = null; const datos = [];
    for (const l of bloque.split(/\r?\n/)) {
      if (l.startsWith(':')) continue;
      if (l.startsWith('event:')) ev = l.slice(6).trim();
      else if (l.startsWith('data:')) datos.push(l.slice(5).replace(/^ /, ''));
    }
    if (!ev) return;
    if (out.primerEventoMs === null) out.primerEventoMs = ms();
    let d = {};
    try { d = datos.length ? JSON.parse(datos.join('\n')) : {}; } catch { d = { crudo: datos.join('\n') }; }
    switch (ev) {
      case 'fuentes': out.fuentes = d.fuentes ?? []; break;
      case 'texto':
        if (out.primeraPalabraMs === null && (d.texto ?? '').trim()) out.primeraPalabraMs = ms();
        out.texto += d.texto ?? ''; break;
      case 'aclaracion':
        out.aclaracion = { texto: d.texto, opciones: d.opciones ?? [] };
        if (out.primeraPalabraMs === null) out.primeraPalabraMs = ms(); break;
      case 'etapa': out.etapas.push(d.etapa); break;
      case 'fin': out.sinMaterial = d.sinMaterial ?? null; out.camino = d.camino ?? null; break;
      case 'error': out.error = d.type ?? 'error'; break;
      default: break; // espera y eventos futuros
    }
  };
  for await (const chunk of r.body) {
    buf += dec.decode(chunk, { stream: true });
    let i;
    while ((i = buf.search(/\r?\n\r?\n/)) >= 0) {
      const m = buf.slice(i).match(/^\r?\n\r?\n/)[0];
      procesar(buf.slice(0, i)); buf = buf.slice(i + m.length);
    }
  }
  if (buf.trim()) procesar(buf);
  out.totalMs = ms();
  return out;
}

function tipoDeSalida(r) {
  if (r.error) return 'error';
  if (r.aclaracion) return 'aclaracion';
  if (r.sinMaterial || r.texto.trim() === SIN_MATERIAL) return 'sin_material';
  return 'texto';
}

// Recall@5: alguna página esperada (±1) cae dentro de alguna de las 5 primeras fuentes del libro esperado.
function acierto(caso, r) {
  if (!caso.paginas.length) return null;
  return r.fuentes.slice(0, 5).some((f) => (f.titulo ?? '').includes(caso.libro)
    && caso.paginas.some((p) => p >= f.paginaDesde - 1 && p <= f.paginaHasta + 1));
}

function percentil(valores, p) {
  const v = valores.filter((x) => x !== null).sort((a, b) => a - b);
  if (!v.length) return null;
  return v[Math.min(v.length - 1, Math.ceil((p / 100) * v.length) - 1)];
}

const pct = (a, n) => (n ? `${a}/${n} (${Math.round((100 * a) / n)} %)` : '—');
const seg = (x) => (x === null ? '—' : (x / 1000).toFixed(1));
const celda = (s) => String(s ?? '').replace(/\|/g, '\\|').replace(/\n/g, ' ');

const casos = JSON.parse(readFileSync(archivoConsultas, 'utf8')).filter((c) => !solo || solo.has(c.id));
const sesion = await entrar();
const filas = [];
for (const caso of casos) {
  const r = await consultar(sesion, caso.consulta, caso.historial ?? []);
  const tipo = tipoDeSalida(r);
  // Sólo cuenta el recall de una respuesta con texto: las fuentes de un "sin material" no son acierto
  // (el "sin material" falso se cuenta aparte).
  const fila = { caso, r, tipo, recall: caso.esperado === 'responder' ? (tipo === 'texto' && acierto(caso, r)) : null };
  filas.push(fila);
  console.log(`${caso.id} ${caso.esperado.padEnd(12)} -> ${tipo.padEnd(12)} primera ${seg(r.primeraPalabraMs)} s, total ${seg(r.totalMs)} s`
    + (fila.recall === null ? '' : `, recall ${fila.recall ? 'sí' : 'no'}`));
}

const responder = filas.filter((f) => f.caso.esperado === 'responder');
const recallOk = responder.filter((f) => f.recall).length;
const repreg = filas.filter((f) => f.caso.esperado === 'repreguntar');
const repregOk = repreg.filter((f) => f.tipo === 'aclaracion').length;
const claras = filas.filter((f) => f.caso.esperado !== 'repreguntar');
const clarasNoRepreg = claras.filter((f) => f.tipo !== 'aclaracion').length;
const sinMat = filas.filter((f) => f.caso.esperado === 'sin_material');
const sinMatOk = sinMat.filter((f) => f.tipo === 'sin_material').length;
const falsosSinMat = responder.filter((f) => f.tipo === 'sin_material').length;
const aclaraciones = filas.filter((f) => f.tipo === 'aclaracion').map((f) => f.r.primeraPalabraMs);
const textos = filas.filter((f) => f.tipo === 'texto');

const md = [];
md.push(`# Resultados de la consulta a la biblioteca`, '');
md.push(`- Fecha: ${new Date().toISOString()}`);
md.push(`- Base: \`${base}\` · casos: ${filas.length}`);
md.push(`- "Primera palabra" es el primer evento \`texto\` no vacío, o la \`aclaracion\` si la hubo.`, '');
md.push('## Agregados', '');
md.push('| Métrica | Valor |', '|---|---|');
md.push(`| Recall@5 de páginas (casos "responder") | ${pct(recallOk, responder.length)} |`);
md.push(`| Acierto de "repreguntar" (ambiguas que repreguntan) | ${pct(repregOk, repreg.length)} |`);
md.push(`| Claras que no repreguntan | ${pct(clarasNoRepreg, claras.length)} |`);
md.push(`| Acierto de "sin material" (temas ausentes) | ${pct(sinMatOk, sinMat.length)} |`);
md.push(`| "Sin material" falso en casos "responder" | ${pct(falsosSinMat, responder.length)} |`);
md.push(`| Primer evento p50 / p95 (s) | ${seg(percentil(filas.map((f) => f.r.primerEventoMs), 50))} / ${seg(percentil(filas.map((f) => f.r.primerEventoMs), 95))} |`);
md.push(`| Primera palabra p50 / p95 (s), respuestas con texto | ${seg(percentil(textos.map((f) => f.r.primeraPalabraMs), 50))} / ${seg(percentil(textos.map((f) => f.r.primeraPalabraMs), 95))} |`);
md.push(`| Repregunta p50 / p95 (s) | ${seg(percentil(aclaraciones, 50))} / ${seg(percentil(aclaraciones, 95))} |`);
md.push(`| Total p50 / p95 (s), todos los casos | ${seg(percentil(filas.map((f) => f.r.totalMs), 50))} / ${seg(percentil(filas.map((f) => f.r.totalMs), 95))} |`);
md.push(`| Errores | ${filas.filter((f) => f.tipo === 'error').length} |`, '');
md.push('## Por caso', '');
md.push('| Id | Consulta | Esperado | Salida | Recall | Fuentes (libro p.desde–hasta) | 1.er evento (s) | 1.ª palabra (s) | Total (s) |');
md.push('|---|---|---|---|---|---|---|---|---|');
for (const { caso, r, tipo, recall } of filas) {
  const fuentes = r.fuentes.map((f) => `${f.titulo} ${f.paginaDesde}–${f.paginaHasta}`).join(', ');
  const ok = tipo === (caso.esperado === 'responder' ? 'texto' : caso.esperado === 'repreguntar' ? 'aclaracion' : 'sin_material');
  md.push(`| ${caso.id} | ${celda(caso.consulta)}${caso.historial?.length ? ' *(con historial)*' : ''} | ${caso.esperado} | ${tipo}${ok ? '' : ' ✗'} | ${recall === null ? '—' : recall ? 'sí' : 'no'} | ${celda(fuentes) || '—'} | ${seg(r.primerEventoMs)} | ${seg(r.primeraPalabraMs)} | ${seg(r.totalMs)} |`);
}
md.push('');
writeFileSync(salida, md.join('\n'));
console.log(`\nRecall@5 ${pct(recallOk, responder.length)} · repreguntar ${pct(repregOk, repreg.length)} · sin material ${pct(sinMatOk, sinMat.length)}`);
console.log(`Escrito ${salida}`);
