// Mide el recorrido COMPLETO (EsPregunta + modelo + ControlDeAccion + resolucion) contra un backend
// levantado con las acciones prendidas, pasando cada frase de un set por POST /asistente/consultas.
// evaluar-acciones.mjs mide solo el modelo; este mide lo que ve la persona.
//
//   AKINE_EMAIL=... AKINE_PASSWORD=... node evaluar-acciones-en-vivo.mjs [url] [CASOS=acciones.json]
//
// Una frase de accion "se reconoce" si la respuesta es una tarjeta (PROPUESTA) del tipo esperado, o
// corta antes por la persona o por datos (ACLARACION, FALTA_PERSONA, PERSONA_NO_ENCONTRADA, o un
// texto de la accion: "no encontre una clase de hoy"). Los nombres del set no existen en la base de
// prueba, asi que PERSONA_NO_ENCONTRADA es lo normal en pagos y atenciones.
// Una frase que NO es accion pasa si no termina en tarjeta.
// Las propuestas quedan en asistente_accion y vencen solas: correrlo contra una base de prueba.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = dirname(fileURLToPath(import.meta.url));
const base = (process.argv[2] ?? 'http://localhost:8080').replace(/\/+$/, '');
const casos = JSON.parse(readFileSync(join(dir, process.env.CASOS ?? 'acciones.json'), 'utf8'));
const { AKINE_EMAIL: email, AKINE_PASSWORD: password } = process.env;
if (!email || !password) throw new Error('Faltan AKINE_EMAIL y AKINE_PASSWORD');

async function json(ruta, opciones = {}) {
  const r = await fetch(base + ruta, {
    ...opciones,
    headers: { 'content-type': 'application/json', ...(opciones.headers ?? {}) },
    body: opciones.body ? JSON.stringify(opciones.body) : undefined,
  });
  if (!r.ok) throw new Error(`${ruta}: ${r.status} ${await r.text()}`);
  return r.json();
}

const pre = (await json('/api/v1/auth/login', { method: 'POST', body: { email, password } })).accessToken;
const contextos = await json('/api/v1/me/contexts', { headers: { authorization: `Bearer ${pre}` } });
const contexto = contextos.find((c) => c.consultorioId) ?? contextos[0];
const { accessToken } = await json('/api/v1/auth/context', {
  method: 'POST',
  headers: { authorization: `Bearer ${pre}` },
  body: { organizationId: contexto.organizationId, consultorioId: contexto.consultorioId },
});
const sede = contexto.consultorioId;

// Quien no tiene sesion:register y cuenta una atencion recibe la llegada: las dos valen.
const TIPOS = { ATENCION: ['ATENCION', 'LLEGADA'] };
const CORTES = ['ACLARACION', 'FALTA_PERSONA', 'PERSONA_NO_ENCONTRADA'];

let bien = 0;
const ms = [];
for (const caso of casos) {
  const t0 = performance.now();
  const r = await json(`/api/v1/consultorios/${sede}/asistente/consultas`, {
    method: 'POST',
    headers: { authorization: `Bearer ${accessToken}` },
    body: { pregunta: caso.q, historial: [] },
  });
  ms.push(performance.now() - t0);
  const tipo = r.accion?.tipo ?? null;
  const esperada = caso.e.accion;
  let ok;
  if (esperada === 'NINGUNA') {
    ok = r.resultado !== 'PROPUESTA';
  } else if (r.resultado === 'PROPUESTA') {
    ok = (TIPOS[esperada] ?? [esperada]).includes(tipo);
  } else {
    // Sin tarjeta: vale si corto por la persona o respondio el texto de la accion (no la ayuda).
    ok = CORTES.includes(r.resultado) || (r.resultado === 'RESPONDIDA' && /clase|turno|dato/i.test(r.texto));
  }
  if (ok) bien++;
  console.log(`${ok ? 'OK   ' : 'FALLA'} ${caso.id.padEnd(5)} ${r.resultado.padEnd(22)} ${(tipo ?? '').padEnd(16)} ${caso.q}${ok ? '' : '\n        ' + r.texto.slice(0, 120)}`);
}
ms.sort((a, b) => a - b);
console.log(`\nReconocidas como corresponde: ${bien}/${casos.length}`);
console.log(`Latencia p50 ${Math.round(ms[Math.floor(ms.length / 2)])} ms, p95 ${Math.round(ms[Math.floor(ms.length * 0.95)])} ms`);
