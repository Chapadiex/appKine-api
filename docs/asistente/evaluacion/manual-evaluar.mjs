#!/usr/bin/env node
/**
 * Mide la intención COMO_SE_HACE del asistente contra el backend levantado (con Ollama real).
 * Cada pregunta de manual-preguntas.json tiene su pasaje esperado ("ficha#Título de la tarea") o
 * NINGUNO. Cuenta aciertos, abstenciones correctas y latencia. No guarda las respuestas.
 *
 * Cada pregunta corre con la cuenta que usa esa pantalla: por defecto la administradora; las marcadas
 * "cuenta": "profesional" (atencion clinica, que pide sesion:register) con la profesional.
 *
 * Uso:
 *   AKINE_API=http://localhost:8096 AKINE_EMAIL=... AKINE_PASSWORD=... \
 *   AKINE_EMAIL_PROFESIONAL=... AKINE_PASSWORD_PROFESIONAL=... AKINE_ORG_PROFESIONAL=<id> node manual-evaluar.mjs
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

async function sesion(email, password, organizationId) {
  const login = await json('/api/v1/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) });
  const contextos = await json('/api/v1/me/contexts', {
    headers: { Authorization: `Bearer ${login.accessToken}` },
  });
  // Una cuenta puede trabajar en varios centros: se elige el indicado, o el primero.
  const contexto =
    contextos.find((c) => String(c.organizationId) === String(organizationId)) ?? contextos[0];
  const elegido = await json('/api/v1/auth/context', {
    method: 'POST',
    headers: { Authorization: `Bearer ${login.accessToken}` },
    body: JSON.stringify({ organizationId: contexto.organizationId, consultorioId: contexto.consultorioId }),
  });
  return { token: elegido.accessToken, consultorioId: contexto.consultorioId };
}

const cuentas = {
  administradora: await sesion(process.env.AKINE_EMAIL, process.env.AKINE_PASSWORD),
  profesional: process.env.AKINE_EMAIL_PROFESIONAL
    ? await sesion(
        process.env.AKINE_EMAIL_PROFESIONAL,
        process.env.AKINE_PASSWORD_PROFESIONAL,
        process.env.AKINE_ORG_PROFESIONAL,
      )
    : null,
};

let aciertos = 0;
let total = 0;
const tiempos = [];
for (const { pregunta, esperado, cuenta } of preguntas) {
  const quien = cuentas[cuenta ?? 'administradora'];
  if (!quien) {
    console.log(`SALTEADA (falta la cuenta ${cuenta}) ${pregunta}`);
    continue;
  }
  const inicio = performance.now();
  const r = await json(`/api/v1/consultorios/${quien.consultorioId}/asistente/consultas`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${quien.token}` },
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
