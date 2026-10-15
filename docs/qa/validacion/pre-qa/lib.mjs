#!/usr/bin/env node
// PRE-QA automatizado de la guia de QA manual (docs/qa/01..12). NO reemplaza el QA manual: recorre
// cada paso por la API (lo que la guia hace "por pantalla", con la operacion HTTP equivalente),
// corre la SQL de la guia contra la base del stack aislado y compara con lo esperado.
//
//   export QA_PREFIJO=preqa QA_PUERTO_MYSQL=3359 QA_PUERTO_API=8129 QA_PUERTO_MAILPIT=8079 QA_PUERTO_SMTP=1079
//   docs/qa/validacion/stack.sh build && docs/qa/validacion/stack.sh up
//   QA_SIN_FERIADOS=1 QA_API_URL=http://localhost:8129 QA_MAILPIT_URL=http://localhost:8079 \
//     node docs/qa/validacion/sembrar.mjs <estado.json>          # bootstrap DP-14 de la plataforma
//   QA_PREFIJO=preqa QA_API_URL=http://localhost:8129 QA_MAILPIT_URL=http://localhost:8079 \
//     node docs/qa/validacion/pre-qa.mjs docs/qa/corridas/<fecha>-pre-qa.md [resultado.json]
//   docs/qa/validacion/stack.sh down --imagen
//
// Node 18+, sin dependencias. Las SQL corren con el cliente mysql del contenedor <prefijo>-mysql.
// Pausa la base (ON-12), reinicia el backend (PLA-01) y baja Mailpit (ORG-15): SOLO los contenedores
// <prefijo>-*, nunca akine-mysql ni los puertos de desarrollo. Cuentas sinteticas @ejemplo.test con
// la misma contrasena de prueba de sembrar.mjs (base efimera); ninguna contrasena ni token se imprime.

import { randomUUID, createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const API = (process.env.QA_API_URL ?? 'http://localhost:8129').replace(/\/$/, '');
const MAILPIT = (process.env.QA_MAILPIT_URL ?? 'http://localhost:8079').replace(/\/$/, '');
const P = process.env.QA_PREFIJO ?? 'preqa';
const MYSQL = `${P}-mysql`, APIC = `${P}-api`, MAILC = `${P}-mailpit`, RED = `${P}-net`;
const DB = process.env.QA_DB ?? 'akine_local';
const PLATAFORMA = process.env.QA_PLATAFORMA_EMAIL ?? 'plataforma.qa@ejemplo.test';
const AQUI = dirname(fileURLToPath(import.meta.url));
const PASSWORD = /const PASSWORD = '([^']+)'/.exec(readFileSync(join(AQUI, '..', 'sembrar.mjs'), 'utf8'))[1];
const PASSWORD2 = `${PASSWORD}-nueva`;
const SALIDA = process.argv[2] ?? join(AQUI, '..', '..', 'corridas', 'pre-qa.md');
const SALIDA_JSON = process.argv[3];
const SOLO = process.env.QA_SOLO ? new RegExp(process.env.QA_SOLO) : null; // depuracion
const suf = Date.now().toString(36);
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));
const UA = 'akine-pre-qa/1.0';
const inicioCorrida = new Date();

// ------------------------------------------------------------------ resultados
class Corte extends Error {}
class Bloqueo extends Error {}
const R = [];
let cur = null;
const CIRC = {
	ON: '01 Onboarding y cuentas', ORG: '02 Organizacion, sedes, colaboradores', CAT: '03 Catalogo, servicios, ofertas',
	PER: '04 Personas, coberturas, autorizaciones', AGE: '05 Agenda, turnos, recepcion', CLI: '06 Atencion clinica',
	ECO: '07 Obligaciones, cobros, caja', PRE: '08 Presentaciones', REP: '09 Reportes', PLA: '10 Plataforma',
	AIS: '11 Aislamiento y rol PACIENTE', PAC: '11 Aislamiento y rol PACIENTE', EGR: '12 Egresos y pagos',
};
async function paso(id, titulo, fn) {
	if (SOLO && !SOLO.test(id)) return;
	cur = { id, titulo, estado: 'OK', checks: 0, fallas: [], notas: [], manualTotal: null, sqls: [] };
	const t0 = Date.now();
	try {
		await fn();
	} catch (e) {
		if (e instanceof Bloqueo) cur.bloqueo = e.message;
		else if (!(e instanceof Corte)) cur.fallas.push({ desc: `excepcion del runner: ${e.message}`, ctx: [cur.ultimo].filter(Boolean).map(descr), sql: ultimaSql() });
	}
	if (cur.fallas.length) cur.estado = 'FALLA';
	else if (cur.bloqueo) cur.estado = 'BLOQUEADO';
	else if (cur.manualTotal) cur.estado = 'MANUAL';
	cur.ms = Date.now() - t0;
	R.push(cur);
	const extra = cur.estado === 'FALLA' ? ` — ${cur.fallas.map((f) => f.desc).join(' | ')}` : cur.bloqueo ? ` — ${cur.bloqueo}` : '';
	console.log(`[${cur.estado.padEnd(9)}] ${id} ${titulo} (${cur.checks} chequeos, ${(cur.ms / 1000).toFixed(1)} s)${extra}`);
	cur = null;
}
const ultimaSql = () => (cur?.sqls?.length ? cur.sqls.at(-1) : null);
function ok(cond, desc, ...ctx) {
	cur.checks++;
	if (!cond) cur.fallas.push({ desc, ctx: ctx.filter(Boolean).map((c) => (typeof c === 'string' ? c : c.rows ? fmtSql(c) : descr(c))) });
	return !!cond;
}
const eq = (got, exp, desc, ...ctx) => ok(String(got) === String(exp), `${desc}: esperado ${JSON.stringify(exp)}, obtenido ${JSON.stringify(got)}`, ...ctx);
function st(r, codes, desc, ...ctx) {
	const cs = [].concat(codes);
	return ok(cs.includes(r.status), `${desc}: esperado HTTP ${cs.join('/')}, obtenido ${r.status}${r.tipo ? ` ${r.tipo}` : ''}`, r, ...ctx);
}
function tipo(r, t, desc) {
	return ok(r.tipo === t, `${desc}: esperado problemType ${t}, obtenido ${r.status} ${r.tipo}`, r);
}
function must(r, codes, desc) {
	if (!st(r, codes, desc)) throw new Corte();
	return r.cuerpo;
}
function need(...vals) {
	if (vals.some((v) => v === undefined || v === null)) throw new Bloqueo('falta un prerequisito de un paso anterior');
}
const nota = (t) => cur.notas.push(t);
const manual = (t) => cur.notas.push(`MANUAL: ${t}`);
const soloManual = (t) => { cur.manualTotal = t; cur.notas.push(`MANUAL: ${t}`); };
const n = (x) => (x === null || x === undefined ? null : Number(x));

// ------------------------------------------------------------------ HTTP
const SECRETO = /^(password|token|accessToken|refreshToken)$/i;
function limpiar(o) {
	if (Array.isArray(o)) return o.map(limpiar);
	if (o && typeof o === 'object') return Object.fromEntries(Object.entries(o).map(([k, v]) => [k, SECRETO.test(k) ? '***' : limpiar(v)]));
	return o;
}
function descr(r) {
	if (!r || !r.metodo) return String(r);
	const cuerpo = typeof r.cuerpo === 'string' ? r.cuerpo.slice(0, 500) : JSON.stringify(limpiar(r.cuerpo))?.slice(0, 700);
	return `${r.metodo} ${r.ruta}${r.pedido !== undefined ? `\n    cuerpo: ${JSON.stringify(r.pedido).slice(0, 500)}` : ''}\n    -> HTTP ${r.status}${r.tipo ? ` ${r.tipo}` : ''}: ${cuerpo}`;
}
async function api(metodo, ruta, { s, json, headers = {}, form, cookie, timeoutMs = 90000, reintentar429 = true } = {}) {
	for (let i = 0; ; i++) {
		const h = { Accept: 'application/json, application/problem+json', 'User-Agent': UA, ...headers };
		const token = typeof s === 'string' ? s : s?.token;
		if (token) h.Authorization = `Bearer ${token}`;
		const ck = cookie ?? s?.cookie;
		if (ck) h.Cookie = ck;
		let body;
		if (form) body = form;
		else if (json !== undefined) { h['Content-Type'] = 'application/json'; body = JSON.stringify(json); }
		const ac = new AbortController();
		const tm = setTimeout(() => ac.abort(), timeoutMs);
		const t0 = Date.now();
		let res, buf;
		try {
			res = await fetch(API + ruta, { method: metodo, headers: h, body, signal: ac.signal });
			buf = Buffer.from(await res.arrayBuffer());
		} catch (e) {
			clearTimeout(tm);
			if (i < 2 && !ac.signal.aborted) { await dormir(2000); continue; }
			const r = { metodo, ruta, status: 0, cuerpo: `error de red: ${e.message}`, tipo: null, headers: new Headers(), buf: Buffer.alloc(0), ms: Date.now() - t0 };
			if (cur) cur.ultimo = r;
			return r;
		}
		clearTimeout(tm);
		if (res.status === 429 && reintentar429 && i < 30) {
			await dormir(Math.min(65, Number(res.headers.get('retry-after') ?? 10)) * 1000);
			continue;
		}
		const texto = buf.toString('utf8');
		let cuerpo;
		try { cuerpo = JSON.parse(texto); } catch { cuerpo = texto; }
		const t = cuerpo && typeof cuerpo === 'object' && typeof cuerpo.type === 'string' ? cuerpo.type.split('/').pop() : null;
		const r = { metodo, ruta, status: res.status, cuerpo, tipo: t, headers: res.headers, buf, texto, ms: Date.now() - t0, pedido: json !== undefined ? limpiar(json) : form ? '(multipart)' : undefined };
		if (cur) cur.ultimo = r;
		return r;
	}
}
const galletas = (r) => (r.headers.getSetCookie?.() ?? []);
function cookieRt(r) {
	const c = galletas(r).find((x) => x.startsWith('akine_rt='));
	return c ? c.split(';')[0] : null;
}

// ------------------------------------------------------------------ SQL
const V = {};
function lit(v) {
	if (v === null || v === undefined) return 'NULL';
	if (typeof v === 'number') return String(v);
	return `'${String(v).replace(/\\/g, '\\\\').replace(/'/g, "''")}'`;
}
function unesc(s) {
	if (s === undefined || s === 'NULL') return null;
	return s.replace(/\\n/g, '\n').replace(/\\t/g, '\t').replace(/\\\\/g, '\\');
}
function q(sql) {
	const pre = Object.entries(V).filter(([, v]) => v !== undefined).map(([k, v]) => `SET @${k} := ${lit(v)};`).join('\n');
	const texto = `${pre}\n${sql.trim().replace(/;?\s*$/, ';')}\n`;
	const out = execFileSync('docker', ['exec', '-i', MYSQL, 'mysql', '-uakine', '-pakine', '--batch', '--default-character-set=utf8mb4', DB],
		{ input: texto, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 });
	const lineas = out.replace(/\n$/, '').split('\n');
	let rows = [];
	if (out.trim()) {
		const cols = lineas[0].split('\t');
		rows = lineas.slice(1).map((l) => { const vs = l.split('\t'); return Object.fromEntries(cols.map((c, i) => [c, unesc(vs[i])])); });
	}
	const res = { sql: sql.trim(), rows };
	if (cur) cur.sqls.push(res);
	return Object.assign(rows, { sql: res.sql, rows });
}
const q1 = (sql) => q(sql)[0] ?? {};
function fmtSql(r) {
	const filas = r.rows.slice(0, 12).map((x) => JSON.stringify(x)).join('\n      ');
	return `SQL:\n      ${r.sql.replace(/\n/g, '\n      ')}\n    filas (${r.rows.length}):\n      ${filas || '(ninguna)'}`;
}
const ahoraDb = () => q1('SELECT DATE_FORMAT(UTC_TIMESTAMP(6), "%Y-%m-%d %H:%i:%s.%f") t').t;
const maxAudit = () => n(q1('SELECT COALESCE(MAX(id),0) m FROM audit_event').m);
function auditDesde(desdeId, extra = '') {
	return q(`SELECT id, organization_id, event_type, entity_type, entity_id, actor_account_id, consultorio_id, previous_state, new_state, reason, details FROM audit_event WHERE id > ${desdeId} ${extra} ORDER BY id`);
}
const tiene = (rows, ev, f = () => true) => rows.some((r) => r.event_type === ev && f(r));
const cuenta = (rows, ev, f = () => true) => rows.filter((r) => r.event_type === ev && f(r)).length;
const det = (r) => { try { return JSON.parse(r.details ?? '{}'); } catch { return {}; } };

function docker(...args) {
	return execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

// ------------------------------------------------------------------ Mailpit
async function correos(email) {
	try {
		const r = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}&limit=50`)).json();
		return r.messages ?? [];
	} catch { return []; }
}
async function textoCorreo(id) {
	const m = await (await fetch(`${MAILPIT}/api/v1/message/${id}`)).json();
	return `${m.Text ?? ''} ${m.HTML ?? ''}`;
}
async function esperarCorreos(email, cantidad, filtro = () => true, segs = 90) {
	let l = [];
	for (let i = 0; i < segs / 2; i++) {
		l = (await correos(email)).filter((m) => filtro(m.Subject ?? ''));
		if (l.length >= cantidad) return l;
		await dormir(2000);
	}
	return l;
}
const tokenDe = (t) => /[?&]token=([A-Za-z0-9_-]+)/.exec(t)?.[1];
/** Token del correo `indice` (0 = el mas nuevo) entre los que pasan el filtro, esperando a que haya `cantidad`. */
async function tokenCorreo(email, filtro, cantidad = 1, indice = 0) {
	const l = await esperarCorreos(email, cantidad, filtro);
	if (l.length < cantidad) return null;
	return tokenDe(await textoCorreo(l[indice].ID));
}
const ES_ACTIVACION = (s) => /activ/i.test(s);
const ES_INVITACION = (s) => /invit/i.test(s);

// ------------------------------------------------------------------ cuentas y sesiones
const pw = {};
const correo = (r) => `qa.${r}.${suf}@ejemplo.test`;
async function registrar(email, organizacion, { clave = randomUUID(), password = PASSWORD, extra = {} } = {}) {
	pw[email] = password;
	return api('POST', '/api/v1/auth/register', {
		headers: { 'Idempotency-Key': clave },
		json: { email, password, firstName: 'QA', lastName: `Sintetica ${suf}`, organizationName: organizacion, ...extra },
	});
}
async function activar(email) {
	const t = await tokenCorreo(email, ES_ACTIVACION);
	if (!t) return { status: 0, cuerpo: 'no llego el correo de activacion', metodo: 'MAILPIT', ruta: email };
	return api('POST', '/api/v1/auth/activate', { json: { token: t } });
}
const login = (email, password = pw[email] ?? PASSWORD, headers = {}) => api('POST', '/api/v1/auth/login', { json: { email, password }, headers });
/** Login + contexto. Devuelve { token, cookie, organizationId, consultorioId, email, t }. */
async function entrar(email, { org, sede, headers } = {}) {
	const l = await login(email, undefined, headers);
	if (l.status !== 200) throw new Bloqueo(`no entra ${email}: HTTP ${l.status}`);
	let cookie = cookieRt(l);
	const pre = l.cuerpo.accessToken;
	const cs = (await api('GET', '/api/v1/me/contexts', { s: pre })).cuerpo;
	if (!Array.isArray(cs) || !cs.length) return { token: pre, cookie, email, t: Date.now(), contextos: [] };
	const c = cs.find((x) => (org === undefined || x.organizationId === org) && (sede === undefined || x.consultorioId === sede))
		?? cs.find((x) => org === undefined || x.organizationId === org);
	if (!c) throw new Bloqueo(`${email} no tiene contexto en la organizacion ${org}`);
	const ctx = await api('POST', '/api/v1/auth/context', { s: pre, cookie, json: { organizationId: c.organizationId, consultorioId: c.consultorioId } });
	if (ctx.status !== 200) throw new Bloqueo(`no toma contexto ${email}: HTTP ${ctx.status}`);
	cookie = cookieRt(ctx) ?? cookie;
	return { token: ctx.cuerpo.accessToken, cookie, organizationId: c.organizationId, consultorioId: c.consultorioId, email, t: Date.now(), contextos: cs, respuesta: l };
}
const CU = {}; // rol -> { email, org, sede }
const SES = {};
async function S(rol) {
	const c = CU[rol];
	if (!c) throw new Bloqueo(`no existe la cuenta ${rol}`);
	const s = SES[rol];
	if (s && Date.now() - s.t < 7 * 60 * 1000) return s;
	SES[rol] = await entrar(c.email, { org: c.org, sede: c.sede });
	return SES[rol];
}
async function Splat() {
	const s = SES.plat;
	if (s && Date.now() - s.t < 7 * 60 * 1000) return s;
	const l = await login(PLATAFORMA, PASSWORD);
	if (l.status !== 200) throw new Bloqueo(`no entra la plataforma: HTTP ${l.status} (corra antes sembrar.mjs)`);
	SES.plat = { token: l.cuerpo.accessToken, cookie: cookieRt(l), t: Date.now() };
	return SES.plat;
}

// ------------------------------------------------------------------ fechas, agenda
export let ZONA = 'America/Argentina/Cordoba';
const hoy = (d = 0) => new Date(Date.now() + d * 86400000).toLocaleDateString('en-CA', { timeZone: ZONA });
const horaLocal = (iso) => new Date(iso).toLocaleTimeString('en-GB', { timeZone: ZONA, hour: '2-digit', minute: '2-digit', hour12: false });
const fechaLocal = (iso) => new Date(iso).toLocaleDateString('en-CA', { timeZone: ZONA });
/** Instante UTC de una fecha y hora locales de la sede (sin DST en Argentina: offset fijo de la zona). */
function instanteLocal(fecha, hhmm) {
	const guess = new Date(`${fecha}T${hhmm}:00Z`);
	const off = new Date(guess.toLocaleString('en-US', { timeZone: ZONA })) - new Date(guess.toLocaleString('en-US', { timeZone: 'UTC' }));
	return new Date(guess.getTime() - off).toISOString();
}
const C = {}; // estado de la corrida
const usados = new Set();
async function agenda(s, sede, oferta, desde, hasta, prof) {
	return api('GET', `/api/v1/consultorios/${sede}/ofertas/${oferta}/agenda?desde=${desde}&hasta=${hasta}${prof ? `&profesionalId=${prof}` : ''}`, { s });
}
/** Primer slot libre (y no usado por esta corrida) del dia `dia`, al menos `margenMin` minutos en el futuro. */
async function slot({ dia = 0, margenMin = 4, oferta = C.oferta, sede = C.sede_a, prof = C.mprof, despuesDe, s } = {}) {
	need(oferta, sede);
	const r = await agenda(s ?? (await S('admin')), sede, oferta, hoy(dia), hoy(dia + 1), prof);
	if (r.status !== 200) throw new Bloqueo(`agenda HTTP ${r.status}`);
	const lim = Date.now() + margenMin * 60000;
	const sl = r.cuerpo.dias.flatMap((d) => d.slots).find((x) => new Date(x.desde).getTime() > lim && !usados.has(x.desde) && (!despuesDe || new Date(x.desde) > new Date(despuesDe)));
	if (!sl) throw new Bloqueo(`sin slots libres el dia ${hoy(dia)} (zona ${ZONA})`);
	usados.add(sl.desde);
	return sl;
}
async function reservar(s, personaId, sl, { coberturaId, oferta = C.oferta, sede = C.sede_a, prof = C.mprof, key = randomUUID() } = {}) {
	return api('POST', `/api/v1/consultorios/${sede}/turnos/ofertas/${oferta}`, { s, json: { inicio: sl.desde ?? sl, personaId, profesionalId: prof, coberturaId, idempotencyKey: key } });
}
/** Llegada -> validacion con cobertura (o particular) -> espera -> llamado. Devuelve la ultima respuesta. */
async function recepcionHastaLlamado(s, turnoId, { cobertura, particular, sede = C.sede_a } = {}) {
	const base = `/api/v1/consultorios/${sede}/turnos/${turnoId}/recepcion`;
	let r = await api('POST', base, { s });
	if (![200, 201].includes(r.status)) return r;
	r = particular
		? await api('POST', `${base}/particular`, { s, json: { motivo: particular, expectedVersion: r.cuerpo.version } })
		: await api('POST', `${base}/validacion`, { s, json: { coberturaId: cobertura, expectedVersion: r.cuerpo.version } });
	if (r.status !== 200) return r;
	r = await api('POST', `${base}/espera`, { s, json: { expectedVersion: r.cuerpo.version } });
	if (r.status !== 200) return r;
	return api('POST', `${base}/llamado`, { s, json: { expectedVersion: r.cuerpo.version } });
}
const ses = (id) => `/api/v1/consultorios/${C.sede_a}/sesiones/${id}`;
async function iniciarSesion(turnoId, { casoId, s } = {}) {
	return api('POST', `/api/v1/consultorios/${C.sede_a}/sesiones/turnos/${turnoId}${casoId ? `?casoId=${casoId}` : ''}`, { s: s ?? (await S('prof')) });
}
async function tratar(sesionId, practicaId, extra = {}) {
	const s = await S('prof');
	const x = await api('GET', ses(sesionId), { s });
	return api('POST', `${ses(sesionId)}/tratamientos`, { s, json: { practicaId, version: x.cuerpo.version, duracionMinutos: 20, zona: 'Rodilla', lateralidad: 'DERECHA', ...extra } });
}
async function cerrarSesion(sesionId, extra = {}) {
	const s = await S('prof');
	const x = await api('GET', ses(sesionId), { s });
	return api('POST', `${ses(sesionId)}/cierre`, { s, json: { asistencia: 'PRESENTE', tolerancia: 'BUENA', indicaciones: 'Hielo 15 min (sintetico)', proximaConducta: 'CONTINUA', notaDeCierre: 'Sesion sintetica pre-QA', version: x.cuerpo.version, ...extra } });
}
/** Turno de hoy -> recepcion -> sesion iniciada (y opcionalmente tratamiento + cierre). */
async function atencionCompleta(personaId, { cobertura, particular, casoId, oferta = C.oferta, practica = C.practica, cerrar = true, coberturaTurno } = {}) {
	const adm = await S('adm');
	const sl = await slot({ oferta });
	const t = await reservar(adm, personaId, sl, { oferta, coberturaId: coberturaTurno });
	if (![200, 201].includes(t.status)) return { error: t };
	const rc = await recepcionHastaLlamado(adm, t.cuerpo.id, { cobertura, particular });
	if (rc.status !== 200) return { error: rc, turno: t.cuerpo };
	const si = await iniciarSesion(t.cuerpo.id, { casoId });
	if (si.status !== 201) return { error: si, turno: t.cuerpo };
	const out = { turno: t.cuerpo, sesion: si.cuerpo };
	if (practica) {
		const tr = await tratar(si.cuerpo.id, practica);
		if (tr.status !== 201) return { ...out, error: tr };
	}
	if (cerrar) {
		const c = await cerrarSesion(si.cuerpo.id);
		if (c.status !== 200) return { ...out, error: c, cierre: c };
		out.cierre = c;
	}
	return out;
}
// Un PNG real de 1x1 (los bytes deciden el tipo, no la extension).
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
function multipart(nombre, bytes, contentType, campo = 'archivo') {
	const f = new FormData();
	f.append(campo, new Blob([bytes], { type: contentType }), nombre);
	return f;
}
const sha256 = (b) => createHash('sha256').update(b).digest('hex');
const J = { 'X-Justificacion-Acceso': 'Pre-QA automatizado: control interno sintetico' };
export const AUDIT_RANGO = () => `from=${encodeURIComponent(new Date(Date.now() - 86400000).toISOString())}&to=${encodeURIComponent(new Date(Date.now() + 3600000).toISOString())}`;
const ORIGEN = { Origin: 'http://localhost:4200' };
const GUIA = [];
const guia = (t) => { GUIA.push(t); cur?.notas.push(`GUIA: ${t}`); };

export function setZona(z) { ZONA = z; }
export { APIC, MAILC, RED, API, MAILPIT, P, MYSQL, DB, PLATAFORMA, AQUI, PASSWORD, PASSWORD2, SALIDA, SALIDA_JSON, SOLO, suf, dormir, UA, inicioCorrida, Corte, Bloqueo, R, cur, CIRC, paso, ultimaSql, ok, eq, st, tipo, must, need, nota, manual, soloManual, n, SECRETO, limpiar, descr, api, galletas, cookieRt, V, lit, unesc, q, q1, fmtSql, ahoraDb, maxAudit, auditDesde, tiene, cuenta, det, docker, correos, textoCorreo, esperarCorreos, tokenDe, tokenCorreo, ES_ACTIVACION, ES_INVITACION, pw, correo, registrar, activar, login, entrar, CU, SES, S, Splat, hoy, horaLocal, fechaLocal, instanteLocal, C, usados, agenda, slot, reservar, recepcionHastaLlamado, ses, iniciarSesion, tratar, cerrarSesion, atencionCompleta, PNG, multipart, sha256, J, ORIGEN, GUIA, guia };
