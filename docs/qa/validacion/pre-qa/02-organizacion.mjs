import { randomUUID } from 'node:crypto';
import { APIC, MAILC, RED, API, MAILPIT, P, MYSQL, DB, PLATAFORMA, AQUI, PASSWORD, PASSWORD2, SALIDA, SALIDA_JSON, SOLO, suf, dormir, UA, inicioCorrida, Corte, Bloqueo, R, cur, CIRC, paso, ultimaSql, ok, eq, st, tipo, must, need, nota, manual, soloManual, n, SECRETO, limpiar, descr, api, galletas, cookieRt, V, lit, unesc, q, q1, fmtSql, ahoraDb, maxAudit, auditDesde, tiene, cuenta, det, docker, correos, textoCorreo, esperarCorreos, tokenDe, tokenCorreo, ES_ACTIVACION, ES_INVITACION, pw, correo, registrar, activar, login, entrar, CU, SES, S, Splat, hoy, horaLocal, fechaLocal, instanteLocal, C, usados, agenda, slot, reservar, recepcionHastaLlamado, ses, iniciarSesion, tratar, cerrarSesion, atencionCompleta, PNG, multipart, sha256, J, ORIGEN, GUIA, guia, ZONA, setZona, AUDIT_RANGO } from './lib.mjs';

// 02 · Organizacion, sedes, colaboradores y permisos (+ PLA-06, que la guia corre en el medio)
const orgUrl = (p = '') => `/api/v1/organizations/${C.org_a}${p}`;
const membership = (email, sede = C.sede_a) => n(q1(`SELECT m.id FROM membership m JOIN cuenta c ON c.id = m.account_id WHERE m.organization_id = @org_a AND c.email_normalizado = ${lit(email)} AND m.estado = 'ACTIVA' ${sede ? `AND m.consultorio_id = ${sede}` : ''} ORDER BY m.id DESC LIMIT 1`).id);
const vincular = async (email, roleCode, consultorioId = C.sede_a, s) =>
	api('POST', '/api/v1/memberships', { s: s ?? (await S('admin')), json: { email, roleCode, consultorioId, reason: 'Vinculo pre-QA sintetico' } });
export const grant = async (mid, permissionCode, s) => api('POST', orgUrl(`/memberships/${mid}/grants`), { s: s ?? (await S('admin')), json: { permissionCode, reason: 'Grant pre-QA sintetico' } });

export async function c02a() {
	await paso('ORG-01', 'Ver y editar la organizacion', async () => {
		need(C.org_a);
		const d0 = maxAudit();
		const s = await S('admin');
		const o = must(await api('GET', orgUrl(), { s }), 200, 'leer la organizacion');
		const antes = q1('SELECT slug, version FROM organization WHERE id = @org_a');
		const nombre = `Centro QA A ${suf} editado`;
		must(await api('PATCH', orgUrl(), { s, json: { name: nombre, version: o.version } }), 200, 'editar el nombre');
		const f = q('SELECT id, name, slug, timezone, version FROM organization WHERE id = @org_a');
		ok(f[0].name === nombre && n(f[0].version) === n(antes.version) + 1 && f[0].slug === antes.slug, 'name nuevo, version +1 y slug sin cambios', f);
		const au = auditDesde(d0, "AND event_type = 'ORGANIZATION_UPDATED' AND organization_id = @org_a");
		ok(au.length === 1 && JSON.stringify(au[0]).includes(nombre), 'ORGANIZATION_UPDATED con el estado nuevo', au);
		const plan = await api('POST', orgUrl('/subscription/plan-changes'), { s, json: { planCode: 'PROFESIONAL', expectedVersion: 0 } });
		st(plan, 403, 'el ORG_ADMIN no puede cambiar de plan');
		manual('con el administrativo, /organizacion no permite editar (por la API se cubre en ORG-13)');
	});
	await paso('ORG-02', 'Editar la sede', async () => {
		need(C.sede_a);
		const d0 = maxAudit();
		const s = await S('admin');
		const sede = must(await api('GET', orgUrl(`/consultorios/${C.sede_a}`), { s }), 200, 'leer la sede');
		const cambios = { legalName: 'Centro QA A SRL (sintetico)', taxId: '30-71234567-1', addressLine: 'Calle Falsa 123', phone: '3510000000', contactEmail: `sede.${suf}@ejemplo.test`, slotMinutes: 15, version: sede.version };
		if (process.env.QA_ZONA_SEDE) cambios.timezone = process.env.QA_ZONA_SEDE;
		const r = await api('PATCH', orgUrl(`/consultorios/${C.sede_a}`), { s, json: cambios });
		must(r, 200, 'editar los datos legales y el slot');
		setZona(r.cuerpo.timezone);
		const f = q('SELECT id, name, legal_name, tax_id, address_line, phone, contact_email, timezone, slot_minutes, active, version FROM consultorio WHERE id = @sede_a');
		ok(f[0].legal_name === cambios.legalName && f[0].contact_email === cambios.contactEmail && f[0].slot_minutes === '15', 'los datos quedan en la base', f);
		if (process.env.QA_ZONA_SEDE) nota(`la sede se movio a ${process.env.QA_ZONA_SEDE} para tener slots de hoy (QA_ZONA_SEDE)`);
		const au = auditDesde(d0, "AND event_type = 'CONSULTORIO_UPDATED' AND organization_id = @org_a");
		ok(au.length >= 1, 'CONSULTORIO_UPDATED', au);
		if (!process.env.QA_SIN_FERIADOS_NO) must(await api('PUT', `/api/v1/consultorios/${C.sede_a}/calendario`, { s, json: { cierraPorFeriado: false } }), 200, 'sin cierre por feriado (como el sembrado)');
	});
	await paso('ORG-03', 'El plan BASICO no deja abrir una segunda sede', async () => {
		const d0 = maxAudit();
		const r = await api('POST', orgUrl('/consultorios'), { s: await S('admin'), headers: { 'Idempotency-Key': randomUUID() }, json: { name: 'Sede QA 2' } });
		st(r, 409, 'segunda sede con BASICO');
		ok(/plan/i.test(`${r.cuerpo?.detail} ${r.cuerpo?.title} ${r.tipo}`), 'el mensaje habla del plan', r);
		const l = q("SELECT p.code, l.limit_code, l.limit_value FROM subscription s JOIN plan p ON p.id = s.plan_id JOIN plan_limit l ON l.plan_id = p.id WHERE s.organization_id = @org_a AND l.active = 1");
		ok(l.some((x) => x.limit_code === 'MAX_CONSULTORIOS' && x.limit_value === '1'), 'MAX_CONSULTORIOS = 1', l);
		eq(q1('SELECT COUNT(*) n FROM consultorio WHERE organization_id = @org_a').n, 1, 'sigue habiendo 1 sede');
		eq(q1('SELECT COUNT(*) n FROM consultorio_alta WHERE organization_id = @org_a').n, 0, 'ninguna fila en consultorio_alta');
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'PLAN_LIMIT_REJECTED'), 'PLAN_LIMIT_REJECTED', auditDesde(d0, 'AND organization_id = @org_a'));
	});
	await paso('ORG-04', 'Vincular colaboradores por alta directa', async () => {
		need(CU.prof, CU.adm, CU.pac);
		const d0 = maxAudit();
		for (const [c, rol] of [[CU.prof, 'PROFESIONAL'], [CU.adm, 'ADMINISTRATIVO'], [CU.pac, 'PACIENTE']]) must(await vincular(c.email, rol), 201, `vincular ${rol}`);
		const inex = await vincular(`qa.no-existe.${suf}@ejemplo.test`, 'ADMINISTRATIVO');
		st(inex, 404, 'un email sin cuenta responde 404');
		const f = q('SELECT m.id, c.email, m.role_code, m.consultorio_id, m.estado FROM membership m JOIN cuenta c ON c.id = m.account_id WHERE m.organization_id = @org_a ORDER BY m.id');
		C.mprof = V.membership_profesional = membership(CU.prof.email);
		C.madm = V.membership_administrativo = membership(CU.adm.email);
		C.mpac = V.membership_paciente = membership(CU.pac.email);
		ok(C.mprof && C.madm && C.mpac, 'tres memberships ACTIVA con consultorio_id = sede A', f);
		for (const k of ['prof', 'adm', 'pac']) Object.assign(CU[k], { org: C.org_a, sede: C.sede_a });
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		eq(cuenta(au, 'MEMBERSHIP_CREATED', (x) => x.reason === 'Vinculo pre-QA sintetico' && det(x).accountId), 3, 'MEMBERSHIP_CREATED con reason y details.accountId', au);
		ok(tiene(au, 'MEMBERSHIP_ALTA_RECHAZADA'), 'MEMBERSHIP_ALTA_RECHAZADA para el email inexistente', au);
		manual('que aparezcan en /organizacion/colaboradores');
	});
	await paso('ON-10', 'El contexto de una cuenta con dos organizaciones', async () => {
		need(C.mprof);
		const d0 = maxAudit();
		const enA = await entrar(CU.prof.email, { org: C.org_a });
		eq(enA.contextos.length, 2, 'el selector ofrece dos contextos');
		st(await api('GET', '/api/v1/personas?size=1', { s: enA }), 200, 'padron de A');
		const propia = enA.contextos.find((x) => x.organizationId !== C.org_a);
		const enP = await entrar(CU.prof.email, { org: propia?.organizationId });
		const ac = q('SELECT organization_id, consultorio_id FROM account_active_context WHERE account_id = @cuenta_profesional');
		eq(n(ac[0]?.organization_id), propia?.organizationId, 'el contexto activo es el ultimo elegido', ac);
		const au = auditDesde(d0, "AND actor_account_id = @cuenta_profesional AND event_type = 'CONTEXTO_SELECCIONADO'");
		ok(tiene(au, 'CONTEXTO_SELECCIONADO', (x) => n(x.organization_id) === C.org_a) && tiene(au, 'CONTEXTO_SELECCIONADO', (x) => n(x.organization_id) === propia?.organizationId), 'un CONTEXTO_SELECCIONADO por eleccion, con su organizacion', au);
		nota(`con el contexto propio, leer el padron devuelve ${(await api('GET', '/api/v1/personas?size=50', { s: enP })).cuerpo?.totalElements ?? '?'} personas (A no se mezcla)`);
		manual('que al cambiar no quede nada de A en pantalla');
	});
	await paso('ORG-05', 'Tope de 5 miembros activos del plan BASICO', async () => {
		const d0 = maxAudit();
		must(await vincular(CU.reenvio.email, 'ADMINISTRATIVO'), 201, 'el quinto entra');
		C.mquinto = membership(CU.reenvio.email);
		const r = await vincular(CU.b.email, 'ADMINISTRATIVO');
		st(r, 409, 'el sexto se rechaza por plan');
		eq(q1("SELECT COUNT(*) n FROM membership WHERE organization_id = @org_a AND estado = 'ACTIVA'").n, 5, 'activos = 5');
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'PLAN_LIMIT_REJECTED'), 'PLAN_LIMIT_REJECTED');
	});
	await paso('ORG-11', 'Desvincular con impacto previo', async () => {
		need(C.mquinto);
		const d0 = maxAudit();
		const s = await S('admin');
		st(await api('GET', orgUrl(`/memberships/${C.mquinto}/desvinculacion-impacto`), { s }), 200, 'consulta de impacto');
		must(await api('POST', orgUrl(`/memberships/${C.mquinto}/revoke`), { s, json: { reason: 'Desvinculo pre-QA' } }), 200, 'desvincular');
		const f = q(`SELECT id, estado, valid_until, revoked_reason, revoked_by_account_id, active, deleted_at FROM membership WHERE id = ${C.mquinto}`);
		ok(f.length === 1 && f[0].estado === 'REVOCADA' && f[0].valid_until && f[0].revoked_reason === 'Desvinculo pre-QA', 'REVOCADA con valid_until y motivo, la fila sigue', f);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'MEMBERSHIP_REVOKED'), 'MEMBERSHIP_REVOKED');
	});
	await paso('PLA-06', 'Cambiar el plan de A a PROFESIONAL', async () => {
		const d0 = maxAudit();
		const s = await Splat();
		// D3 (api f96aedd): la plataforma lee la version por su propia ruta; la del centro sigue detras de
		// tenant:read y, sin acceso de soporte, es 403.
		st(await api('GET', orgUrl('/subscription'), { s }), 403, 'la suscripcion del centro sin soporte (tenant:read)');
		const gs = await api('GET', `/api/v1/platform/organizations/${C.org_a}/subscription`, { s });
		st(gs, 200, 'la plataforma lee la suscripcion por GET /platform/organizations/{org}/subscription');
		ok(gs.cuerpo?.version != null && gs.cuerpo?.planCode, 'trae plan y version', gs.cuerpo);
		const sub = gs.status === 200 ? gs.cuerpo : { version: n(q1('SELECT version FROM subscription WHERE organization_id = @org_a').version) };
		if (gs.status !== 200) nota('se siguio con la version leida de la base');
		must(await api('POST', orgUrl('/subscription/plan-changes'), { s, json: { planCode: 'PROFESIONAL', expectedVersion: sub.version } }), 200, 'cambio de plan');
		const vieja = await api('POST', orgUrl('/subscription/plan-changes'), { s, json: { planCode: 'BASICO', expectedVersion: sub.version } });
		tipo(vieja, 'concurrent-modification', 'con la version vieja');
		const f = q('SELECT p.code, s.status, s.version FROM subscription s JOIN plan p ON p.id = s.plan_id WHERE s.organization_id = @org_a');
		eq(f[0]?.code, 'PROFESIONAL', 'plan PROFESIONAL', f);
		const t = q('SELECT from_status, to_status, from_plan_id, to_plan_id, reason FROM subscription_transition WHERE organization_id = @org_a ORDER BY id');
		ok(t.some((x) => x.from_plan_id && x.to_plan_id && x.from_plan_id !== x.to_plan_id), 'una transicion con los dos planes', t);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'SUBSCRIPTION_PLAN_CHANGED'), 'SUBSCRIPTION_PLAN_CHANGED');
	});
	await paso('ORG-03b', 'Alta de sede en un acto, con primer box y horario general', async () => {
		const d0 = maxAudit();
		const s = await S('admin');
		const k = randomUUID();
		const cuerpo = { name: `Sede QA 2 ${suf}`, primerBox: { name: 'Box 1', capacidad: 1 }, horarioGeneral: [1, 2, 3, 4, 5].map((d) => ({ diaSemana: d, horaDesde: '08:00', horaHasta: '18:00' })) };
		const r1 = await api('POST', orgUrl('/consultorios'), { s, headers: { 'Idempotency-Key': k }, json: cuerpo });
		C.sede_a2 = V.sede_a2 = must(r1, [200, 201], 'alta de la sede 2').id;
		const r2 = await api('POST', orgUrl('/consultorios'), { s, headers: { 'Idempotency-Key': k }, json: cuerpo });
		ok([200, 201].includes(r2.status) && r2.cuerpo?.id === C.sede_a2, 'la misma Idempotency-Key no crea otra sede', r2);
		eq(q1('SELECT COUNT(*) n FROM consultorio WHERE organization_id = @org_a').n, 2, 'dos sedes');
		const e = q('SELECT id, name, tipo, capacidad, active FROM espacio WHERE consultorio_id = @sede_a2');
		ok(e.length === 1 && e[0].tipo === 'BOX', 'un box', e);
		const h = q('SELECT dia_semana, hora_desde, hora_hasta, active FROM consultorio_horario WHERE consultorio_id = @sede_a2 ORDER BY dia_semana');
		eq(h.length, 5, 'cinco franjas', h);
		eq(q1('SELECT COUNT(*) n FROM consultorio_alta WHERE consultorio_id = @sede_a2').n, 1, 'la fila de idempotencia');
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		for (const ev of ['CONSULTORIO_CREATED', 'ESPACIO_CREATED', 'HORARIO_GENERAL_UPDATED']) ok(tiene(au, ev), ev, au);
	});
	await paso('ORG-06', 'Invitar por correo y aceptar', async () => {
		const d0 = maxAudit();
		const s = await S('admin');
		CU.inv = { email: correo('invitado'), org: C.org_a, sede: C.sede_a };
		const inv = must(await api('POST', orgUrl('/colaborador-invitaciones'), { s, json: { email: CU.inv.email, roleCode: 'ADMINISTRATIVO', consultorioId: C.sede_a } }), 201, 'invitar');
		await esperarCorreos(CU.inv.email, 1, ES_INVITACION);
		must(await api('POST', orgUrl(`/colaborador-invitaciones/${inv.id}/resend`), { s }), 200, 'reenviar');
		const ms = await esperarCorreos(CU.inv.email, 2, ES_INVITACION);
		need(ms[1]);
		const nuevo = tokenDe(await textoCorreo(ms[0].ID)), viejo = tokenDe(await textoCorreo(ms[1].ID));
		const pv = await api('POST', '/api/v1/auth/invitations/preview', { json: { token: viejo } });
		ok(pv.status >= 400, 'el enlace anterior al reenvio ya no sirve', pv);
		const pn = await api('POST', '/api/v1/auth/invitations/preview', { json: { token: nuevo } });
		ok(pn.status === 200 && pn.cuerpo.email === CU.inv.email, 'la vista previa del enlace nuevo muestra su email', pn);
		pw[CU.inv.email] = PASSWORD;
		const ac = await api('POST', '/api/v1/auth/invitations/accept', { json: { token: nuevo, nombre: 'Invitado', apellido: 'QA', password: PASSWORD } });
		must(ac, 200, 'aceptar');
		C.minv = ac.cuerpo.membershipId;
		const cancel = must(await api('POST', orgUrl('/colaborador-invitaciones'), { s, json: { email: correo('cancelada'), roleCode: 'ADMINISTRATIVO', consultorioId: C.sede_a } }), 201, 'otra invitacion');
		const tc = await tokenCorreo(correo('cancelada'), ES_INVITACION);
		must(await api('POST', orgUrl(`/colaborador-invitaciones/${cancel.id}/cancel`), { s, json: { reason: 'Cancelada pre-QA' } }), 200, 'cancelar la pendiente');
		ok((await api('POST', '/api/v1/auth/invitations/preview', { json: { token: tc } })).status >= 400, 'el enlace de una invitacion cancelada es inutil');
		const f = q(`SELECT id, email_normalizado, role_code, estado, membership_id, aceptada_por_account_id, LENGTH(token_hash) AS largo_hash FROM colaborador_invitacion WHERE organization_id = @org_a AND id = ${inv.id}`);
		ok(f[0]?.estado === 'ACEPTADA' && f[0].membership_id && f[0].aceptada_por_account_id && f[0].largo_hash === '64', 'invitacion ACEPTADA con membership y cuenta', f);
		const ob = q(`SELECT tipo, estado FROM notification_outbox WHERE destinatario = ${lit(CU.inv.email)} ORDER BY id`);
		ok(ob.length >= 1 && ob.every((x) => x.tipo === 'INVITACION_COLABORADOR'), 'notificaciones INVITACION_COLABORADOR', ob);
		const c = q(`SELECT c.estado, m.role_code, m.estado AS membership FROM cuenta c JOIN membership m ON m.account_id = c.id WHERE c.email_normalizado = ${lit(CU.inv.email)} AND m.organization_id = @org_a`);
		ok(c[0]?.estado === 'ACTIVA' && c[0].role_code === 'ADMINISTRATIVO', 'cuenta ACTIVA con membership ADMINISTRATIVO', c);
		const si = await entrar(CU.inv.email, { org: C.org_a });
		eq(si.organizationId, C.org_a, 'el invitado entra con contexto en A');
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		for (const ev of ['INVITACION_EMITIDA', 'INVITACION_ACEPTADA', 'INVITACION_REENVIADA', 'INVITACION_CANCELADA']) ok(tiene(au, ev), ev, au);
	});
	await paso('ORG-07', 'Grants que la matriz admite', async () => {
		need(C.mprof);
		const d0 = maxAudit();
		const s = await S('admin');
		must(await grant(C.mprof, 'paciente:manage'), 201, 'otorgar paciente:manage al profesional');
		const g = q(`SELECT g.permission_code, g.active, g.reason FROM membership_grant g WHERE g.organization_id = @org_a AND g.membership_id = ${C.mprof}`);
		ok(g.some((x) => x.permission_code === 'PACIENTE_MANAGE' && x.active === '1'), 'fila PACIENTE_MANAGE activa', g);
		SES.prof = null;
		const alta = await api('POST', '/api/v1/personas', { s: await S('prof'), json: { nombre: 'Alta', apellido: `Del Profesional ${suf}`, tipoDocumento: 'DNI', numeroDocumento: `7${Date.now() % 10000000}` } });
		st(alta, 201, 'con el grant, el profesional da de alta una persona');
		const ot = async (rol) => (await api('GET', orgUrl(`/roles/${rol}/permisos-otorgables`), { s })).cuerpo;
		const lista = (x) => JSON.stringify((Array.isArray(x) ? x : x?.permissionCodes ?? []).map((p) => p.code ?? p.permissionCode ?? p).sort());
		eq(lista(await ot('PROFESIONAL')), JSON.stringify(['hc:read', 'hc:write', 'paciente:manage']), 'otorgables al PROFESIONAL');
		eq(lista(await ot('ADMINISTRATIVO')), JSON.stringify(['paciente:manage']), 'otorgables al ADMINISTRATIVO');
		eq(lista(await ot('PACIENTE')), '[]', 'otorgables al PACIENTE');
		st(await api('GET', orgUrl('/roles/PLATFORM_ADMIN/permisos-otorgables'), { s }), 400, 'PLATFORM_ADMIN');
		st(await api('GET', orgUrl('/roles/NO_EXISTE/permisos-otorgables'), { s }), 400, 'rol inexistente');
		must(await grant(C.mprof, 'hc:read'), 201, 'otorgar hc:read (redundante)');
		st(await api('DELETE', orgUrl(`/memberships/${C.mprof}/grants/hc:read?reason=${encodeURIComponent('Revocacion pre-QA')}`), { s }), 204, 'revocar hc:read');
		const r = q(`SELECT active, revoked_reason FROM membership_grant WHERE membership_id = ${C.mprof} AND permission_code = 'HC_READ'`);
		ok(r.length === 1 && r[0].active === '0' && r[0].revoked_reason, 'el grant revocado queda con active = 0 y motivo, no se borra', r);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(tiene(au, 'GRANT_ASSIGNED', (x) => x.reason === 'Grant pre-QA sintetico') && tiene(au, 'GRANT_REVOKED'), 'GRANT_ASSIGNED con reason y GRANT_REVOKED', au);
		manual('el selector de permisos adicionales de la pantalla');
	});
	await paso('ORG-08', 'Grants que la matriz prohibe', async () => {
		need(C.madm, C.mpac, C.mprof);
		for (const [mid, perm, rol] of [[C.madm, 'hc:write', 'ADMINISTRATIVO'], [C.madm, 'hc:read', 'ADMINISTRATIVO'], [C.mpac, 'paciente:manage', 'PACIENTE'], [C.mprof, 'auditoria:read-clinica', 'PROFESIONAL']]) {
			const r = await grant(mid, perm);
			st(r, 400, `${perm} al ${rol}`);
			tipo(r, 'permiso-no-otorgable-al-rol', `${perm} al ${rol}`);
			ok(r.cuerpo?.permissionCode === perm && r.cuerpo?.roleCode === rol, 'el cuerpo trae permissionCode y roleCode', r);
		}
		const f = q(`SELECT g.permission_code, m.role_code FROM membership_grant g JOIN membership m ON m.id = g.membership_id WHERE g.organization_id = @org_a
			AND NOT ((g.permission_code = 'PACIENTE_MANAGE' AND m.role_code IN ('PROFESIONAL','ORG_ADMIN','CONSULTORIO_ADMIN','ADMINISTRATIVO'))
			OR (g.permission_code = 'AUDITORIA_READ_CLINICA' AND m.role_code IN ('ORG_ADMIN','CONSULTORIO_ADMIN'))
			OR (g.permission_code = 'HC_READ' AND m.role_code IN ('ORG_ADMIN','CONSULTORIO_ADMIN','PROFESIONAL'))
			OR (g.permission_code = 'HC_WRITE' AND m.role_code IN ('CONSULTORIO_ADMIN','PROFESIONAL')))`);
		eq(f.length, 0, 'ninguna fila prohibida (critico si falla)', f);
	});
	await paso('ORG-09', 'Cambiar el rol de una membership', async () => {
		need(C.madm);
		const d0 = maxAudit();
		const s = await S('admin');
		must(await api('PATCH', orgUrl(`/memberships/${C.madm}`), { s, json: { roleCode: 'CONSULTORIO_ADMIN', reason: 'Cambio de rol pre-QA' } }), 200, 'a CONSULTORIO_ADMIN');
		must(await grant(C.madm, 'hc:write'), 201, 'hc:write admitido para CONSULTORIO_ADMIN');
		must(await api('PATCH', orgUrl(`/memberships/${C.madm}`), { s, json: { roleCode: 'ADMINISTRATIVO', reason: 'Vuelta de rol pre-QA' } }), 200, 'de vuelta a ADMINISTRATIVO');
		const g = (await api('GET', orgUrl(`/memberships/${C.madm}/grants`), { s })).cuerpo;
		const hw = (Array.isArray(g) ? g : g?.content ?? []).find((x) => x.permissionCode === 'hc:write');
		ok(hw && hw.vigenteConElRolActual === false, 'el grant hc:write sigue listado con vigenteConElRolActual = false', JSON.stringify(g));
		SES.adm = null;
		const f = q('SELECT m.role_code, m.version, g.permission_code, g.active FROM membership m LEFT JOIN membership_grant g ON g.membership_id = m.id WHERE m.id = @membership_administrativo');
		ok(f[0]?.role_code === 'ADMINISTRATIVO', 'rol ADMINISTRATIVO', f);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		eq(cuenta(au, 'MEMBERSHIP_ROLE_CHANGED'), 2, 'dos MEMBERSHIP_ROLE_CHANGED', au);
		ok(tiene(au, 'GRANT_ASSIGNED'), 'GRANT_ASSIGNED', au);
		nota('la lectura de historia clinica del administrativo (403) se verifica en CLI-08');
	});
	await paso('ORG-10', 'Suspender y reactivar', async () => {
		need(C.madm);
		const d0 = maxAudit();
		const a = await S('admin');
		const adm = await entrar(CU.adm.email, { org: C.org_a });
		const agenda = `/api/v1/consultorios/${C.sede_a}/turnos?fecha=${hoy()}`;
		st(await api('GET', agenda, { s: adm }), 200, 'el administrativo lee la agenda');
		must(await api('POST', orgUrl(`/memberships/${C.madm}/suspend`), { s: a, json: { reason: 'Suspension pre-QA' } }), 200, 'suspender');
		const r = await api('GET', agenda, { s: adm });
		ok(r.status === 404, 'pierde el acceso en el pedido siguiente, sin esperar a que venza el token (404 de contexto vencido)', r);
		eq(q1('SELECT estado FROM membership WHERE id = @membership_administrativo').estado, 'SUSPENDIDA', 'SUSPENDIDA');
		must(await api('POST', orgUrl(`/memberships/${C.madm}/reactivate`), { s: a, json: { reason: 'Reactivacion pre-QA' } }), 200, 'reactivar');
		st(await api('GET', agenda, { s: adm }), 200, 'reactivar devuelve el acceso con el mismo token');
		eq(q1('SELECT estado FROM membership WHERE id = @membership_administrativo').estado, 'ACTIVA', 'ACTIVA');
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(tiene(au, 'MEMBERSHIP_SUSPENDED', (x) => x.reason) && tiene(au, 'MEMBERSHIP_REACTIVATED', (x) => x.reason), 'MEMBERSHIP_SUSPENDED y MEMBERSHIP_REACTIVATED con reason', au);
	});
	await paso('ORG-12', 'Ultimo administrador y auto-revocacion', async () => {
		need(C.madmin);
		const s = await S('admin');
		for (const [m, ruta, json] of [['POST', `/memberships/${C.madmin}/suspend`, { reason: 'auto' }], ['POST', `/memberships/${C.madmin}/revoke`, { reason: 'auto' }], ['PATCH', `/memberships/${C.madmin}`, { roleCode: 'ADMINISTRATIVO', reason: 'auto' }]]) {
			st(await api(m, orgUrl(ruta), { s, json }), 409, `admin-a sobre si misma: ${m} ${ruta.split('/').pop()}`);
		}
		const f = q("SELECT COUNT(*) n FROM membership WHERE organization_id = @org_a AND role_code = 'ORG_ADMIN' AND estado = 'ACTIVA'");
		ok(n(f[0].n) >= 1, 'sigue habiendo un ORG_ADMIN vigente', f);
	});
	// preparacion (no es un paso de la guia): el segundo profesional de CLI-02 y CLI-17
	try {
		const r = await vincular(CU.prof2.email, 'PROFESIONAL');
		if (r.status === 201) { C.mprof2 = membership(CU.prof2.email); Object.assign(CU.prof2, { org: C.org_a, sede: C.sede_a }); }
	} catch { /* lo bloquea CLI-02 */ }
}

// 02 (segunda parte): lo que la guia corre despues de los circuitos clinicos y economicos
export async function c02b() {
	await paso('ORG-13', 'La matriz por rol', async () => {
		need(C.persona);
		const d0 = maxAudit();
		const roles = { admin: await S('admin'), adm: await S('adm'), prof: await S('prof'), pac: await S('pac') };
		const sede = `/api/v1/consultorios/${C.sede_a}`;
		const matriz = [
			['PATCH organizacion', 'PATCH', orgUrl(), { name: 'x', version: 0 }, { adm: 403, prof: 403, pac: 403 }],
			['colaboradores', 'GET', orgUrl('/memberships'), null, { admin: 200, adm: 200, prof: 200, pac: 403 }],
			['auditoria', 'GET', orgUrl(`/audit-events?${AUDIT_RANGO()}`), null, { admin: 200, adm: 403, prof: 403, pac: 403 }],
			['padron', 'GET', '/api/v1/personas?size=1', null, { admin: 200, adm: 200, prof: 200, pac: 403 }],
			['agenda', 'GET', `${sede}/turnos?fecha=${hoy()}`, null, { admin: 200, adm: 200, prof: 200, pac: 403 }],
			['cobros', 'GET', `${sede}/cobros?personaId=${C.persona}`, null, { admin: 200, adm: 200, prof: 403, pac: 403 }],
			['caja', 'GET', `${sede}/caja/jornadas`, null, { admin: 200, adm: 200, prof: 403, pac: 403 }],
			['presentaciones', 'GET', `${sede}/presentaciones`, null, { admin: 200, adm: 200, prof: 403, pac: 403 }],
			// DP-36 (U-07, U-08): convenio:read y catalogo:read son del personal; el PACIENTE recibe 403.
			['financiadores (convenio:read, DP-36)', 'GET', '/api/v1/financiadores', null, { admin: 200, adm: 200, prof: 200, pac: 403 }],
			['catalogo clinico (catalogo:read, DP-36)', 'GET', '/api/v1/catalogos/practicas', null, { admin: 200, adm: 200, prof: 200, pac: 403 }],
			['alta de financiador', 'POST', '/api/v1/financiadores', { codigo: `NO${suf}`.toUpperCase(), nombre: 'No', tipo: 'OTRO' }, { adm: 403, prof: 403, pac: 403 }],
			['reportes', 'GET', `${sede}/reportes/OPERATIVO?desde=${hoy()}&hasta=${hoy()}`, null, { admin: 200, adm: 200, prof: 200, pac: 403 }],
		];
		let prohibidos = 0;
		for (const [nombre, m, ruta, json, esperado] of matriz) {
			for (const [rol, cod] of Object.entries(esperado)) {
				const r = await api(m, ruta, { s: roles[rol], json: json ?? undefined });
				st(r, cod, `${nombre} con ${rol}`);
				if (r.status === 403) prohibidos++;
			}
		}
		const pd = auditDesde(d0, "AND organization_id = @org_a AND event_type = 'PERMISSION_DENIED'");
		ok(pd.length === prohibidos && pd.every((x) => det(x).permissionCode && det(x).denialKind), `una fila PERMISSION_DENIED por cada 403 (${prohibidos}) con permissionCode y denialKind`, pd);
		manual('recorrer el menu de cada rol; lo que no puede, no aparece o lleva a /sin-permiso');
	});
	await paso('ORG-14', 'Auditoria por pantalla y redaccion de lo clinico', async () => {
		need(C.historia);
		const a = await S('admin');
		const crudo = q("SELECT id, reason FROM audit_event WHERE organization_id = @org_a AND event_type = 'HISTORIA_CLINICA_ACCESSED' AND reason IS NOT NULL ORDER BY id DESC LIMIT 1");
		need(crudo[0]);
		const ver = async () => {
			const r = must(await api('GET', orgUrl(`/audit-events?entityType=HistoriaClinica&entityId=${C.historia}&size=100`), { s: a }), 200, 'auditoria filtrada por HistoriaClinica');
			return (r.content ?? []).find((x) => x.id === n(crudo[0].id));
		};
		const sin = await ver();
		ok(sin && sin.reason !== crudo[0].reason, 'sin auditoria:read-clinica, la reason clinica se ve redactada', JSON.stringify(sin));
		const { grant: g } = await import('./02-organizacion.mjs');
		must(await g(C.madmin, 'auditoria:read-clinica'), 201, 'otorgar auditoria:read-clinica');
		const con = await ver();
		ok(con && con.reason === crudo[0].reason, 'con el grant se ve', JSON.stringify(con));
		const r = await api('GET', `/api/v1/organizations/${C.org_b}/audit-events?${AUDIT_RANGO()}`, { s: a });
		st(r, 404, 'la auditoria de B no es alcanzable desde A');
	});
	await paso('ORG-15', 'Notificaciones fallidas y reintento por fila', async () => {
		need(C.org_a);
		const d0 = maxAudit();
		const s = await S('admin');
		const inv = (email) => api('POST', orgUrl('/colaborador-invitaciones'), { s, json: { email, roleCode: 'ADMINISTRATIVO', consultorioId: C.sede_a } });
		docker('stop', MAILC);
		let cancelada, entregable;
		try {
			cancelada = must(await inv(correo('notif')), 201, 'invitar con el correo abajo');
			entregable = must(await inv(correo('notif2')), 201, 'otra invitacion con el correo abajo');
			let ob = [];
			for (let i = 0; i < 40; i++) {
				ob = q(`SELECT id, destinatario, estado, intentos, proxima_ejecucion_en, error_sanitizado FROM notification_outbox WHERE organization_id = @org_a AND destinatario IN (${lit(correo('notif'))}, ${lit(correo('notif2'))}) ORDER BY id`);
				if (ob.length === 2 && ob.every((x) => x.estado === 'REINTENTABLE')) break;
				await dormir(3000);
			}
			ok(ob.length === 2 && ob.every((x) => x.estado === 'REINTENTABLE' && n(x.intentos) >= 1 && x.proxima_ejecucion_en), 'las dos quedan REINTENTABLE con intentos y proxima ejecucion', ob);
			ok(ob.every((x) => x.error_sanitizado && !/preqa|mailpit|password|token|https?:/i.test(x.error_sanitizado)), 'error_sanitizado sin host, credenciales ni enlace', ob);
			const l = must(await api('GET', orgUrl('/notifications?estado=REINTENTABLE'), { s }), 200, 'listar REINTENTABLE');
			ok((l.content ?? []).some((x) => x.id === n(ob[0]?.id) && x.reintentable === false), 'aparece, sin reintento', JSON.stringify(l.content).slice(0, 400));
			tipo(await api('POST', orgUrl(`/notifications/${ob[0]?.id}/retry`), { s }), 'conflict', 'reintentar una que no esta FALLIDA ni AGOTADA');
			must(await api('POST', orgUrl(`/colaborador-invitaciones/${cancelada.id}/cancel`), { s, json: { reason: 'Cancelada mientras esperaba (pre-QA)' } }), 200, 'cancelar la primera mientras espera');
		} finally {
			docker('start', MAILC);
		}
		for (let i = 0; i < 60; i++) { try { if ((await fetch(`${MAILPIT}/api/v1/info`)).ok) break; } catch { /* arrancando */ } await dormir(1000); }
		let ob = [];
		for (let i = 0; i < 75; i++) {
			ob = q(`SELECT id, destinatario, estado, intentos, enviado_en, error_sanitizado FROM notification_outbox WHERE organization_id = @org_a AND destinatario IN (${lit(correo('notif'))}, ${lit(correo('notif2'))}) ORDER BY id`);
			if (ob.every((x) => ['ENVIADA', 'AGOTADA'].includes(x.estado))) break;
			await dormir(3000);
		}
		const [nc, ne] = ob;
		ok(nc?.estado === 'AGOTADA' && nc.error_sanitizado === 'No se envia: la invitacion fue cancelada' && nc.enviado_en === null, 'la de la invitacion cancelada queda AGOTADA con el motivo y sin enviar (regresion de #100 si sale)', ob);
		ok(ne?.estado === 'ENVIADA' && ne.error_sanitizado === null, 'la otra sale con el correo arriba y no arrastra el error anterior (regresion de #100)', ob);
		eq((await correos(correo('notif'))).length, 0, 'Mailpit no recibe el correo de la cancelada');
		ok((await esperarCorreos(correo('notif2'), 1, ES_INVITACION, 20)).length === 1, 'Mailpit recibe el de la otra');
		st(await api('POST', orgUrl(`/notifications/${nc?.id}/retry`), { s }), 204, 'reintentar la AGOTADA');
		let r = [];
		for (let i = 0; i < 30; i++) { r = q(`SELECT estado, error_sanitizado, enviado_en FROM notification_outbox WHERE id = ${nc?.id}`); if (r[0]?.estado === 'AGOTADA') break; await dormir(2000); }
		ok(r[0]?.estado === 'AGOTADA' && r[0].enviado_en === null, 'reintentar la de la cancelada la vuelve a dejar igual', r);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'NOTIFICACION_REINTENTADA', (x) => x.entity_type === 'Notificacion'), 'NOTIFICACION_REINTENTADA');
		manual('llegar a AGOTADA por backoff (1, 5, 15 y 60 min: ~81 minutos con el correo abajo) y reintentar una entregable desde la pantalla');
	});
	await paso('ORG-16', 'Cada invitacion con su propio enlace', async () => {
		const d0 = maxAudit();
		const s = await S('admin');
		const e1 = correo('inv1'), e2 = correo('inv2');
		const [i1, i2] = await Promise.all([e1, e2].map((email) => api('POST', orgUrl('/colaborador-invitaciones'), { s, json: { email, roleCode: 'ADMINISTRATIVO', consultorioId: C.sede_a } })));
		must(i1, 201, 'inv1');
		must(i2, 201, 'inv2');
		const t1 = await tokenCorreo(e1, ES_INVITACION), t2 = await tokenCorreo(e2, ES_INVITACION);
		const pv = async (t) => api('POST', '/api/v1/auth/invitations/preview', { json: { token: t } });
		ok((await pv(t1)).cuerpo?.email === e1 && (await pv(t2)).cuerpo?.email === e2, 'cada enlace muestra su email');
		must(await api('POST', orgUrl(`/colaborador-invitaciones/${i1.cuerpo.id}/resend`), { s }), 200, 'reenviar la primera');
		const t1b = await tokenCorreo(e1, ES_INVITACION, 2, 0);
		ok((await pv(t1)).status === 404 && (await pv(t1b)).cuerpo?.email === e1, 'el original ya no sirve (404) y el nuevo si');
		const f = q(`SELECT n.id, n.referencia_token_id, n.clave_idempotente, n.referencia_token_id = n.clave_idempotente AS ok FROM notification_outbox n
			WHERE n.organization_id = @org_a AND n.tipo = 'INVITACION_COLABORADOR' AND n.destinatario IN (${lit(e1)}, ${lit(e2)}) ORDER BY n.id`);
		ok(f.length === 3 && f.every((x) => /^invitacion:\d+:\d+$/.test(x.referencia_token_id ?? '') && x.ok === '1') && new Set(f.map((x) => x.referencia_token_id)).size === 3, 'referencia invitacion:<id>:<ms>, igual a la clave e irrepetible (regresion de #111 si es solo un numero)', f);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(cuenta(au, 'INVITACION_EMITIDA') === 2 && tiene(au, 'INVITACION_REENVIADA'), 'INVITACION_EMITIDA x2 e INVITACION_REENVIADA', au);
	});
	await paso('ORG-17', 'Una suspension vale desde el pedido siguiente', async () => {
		need(C.madm, C.mprof);
		const a = await S('admin');
		const adm = await entrar(CU.adm.email, { org: C.org_a });
		st(await api('GET', '/api/v1/personas?size=1', { s: adm }), 200, 'antes de suspender');
		must(await api('POST', orgUrl(`/memberships/${C.madm}/suspend`), { s: a, json: { reason: 'ORG-17 pre-QA' } }), 200, 'suspender');
		st(await api('GET', '/api/v1/personas?size=1', { s: adm }), 404, 'buscar en el padron con el mismo token (critico si da 200)');
		st(await api('POST', '/api/v1/personas', { s: adm, json: { nombre: 'No', apellido: 'Entra', tipoDocumento: 'DNI', numeroDocumento: `8${String(Date.now()).slice(-7)}` } }), 404, 'alta de persona');
		const altas = q("SELECT COUNT(*) n FROM persona p WHERE p.organization_id = @org_a AND p.created_at >= (SELECT MAX(occurred_at) FROM audit_event WHERE organization_id = @org_a AND event_type = 'MEMBERSHIP_SUSPENDED')");
		eq(altas[0].n, 0, 'ninguna alta despues de suspender', altas);
		must(await api('POST', orgUrl(`/memberships/${C.madm}/reactivate`), { s: a, json: { reason: 'ORG-17 pre-QA' } }), 200, 'reactivar');
		st(await api('GET', '/api/v1/personas?size=1', { s: adm }), 200, 'el pedido siguiente vuelve a 200');
		const prof = await entrar(CU.prof.email, { org: C.org_a, sede: C.sede_a });
		st(await api('POST', '/api/v1/personas', { s: prof, json: { nombre: 'Con', apellido: `Grant ${suf}`, tipoDocumento: 'DNI', numeroDocumento: `81${String(Date.now()).slice(-6)}` } }), 201, 'alta del profesional con el grant (ORG-07)');
		st(await api('DELETE', orgUrl(`/memberships/${C.mprof}/grants/paciente:manage?reason=ORG-17`), { s: a }), 204, 'revocar el grant');
		st(await api('POST', '/api/v1/personas', { s: prof, json: { nombre: 'Sin', apellido: `Grant ${suf}`, tipoDocumento: 'DNI', numeroDocumento: `82${String(Date.now()).slice(-6)}` } }), 403, 'la siguiente alta con el mismo token');
		manual('la sesion no se cierra en la pantalla');
	});
}
