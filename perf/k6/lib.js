// Utilidades compartidas por el sembrado y la carga (AKINE G-10). JavaScript de k6, no de Node:
// no hay `fs`, `crypto` ni `fetch`; solo los modulos `k6/*`.

import http from 'k6/http';
import { sleep } from 'k6';

/** Backend. Dentro de la red docker `g10-net` el contenedor se llama `g10-api`. */
export const API = __ENV.AKINE_API || 'http://g10-api:8080';

/** API HTTP de Mailpit, para leer los enlaces de activacion. */
export const MAILPIT = __ENV.AKINE_MAILPIT || 'http://g10-mailpit:8025';

/**
 * Contrasena de TODAS las cuentas sinteticas del stack de carga. Es un valor de prueba de una base
 * efimera que se crea y se borra en cada corrida: no existe fuera de ella.
 */
export const PASSWORD = __ENV.AKINE_CARGA_PASSWORD || 'carga-g10-contrasena-sintetica-2026';

/**
 * Casilla del admin de plataforma. Tiene que coincidir con `AKINE_BOOTSTRAP_ADMIN_EMAIL` del
 * backend (DP-14): en una base nueva el catalogo global esta vacio y solo el admin de plataforma
 * puede dar de alta el servicio del que cuelga la oferta.
 */
export const EMAIL_PLATAFORMA = __ENV.AKINE_CARGA_PLATAFORMA_EMAIL || 'plataforma.carga@ejemplo.test';

export const JSON_HEADERS = {
  'content-type': 'application/json',
  accept: 'application/json, application/problem+json',
};

export function conToken(token, extra = {}) {
  return { ...JSON_HEADERS, authorization: `Bearer ${token}`, ...extra };
}

export function nuevaClave() {
  return crypto.randomUUID();
}

/** Repite el pedido mientras responda 429, esperando lo que diga `Retry-After`. */
export function conReintentoPor429(hacer, maxIntentos = 20) {
  let r = hacer();
  for (let i = 0; i < maxIntentos && r.status === 429; i++) {
    const espera = parseInt(r.headers['Retry-After'] || '10', 10);
    sleep(Math.min(Math.max(espera, 1), 65));
    r = hacer();
  }
  return r;
}

/** Falla con la ruta y el cuerpo si el pedido no salio bien. Lo usa el sembrado, no la carga. */
export function exigir(r, que, esperados = [200, 201, 202, 204]) {
  if (!esperados.includes(r.status)) {
    throw new Error(`${que}: ${r.status} ${String(r.body).slice(0, 500)}`);
  }
  return r.body ? safeJson(r) : undefined;
}

function safeJson(r) {
  try {
    return r.json();
  } catch (_e) {
    return undefined;
  }
}

/**
 * Login completo: credenciales → token `pre_context` → contextos → token de contexto. Devuelve el
 * access token con contexto elegido (el de `organizationId` si se pide, si no el primero).
 *
 * <p>Pasa por el rate limit de 30/min/IP de `/auth/login`: se llama pocas veces y espaciado.
 */
export function ingresar(email, organizationId, tags = {}) {
  const login = conReintentoPor429(() =>
    http.post(`${API}/api/v1/auth/login`, JSON.stringify({ email, password: PASSWORD }), {
      headers: JSON_HEADERS,
      tags: { endpoint: 'login', ...tags },
    }),
  );
  const pre = exigir(login, `login de ${email}`, [200]).accessToken;
  const contextos = exigir(
    http.get(`${API}/api/v1/me/contexts`, {
      headers: conToken(pre),
      tags: { endpoint: 'me_contexts', ...tags },
    }),
    'contextos',
    [200],
  );
  const contexto =
    organizationId === undefined
      ? contextos[0]
      : contextos.find((c) => c.organizationId === organizationId);
  if (!contexto) {
    throw new Error(`${email} no tiene contexto en ${organizationId}`);
  }
  const elegido = http.post(
    `${API}/api/v1/auth/context`,
    JSON.stringify({
      organizationId: contexto.organizationId,
      consultorioId: contexto.consultorioId,
    }),
    { headers: conToken(pre), tags: { endpoint: 'auth_context', ...tags } },
  );
  return exigir(elegido, 'seleccion de contexto', [200]).accessToken;
}

/**
 * Desfasaje de la zona de la sede respecto de UTC, en horas. k6 no trae `Intl`, asi que no puede
 * resolver una zona IANA: se usa una tabla chica y se puede forzar con `AKINE_CARGA_TZ_OFFSET`.
 * Argentina no tiene horario de verano desde 2009, asi que -3 es exacto todo el anio.
 */
export function desfasajeDe(timezone) {
  if (__ENV.AKINE_CARGA_TZ_OFFSET !== undefined) {
    return parseFloat(__ENV.AKINE_CARGA_TZ_OFFSET);
  }
  if (String(timezone).startsWith('America/Argentina') || timezone === 'America/Buenos_Aires') {
    return -3;
  }
  if (timezone === 'UTC' || timezone === 'Etc/UTC') {
    return 0;
  }
  throw new Error(`zona ${timezone} desconocida: defini AKINE_CARGA_TZ_OFFSET`);
}

/** Fecha local `YYYY-MM-DD` de la sede, `dias` despues de hoy. */
export function fechaLocal(timezone, dias = 0) {
  const ahora = new Date(Date.now() + desfasajeDe(timezone) * 3600 * 1000);
  return sumarDias(ahora.toISOString().slice(0, 10), dias);
}

export function sumarDias(fecha, dias) {
  const d = new Date(`${fecha}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + dias);
  return d.toISOString().slice(0, 10);
}

export function alAzar(lista) {
  return lista[Math.floor(Math.random() * lista.length)];
}

export function entre(min, max) {
  return min + Math.random() * (max - min);
}
