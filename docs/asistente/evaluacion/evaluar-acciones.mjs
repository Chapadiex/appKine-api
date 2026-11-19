// Mide el interpretador de ACCIONES contra Ollama con el mismo prompt y el mismo JSON schema que usa el
// backend (docs/diseno/AKINE-asistente-acciones.md, §8).
//
//   node evaluar-acciones.mjs [modelo] [url]        CASOS=acciones.json por defecto
//
// Mide SOLO el modelo: lo que devuelve antes de ControlDeAccion. El monto en letras (PA8) o un nombre
// inventado se ven aca tal cual salen; el backend los descarta despues.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = dirname(fileURLToPath(import.meta.url));
const modelo = process.argv[2] ?? 'qwen3:4b-instruct';
const url = (process.argv[3] ?? 'http://localhost:11434').replace(/\/+$/, '') + '/api/chat';
const raiz = join(dir, '../../../src/main');
const sistema = readFileSync(join(raiz, 'resources/asistente/prompt-acciones.txt'), 'utf8');
const casos = JSON.parse(readFileSync(join(dir, process.env.CASOS ?? 'acciones.json'), 'utf8'));

// El schema se lee del fuente Java: lo que se mide es lo que corre.
const fuente = readFileSync(join(raiz, 'java/com/akine/assistant/application/InterpretadorDeAcciones.java'), 'utf8');
const bloque = /ESQUEMA = """\s*([\s\S]*?)""";/.exec(fuente);
if (!bloque) throw new Error('No encontre ESQUEMA en InterpretadorDeAcciones.java');
const esquema = JSON.parse(bloque[1].replace(/\\\r?\n\s*/g, ''));

const norm = (s) => (s ?? '').toString().normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
  .replace(/\s+/g, ' ').trim();
const digitos = (s) => (s ?? '').toString().replace(/\D/g, '');
const vacio = (v) => v === null || v === undefined || v === '' || v === 'null' || (Array.isArray(v) && v.length === 0);
const alguna = (esperado, obtenido) => esperado.split('|').some((e) => norm(obtenido).includes(norm(e)));

async function interpretar(caso) {
  const t0 = performance.now();
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      model: modelo, stream: false, format: esquema, keep_alive: '2h',
      messages: [{ role: 'system', content: sistema }, { role: 'user', content: caso.q }],
      options: { temperature: 0, num_ctx: 8192, num_predict: 300, ...(process.env.NUM_GPU ? { num_gpu: Number(process.env.NUM_GPU) } : {}) },
    }),
  });
  const cuerpo = await r.json();
  const ms = performance.now() - t0;
  let salida = {};
  try { salida = JSON.parse(cuerpo.message?.content ?? '{}'); } catch { /* JSON ilegible: todo falla */ }
  return { salida, ms };
}

function comparar(e, s) {
  const fallas = [];
  if ((s.accion ?? 'NINGUNA') !== e.accion) fallas.push(`accion=${s.accion}`);
  if (e.accion === 'NINGUNA') return fallas;
  for (const campo of ['persona', 'clase', 'nombre', 'apellido', 'email']) {
    if (!vacio(e[campo]) && !alguna(e[campo], s[campo])) fallas.push(`${campo}=${s[campo]}`);
  }
  if (!vacio(e.modo) && s.modo !== e.modo) fallas.push(`modo=${s.modo}`);
  if (e.monto && !e.monto.split('|').includes(digitos(s.monto))) fallas.push(`monto=${s.monto}`);
  for (const campo of ['telefono', 'documento']) {
    if (e[campo] && digitos(s[campo]) !== e[campo]) fallas.push(`${campo}=${s[campo]}`);
  }
  for (const campo of ['nombres', 'practicas']) {
    if (e[campo] === undefined) continue;
    const obtenidos = (s[campo] ?? []).map(norm);
    const faltan = e[campo].filter((x) => !obtenidos.some((o) => alguna(x, o)));
    if (faltan.length > 0 || obtenidos.length !== e[campo].length) fallas.push(`${campo}=${JSON.stringify(s[campo] ?? [])}`);
  }
  return fallas;
}

const filas = [];
for (const caso of casos) {
  const { salida, ms } = await interpretar(caso);
  const fallas = comparar(caso.e, salida);
  filas.push({ id: caso.id, ms, fallas, accionOk: (salida.accion ?? 'NINGUNA') === caso.e.accion });
  console.log(`${fallas.length === 0 ? 'OK  ' : 'FALLA'} ${caso.id.padEnd(5)} ${Math.round(ms)} ms  ${caso.q}${fallas.length ? '\n        ' + fallas.join(', ') : ''}`);
}
const ms = filas.map((f) => f.ms).sort((a, b) => a - b);
const p = (q) => Math.round(ms[Math.min(ms.length - 1, Math.floor(q * ms.length))]);
const acciones = filas.filter((f) => !f.id.startsWith('NI'));
const ninguna = filas.filter((f) => f.id.startsWith('NI'));
console.log(`\nAccion correcta: ${filas.filter((f) => f.accionOk).length}/${filas.length}`);
console.log(`Completas (accion y campos): ${acciones.filter((f) => f.fallas.length === 0).length}/${acciones.length}`);
console.log(`No-acciones que quedaron en NINGUNA: ${ninguna.filter((f) => f.accionOk).length}/${ninguna.length}`);
console.log(`Latencia p50 ${p(0.5)} ms, p95 ${p(0.95)} ms (${modelo})`);
