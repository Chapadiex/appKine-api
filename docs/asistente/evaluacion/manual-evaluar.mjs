#!/usr/bin/env node
/**
 * Mide la intención COMO_SE_HACE del asistente contra el backend levantado (con Ollama real).
 * Cada pregunta de manual-preguntas.json tiene su pasaje esperado ("ficha#Título de la tarea") o
 * NINGUNO. Cuenta aciertos, abstenciones correctas y latencia. No guarda las respuestas.
 *
 * Uso:
 *   AKINE_API=http://localhost:8096 AKINE_EMAIL=... AKINE_PASSWORD=... node manual-evaluar.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const API = process.env.AKINE_API ?? 'http://localhost:8080';
const preguntas = JSON.parse(
  readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'manual-preguntas.json'), 'utf8'),
);

async function json(ruta, opciones = {}) {
  const r = await fetch(API + ruta, {
    ...opciones,
    headers: { 'Content-Type': 'application/json', ...(opciones.headers ?? {}) },
  });
  const texto = await r.text();
  if (!r.ok) throw new Error(`${ruta}: ${r.status} ${texto.slice(0, 200)}`);
  return texto ? JSON.parse(texto) : null;
}

const login = await json('/api/v1/auth/login', {
  method: 'POST',
  body: JSON.stringify({ email: process.env.AKINE_EMAIL, password: process.env.AKINE_PASSWORD }),
});
const [contexto] = await json('/api/v1/me/contexts', {
  headers: { Authorization: `Bearer ${login.accessToken}` },
});
const sesion = await json('/api/v1/auth/context', {
  method: 'POST',
  headers: { Authorization: `Bearer ${login.accessToken}` },
  body: JSON.stringify({ organizationId: contexto.organizationId, consultorioId: contexto.consultorioId }),
});

let aciertos = 0;
let total = 0;
const tiempos = [];
for (const { pregunta, esperado } of preguntas) {
  const inicio = performance.now();
  const r = await json(`/api/v1/consultorios/${contexto.consultorioId}/asistente/consultas`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${sesion.accessToken}` },
    body: JSON.stringify({ pregunta }),
  });
  tiempos.push(performance.now() - inicio);
  const fila = r.filas?.[0];
  const obtenido =
    r.interpretacion?.intencion === 'COMO_SE_HACE' && fila?.manualFicha
      ? `${fila.manualFicha}#${String(fila.manualTitulo).split(' → ').pop()}`
      : 'NINGUNO';
  const ok = obtenido === esperado;
  aciertos += ok ? 1 : 0;
  total++;
  console.log(`${ok ? 'OK ' : 'MAL'} ${pregunta}\n    esperado ${esperado}\n    obtenido ${obtenido} (${r.interpretacion?.intencion})`);
}
tiempos.sort((a, b) => a - b);
console.log(
  `\n${aciertos}/${total} correctas · mediana ${Math.round(tiempos[Math.floor(tiempos.length / 2)])} ms · ` +
    `p90 ${Math.round(tiempos[Math.floor(tiempos.length * 0.9)])} ms`,
);
