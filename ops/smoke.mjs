#!/usr/bin/env node
// Smoke post-deploy de AKINE (G-13). Node 18+ (usa fetch nativo), sin dependencias.
//
//   AKINE_SMOKE_API_URL=https://api.akine.example \
//   AKINE_SMOKE_WEB_URL=https://app.akine.example \
//   AKINE_SMOKE_CONTRACT=0.80.0 \
//   AKINE_SMOKE_EMAIL=... AKINE_SMOKE_PASSWORD=... \
//     node ops/smoke.mjs
//
// Que verifica, en este orden, y por que cada cosa:
//   1. liveness, readiness y health general del backend      el proceso arranco y ve la base
//   2. /api/v1/version y su contrato                          corre la version que se desplego
//   3. X-Request-Id de ida y vuelta                           la correlacion de G-4 esta viva
//   4. login de la cuenta de humo, contexto y una lectura     autenticacion, base y permisos
//   5. el frontend sirve index.html, la ruta profunda y /healthz
//   6. el frontend proxyea /api al MISMO backend (mismo contrato)
//
// SIN PHI: la unica lectura autenticada es /api/v1/me/permissions, que devuelve codigos de
// permiso. La cuenta de humo no tiene que ser paciente ni ver pacientes (docs/operaciones).
// Nunca se imprime la contrasena ni el token.
//
// Variables opcionales:
//   AKINE_SMOKE_WEB_URL     sin ella se saltean 5 y 6
//   AKINE_SMOKE_CONTRACT    sin ella se informa el contrato pero no se compara
//   AKINE_SMOKE_EMAIL/PASSWORD  sin ellas se saltea 4 (y el smoke no puede salir verde: ver abajo)
//   AKINE_SMOKE_ACTUATOR_URL    si el actuator esta en otro puerto (MANAGEMENT_SERVER_PORT)
//   AKINE_SMOKE_ALLOW_SKIP=1    acepta pasos salteados como exito (solo para entornos sin cuenta)
//   AKINE_SMOKE_TIMEOUT_MS  (10000)
//
// Sale con 0 solo si todo paso. Imprime una linea por chequeo.

import { randomUUID } from 'node:crypto';

const env = process.env;
const api = (env.AKINE_SMOKE_API_URL ?? '').replace(/\/$/, '');
const web = (env.AKINE_SMOKE_WEB_URL ?? '').replace(/\/$/, '');
const actuator = (env.AKINE_SMOKE_ACTUATOR_URL ?? api).replace(/\/$/, '');
const timeoutMs = Number(env.AKINE_SMOKE_TIMEOUT_MS ?? 10000);

if (!api) {
	console.error('falta AKINE_SMOKE_API_URL');
	process.exit(2);
}

const resultados = [];

async function pedir(url, opciones = {}) {
	const t0 = Date.now();
	const res = await fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(timeoutMs), ...opciones });
	const texto = await res.text();
	let json;
	try { json = JSON.parse(texto); } catch { json = undefined; }
	return { res, texto, json, ms: Date.now() - t0 };
}

async function chequeo(nombre, fn) {
	const t0 = Date.now();
	try {
		const detalle = await fn();
		resultados.push({ nombre, estado: 'OK' });
		console.log(`OK    ${nombre} (${Date.now() - t0} ms)${detalle ? ` — ${detalle}` : ''}`);
	} catch (e) {
		resultados.push({ nombre, estado: 'FALLA' });
		console.log(`FALLA ${nombre} (${Date.now() - t0} ms) — ${e.message}`);
	}
}

function saltear(nombre, motivo) {
	resultados.push({ nombre, estado: 'SALTEADO' });
	console.log(`SKIP  ${nombre} — ${motivo}`);
}

function esperar(condicion, mensaje) {
	if (!condicion) throw new Error(mensaje);
}

function requestIdDe(res) {
	return res.headers.get('x-request-id') ?? 'sin X-Request-Id';
}

// ---- 1. Health ------------------------------------------------------------------------------
for (const ruta of ['/actuator/health/liveness', '/actuator/health/readiness', '/actuator/health']) {
	await chequeo(`backend ${ruta}`, async () => {
		const { res, json } = await pedir(actuator + ruta);
		esperar(res.status === 200, `HTTP ${res.status}`);
		esperar(json?.status === 'UP', `status=${json?.status}`);
		return 'UP';
	});
}

// ---- 2 y 3. Version, contrato y correlacion -------------------------------------------------
let contratoBackend;
await chequeo('backend /api/v1/version', async () => {
	const enviado = `smoke-${randomUUID()}`;
	const { res, json } = await pedir(`${api}/api/v1/version`, { headers: { 'X-Request-Id': enviado } });
	esperar(res.status === 200, `HTTP ${res.status}`);
	esperar(json?.contract, 'la respuesta no trae contract');
	esperar(res.headers.get('x-request-id') === enviado,
		`X-Request-Id no vuelve igual (G-4): recibido ${res.headers.get('x-request-id')}`);
	contratoBackend = json.contract;
	if (env.AKINE_SMOKE_CONTRACT) {
		esperar(json.contract === env.AKINE_SMOKE_CONTRACT,
			`contrato ${json.contract}, se esperaba ${env.AKINE_SMOKE_CONTRACT}: ¿corre la imagen que se desplego?`);
	}
	return `aplicacion ${json.version}, contrato ${json.contract}`;
});

// ---- 4. Login, contexto y una lectura sin PHI -----------------------------------------------
if (env.AKINE_SMOKE_EMAIL && env.AKINE_SMOKE_PASSWORD) {
	// Se entra por donde entran los usuarios: el frontend si esta, el backend si no.
	const base = web || api;
	let token;
	await chequeo(`login de la cuenta de humo (via ${web ? 'frontend' : 'backend'})`, async () => {
		const { res, json } = await pedir(`${base}/api/v1/auth/login`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
			body: JSON.stringify({ email: env.AKINE_SMOKE_EMAIL, password: env.AKINE_SMOKE_PASSWORD }),
		});
		esperar(res.status === 200, `HTTP ${res.status} (requestId ${requestIdDe(res)}; 401 = credencial o cuenta no activa, 429 = rate limit)`);
		esperar(json?.accessToken, 'la respuesta no trae accessToken');
		token = json.accessToken;
	});
	if (token) {
		await chequeo('contexto de trabajo', async () => {
			const auth = { Authorization: `Bearer ${token}`, Accept: 'application/json' };
			const { res, json } = await pedir(`${base}/api/v1/me/contexts`, { headers: auth });
			esperar(res.status === 200, `GET /me/contexts HTTP ${res.status} (requestId ${requestIdDe(res)})`);
			esperar(Array.isArray(json) && json.length > 0, 'la cuenta de humo no tiene ningun contexto autorizado');
			const ctx = json[0];
			const sel = await pedir(`${base}/api/v1/auth/context`, {
				method: 'POST',
				headers: { ...auth, 'Content-Type': 'application/json' },
				body: JSON.stringify({ organizationId: ctx.organizationId, consultorioId: ctx.consultorioId }),
			});
			esperar(sel.res.status === 200 && sel.json?.accessToken,
				`POST /auth/context HTTP ${sel.res.status} (requestId ${requestIdDe(sel.res)})`);
			token = sel.json.accessToken;
			return `${json.length} contexto(s)`;
		});
		await chequeo('lectura autenticada /api/v1/me/permissions', async () => {
			const { res, json } = await pedir(`${base}/api/v1/me/permissions`, {
				headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
			});
			esperar(res.status === 200, `HTTP ${res.status} (requestId ${requestIdDe(res)})`);
			const permisos = json?.permissions ?? json?.permissionCodes ?? [];
			return `${Array.isArray(permisos) ? permisos.length : '?'} permisos`;
		});
	}
} else {
	saltear('login de la cuenta de humo', 'faltan AKINE_SMOKE_EMAIL / AKINE_SMOKE_PASSWORD');
}

// ---- 5 y 6. Frontend ------------------------------------------------------------------------
if (web) {
	await chequeo('frontend / sirve index.html', async () => {
		const { res, texto } = await pedir(`${web}/`);
		esperar(res.status === 200, `HTTP ${res.status}`);
		esperar((res.headers.get('content-type') ?? '').includes('text/html'), `content-type ${res.headers.get('content-type')}`);
		esperar(texto.includes('<app-root'), 'el HTML no tiene <app-root>: no es la SPA');
	});
	await chequeo('frontend ruta profunda cae en index.html', async () => {
		const { res, texto } = await pedir(`${web}/smoke/ruta/que/no/existe`);
		esperar(res.status === 200 && texto.includes('<app-root'), `HTTP ${res.status}`);
	});
	await chequeo('frontend /healthz', async () => {
		const { res, texto } = await pedir(`${web}/healthz`);
		esperar(res.status === 200 && texto.trim() === 'ok', `HTTP ${res.status}`);
	});
	await chequeo('frontend proxyea /api al backend', async () => {
		const { res, json } = await pedir(`${web}/api/v1/version`);
		esperar(res.status === 200, `HTTP ${res.status} (502 = el nginx no alcanza AKINE_API_URL)`);
		if (contratoBackend) {
			esperar(json?.contract === contratoBackend,
				`via frontend contrato ${json?.contract}, directo ${contratoBackend}: el frontend apunta a otro backend`);
		}
		return `contrato ${json?.contract}`;
	});
} else {
	saltear('frontend', 'falta AKINE_SMOKE_WEB_URL');
}

// ---- Resultado ------------------------------------------------------------------------------
const fallas = resultados.filter((r) => r.estado === 'FALLA').length;
const salteados = resultados.filter((r) => r.estado === 'SALTEADO').length;
const ok = fallas === 0 && (salteados === 0 || env.AKINE_SMOKE_ALLOW_SKIP === '1');
console.log(`\nSMOKE ${ok ? 'VERDE' : 'ROJO'}: ${resultados.length - fallas - salteados} ok, ${fallas} fallas, ${salteados} salteados`);
process.exit(ok ? 0 : 1);
