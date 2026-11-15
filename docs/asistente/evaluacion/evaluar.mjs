// Mide el interpretador del asistente de datos contra Ollama.
// Uso: [CASOS=archivo.json] node evaluar.mjs [modelo] [url]   (por defecto preguntas.json, qwen3:4b-instruct, http://localhost:11434)
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = dirname(fileURLToPath(import.meta.url));
const modelo = process.argv[2] ?? 'qwen3:4b-instruct';
const url = (process.argv[3] ?? 'http://localhost:11434').replace(/\/+$/, '') + '/api/chat';
const sistema = readFileSync(join(dir, 'prompt-interpretador.txt'), 'utf8');
const casos = JSON.parse(readFileSync(join(dir, process.env.CASOS ?? 'preguntas.json'), 'utf8'));

const HONORIFICOS = new Set(['sr', 'sra', 'senor', 'senora', 'el', 'la', 'don', 'dona']);
const norm = (s) => (s ?? '').toString().normalize('NFD').replace(/[̀-ͯ]/g, '')
  .toLowerCase().replace(/[^a-z0-9 ]/g, ' ').split(/\s+/).filter((t) => t && !HONORIFICOS.has(t)).join(' ');
const vacio = (v) => v === null || v === undefined || v === '' || v === 'null';

async function interpretar(caso) {
  const mensajes = [{ role: 'system', content: sistema }];
  for (const [pregunta, respuesta] of caso.h ?? []) {
    mensajes.push({ role: 'user', content: pregunta }, { role: 'assistant', content: JSON.stringify(respuesta) });
  }
  mensajes.push({ role: 'user', content: caso.q });
  const t0 = performance.now();
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: modelo, messages: mensajes, stream: false, format: 'json', keep_alive: '2h',
      options: { temperature: 0, num_ctx: 4096, num_predict: 120, ...(process.env.NUM_GPU ? { num_gpu: Number(process.env.NUM_GPU) } : {}) } }),
  });
  const cuerpo = await r.json();
  const ms = performance.now() - t0;
  let salida = null;
  try { salida = JSON.parse(cuerpo.message?.content ?? ''); } catch { /* JSON inválido */ }
  return { salida, ms, crudo: cuerpo.message?.content };
}

function comparar(e, s) {
  if (!s) return ['json-invalido'];
  const fallas = [];
  if (s.intencion !== e.intencion) fallas.push(`intencion=${s.intencion}`);
  for (const campo of ['persona', 'clase']) {
    if (campo in e && norm(e[campo]) !== norm(s[campo])) fallas.push(`${campo}=${s[campo]}`);
  }
  for (const campo of ['periodo', 'reporte']) {
    if (!(campo in e)) continue;
    const ok = vacio(e[campo]) ? vacio(s[campo]) : s[campo] === e[campo];
    if (!ok) fallas.push(`${campo}=${s[campo]}`);
  }
  return fallas;
}

// Calentamiento: la primera llamada paga la carga del prompt.
await interpretar({ q: 'hola' });

const filas = [];
for (const caso of casos) {
  const { salida, ms, crudo } = await interpretar(caso);
  const fallas = comparar(caso.e, salida);
  filas.push({ id: caso.id, ok: fallas.length === 0, intencionOk: salida?.intencion === caso.e.intencion, ms, fallas, crudo });
  console.log(`${fallas.length ? 'FALLA' : 'ok   '} ${caso.id} ${Math.round(ms)} ms  ${caso.q}${fallas.length ? '  -> ' + fallas.join(', ') : ''}`);
}

const ms = filas.map((f) => f.ms).sort((a, b) => a - b);
const p = (q) => Math.round(ms[Math.min(ms.length - 1, Math.floor(q * ms.length))]);
const fuera = filas.filter((f) => f.id.startsWith('F'));
console.log(`\nModelo: ${modelo}`);
console.log(`Completo (intención + campos): ${filas.filter((f) => f.ok).length}/${filas.length}`);
console.log(`Sólo intención:                ${filas.filter((f) => f.intencionOk).length}/${filas.length}`);
console.log(`Fuera de alcance:              ${fuera.filter((f) => f.ok).length}/${fuera.length}`);
console.log(`Seguimientos (H):              ${filas.filter((f) => f.id.startsWith('H') && f.ok).length}/${filas.filter((f) => f.id.startsWith('H')).length}`);
console.log(`Latencia p50 / p95 / máx:      ${p(0.5)} / ${p(0.95)} / ${Math.round(ms.at(-1))} ms`);
