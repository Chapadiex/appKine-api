#!/usr/bin/env node
// Datos del simulacro de restore (ops/simulacro-restore.sh). Node 18+, sin dependencias.
//
//   node ops/simulacro/datos.mjs sembrar   <estado.json>
//   node ops/simulacro/datos.mjs comprobar <estado.json>
//
// sembrar: alta self-service de una organizacion, activacion por el enlace que llega a Mailpit,
//   login, contexto, tres personas y un adjunto PNG. Todo SINTETICO y todo por la API publica:
//   nada se escribe en la base a mano. Guarda en estado.json lo que comprobar necesita (la
//   contrasena generada incluida: el archivo vive en el directorio de trabajo del simulacro y se
//   borra con el).
// comprobar: vuelve a entrar con la misma cuenta contra el backend RESTAURADO, lista las personas
//   y el adjunto (que figure DISPONIBLE y que se descargue con el mismo SHA-256).
//
// Variables: SIM_API_URL (backend), SIM_MAILPIT_URL (UI/API de Mailpit; solo para sembrar).

import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const [accion, archivoEstado] = process.argv.slice(2);
const api = process.env.SIM_API_URL?.replace(/\/$/, '');
if (!api || !archivoEstado || !['sembrar', 'comprobar'].includes(accion)) {
	console.error('uso: SIM_API_URL=... datos.mjs sembrar|comprobar <estado.json>');
	process.exit(2);
}

// PNG de 1x1, transparente. El backend decide el tipo por los bytes: tiene que ser un PNG real.
const PNG = Buffer.from(
	'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
	'base64');
const sha = (b) => createHash('sha256').update(b).digest('hex');

async function http(metodo, ruta, { token, json, form, headers = {}, esperado = [200] } = {}) {
	const res = await fetch(`${api}${ruta}`, {
		method: metodo,
		headers: {
			Accept: 'application/json',
			...(token ? { Authorization: `Bearer ${token}` } : {}),
			...(json ? { 'Content-Type': 'application/json' } : {}),
			...headers,
		},
		body: json ? JSON.stringify(json) : form,
	});
	const buf = Buffer.from(await res.arrayBuffer());
	if (!esperado.includes(res.status)) {
		throw new Error(`${metodo} ${ruta}: HTTP ${res.status} (requestId ${res.headers.get('x-request-id')}) ${buf.toString('utf8').slice(0, 300)}`);
	}
	let cuerpo;
	try { cuerpo = JSON.parse(buf.toString('utf8')); } catch { cuerpo = buf; }
	return cuerpo;
}

async function entrar(email, password) {
	const login = await http('POST', '/api/v1/auth/login', { json: { email, password } });
	const contextos = await http('GET', '/api/v1/me/contexts', { token: login.accessToken });
	if (!contextos.length) throw new Error('la cuenta no tiene contexto');
	const { organizationId, consultorioId } = contextos[0];
	const ctx = await http('POST', '/api/v1/auth/context', {
		token: login.accessToken, json: { organizationId, consultorioId },
	});
	return { token: ctx.accessToken, organizationId, consultorioId };
}

async function enlaceDeActivacion(mailpit, email) {
	for (let i = 0; i < 60; i++) {
		const lista = await (await fetch(`${mailpit}/api/v1/messages?limit=50`)).json();
		const msg = (lista.messages ?? []).find((m) => (m.To ?? []).some((t) => t.Address === email));
		if (msg) {
			const detalle = await (await fetch(`${mailpit}/api/v1/message/${msg.ID}`)).json();
			const token = `${detalle.Text ?? ''} ${detalle.HTML ?? ''}`.match(/token=([A-Za-z0-9_-]+)/)?.[1];
			if (token) return token;
		}
		await new Promise((r) => setTimeout(r, 2000)); // el worker del outbox corre cada 15 s
	}
	throw new Error(`no llego el correo de activacion a Mailpit para ${email}`);
}

if (accion === 'sembrar') {
	const mailpit = process.env.SIM_MAILPIT_URL?.replace(/\/$/, '');
	if (!mailpit) throw new Error('falta SIM_MAILPIT_URL');
	const sufijo = randomBytes(4).toString('hex');
	const email = `simulacro-${sufijo}@ejemplo.test`;
	const password = `Simulacro-${randomBytes(12).toString('hex')}`;

	// BASICO: el plan por defecto del alta self-service (el catalogo de planes pide sesion).
	const planCode = process.env.SIM_PLAN_CODE ?? 'BASICO';
	await http('POST', '/api/v1/auth/register', {
		headers: { 'Idempotency-Key': randomUUID() },
		esperado: [202],
		json: {
			email, password, firstName: 'Simulacro', lastName: 'Restore',
			organizationName: `Centro Simulacro ${sufijo}`, organizationSlug: `simulacro-${sufijo}`,
			consultorioName: 'Sede Simulacro', planCode,
		},
	});
	const token = await enlaceDeActivacion(mailpit, email);
	await http('POST', '/api/v1/auth/activate', { json: { token }, esperado: [200, 204] });

	const sesion = await entrar(email, password);
	const personas = [];
	for (const [apellido, nombre] of [['Simulacro', 'Ana'], ['Restore', 'Bruno'], ['Backup', 'Carla']]) {
		const p = await http('POST', '/api/v1/personas', {
			token: sesion.token, esperado: [201], json: { apellido, nombre },
		});
		personas.push(p.id);
	}
	const form = new FormData();
	form.append('archivo', new Blob([PNG], { type: 'image/png' }), 'simulacro.png');
	const adjunto = await http('POST',
		`/api/v1/personas/${personas[0]}/adjuntos?categoria=OTRO&titulo=simulacro`,
		{ token: sesion.token, form, esperado: [201] });

	writeFileSync(archivoEstado, JSON.stringify({
		email, password, organizationId: sesion.organizationId, personas,
		adjunto: { personaId: personas[0], id: adjunto.id, sha256: sha(PNG) },
	}, null, 2));
	console.log(`sembrado: organizacion ${sesion.organizationId}, ${personas.length} personas, adjunto ${adjunto.id}`);
} else {
	const estado = JSON.parse(readFileSync(archivoEstado, 'utf8'));
	const sesion = await entrar(estado.email, estado.password);
	if (sesion.organizationId !== estado.organizationId) {
		throw new Error(`la cuenta entra a la organizacion ${sesion.organizationId}, se sembro ${estado.organizationId}`);
	}
	const pagina = await http('GET', '/api/v1/personas?size=100', { token: sesion.token });
	const ids = pagina.content.map((p) => p.id).sort((a, b) => a - b);
	const esperados = [...estado.personas].sort((a, b) => a - b);
	if (JSON.stringify(ids) !== JSON.stringify(esperados)) {
		throw new Error(`personas por API ${JSON.stringify(ids)}, se sembraron ${JSON.stringify(esperados)}`);
	}
	const adjuntos = await http('GET', `/api/v1/personas/${estado.adjunto.personaId}/adjuntos`, { token: sesion.token });
	const lista = Array.isArray(adjuntos) ? adjuntos : adjuntos.content ?? [];
	const fila = lista.find((a) => a.id === estado.adjunto.id);
	if (!fila || fila.estado !== 'DISPONIBLE') {
		throw new Error(`el adjunto ${estado.adjunto.id} no figura DISPONIBLE por API: ${JSON.stringify(fila ?? null)}`);
	}

	// La descarga. SIM_DESCARGA=avisar la deja como aviso: hoy responde 500 en main por un defecto
	// que no es de backup (AdjuntoService.contenido audita dentro de una transaccion readOnly). La
	// integridad del binario restaurado la prueba igual ops/verificar-adjuntos.sh: SHA-256 del
	// archivo contra checksum_sha256 de la fila. Cuando el defecto se corrija, sacar el aviso.
	let descarga = 'descargado y SHA-256 identico';
	try {
		const binario = await http('GET',
			`/api/v1/personas/${estado.adjunto.personaId}/adjuntos/${estado.adjunto.id}/contenido`,
			{ token: sesion.token, headers: { Accept: '*/*' } });
		const bytes = Buffer.isBuffer(binario) ? binario : Buffer.from(JSON.stringify(binario));
		if (sha(bytes) !== estado.adjunto.sha256) {
			throw new Error('el adjunto descargado no coincide con el sembrado (SHA-256)');
		}
	} catch (e) {
		if (process.env.SIM_DESCARGA !== 'avisar') throw e;
		descarga = `DISPONIBLE por API; la DESCARGA fallo (${e.message.slice(0, 120)}) — aviso, no falla`;
	}
	console.log(`comprobado por API: login ok, ${ids.length} personas con los mismos ids, adjunto ${estado.adjunto.id} ${descarga}`);
}
