import { randomUUID } from 'node:crypto';
import { APIC, MAILC, RED, API, MAILPIT, P, MYSQL, DB, PLATAFORMA, AQUI, PASSWORD, PASSWORD2, SALIDA, SALIDA_JSON, SOLO, suf, dormir, UA, inicioCorrida, Corte, Bloqueo, R, cur, CIRC, paso, ultimaSql, ok, eq, st, tipo, must, need, nota, manual, soloManual, n, SECRETO, limpiar, descr, api, galletas, cookieRt, V, lit, unesc, q, q1, fmtSql, ahoraDb, maxAudit, auditDesde, tiene, cuenta, det, docker, correos, textoCorreo, esperarCorreos, tokenDe, tokenCorreo, ES_ACTIVACION, ES_INVITACION, pw, correo, registrar, activar, login, entrar, CU, SES, S, Splat, hoy, horaLocal, fechaLocal, instanteLocal, C, usados, agenda, slot, reservar, recepcionHastaLlamado, ses, iniciarSesion, tratar, cerrarSesion, atencionCompleta, PNG, multipart, sha256, J, ORIGEN, GUIA, guia, ZONA, setZona, AUDIT_RANGO } from './lib.mjs';

// 10 · Plataforma (el resto: PLA-01/02 corren al inicio, PLA-04 en 03 y PLA-06 en 02)
const org = (id, p = '') => `/api/v1/organizations/${id}${p}`;

export async function c10resto() {
	await paso('PLA-03', 'Sin soporte, la plataforma no ve los datos de un centro', async () => {
		need(C.org_a);
		const d0 = maxAudit();
		const s = await Splat();
		st(await api('GET', org(C.org_a), { s }), 403, 'la organizacion A');
		st(await api('GET', org(C.org_a, '/memberships'), { s }), 403, 'sus memberships');
		st(await api('GET', '/api/v1/personas', { s }), 403, 'el padron (sin contexto de tenant)');
		eq(cuenta(auditDesde(d0, 'AND organization_id = @org_a'), 'SUPPORT_ACCESS_USED'), 0, 'ningun SUPPORT_ACCESS_USED');
	});
	await paso('PLA-05', 'Alta de una organizacion por plataforma', async () => {
		const d0 = maxAudit();
		const s = await Splat();
		const slug = `centro-qa-c-${suf}`;
		const k = randomUUID();
		const cuerpo = { name: `Centro QA C ${suf}`, slug, planCode: 'BASICO' };
		const r1 = must(await api('POST', '/api/v1/organizations', { s, json: cuerpo, headers: { 'Idempotency-Key': k } }), 201, 'alta');
		const r2 = await api('POST', '/api/v1/organizations', { s, json: cuerpo, headers: { 'Idempotency-Key': k } });
		ok([200, 201].includes(r2.status) && r2.cuerpo?.id === r1.id, 'la misma clave y el mismo cuerpo: la misma respuesta', r2);
		const r3 = await api('POST', '/api/v1/organizations', { s, json: cuerpo, headers: { 'Idempotency-Key': randomUUID() } });
		ok(r3.status === 409 && r3.tipo, 'el mismo slug con otra clave: 409 tipado', r3);
		const f = q(`SELECT o.id, o.slug, o.timezone, s.status, p.code FROM organization o JOIN subscription s ON s.organization_id = o.id JOIN plan p ON p.id = s.plan_id WHERE o.slug = ${lit(slug)}`);
		ok(f.length === 1 && f[0].status === 'ACTIVA' && f[0].code === 'BASICO', 'una organizacion ACTIVA en BASICO', f);
		ok(tiene(auditDesde(d0, `AND organization_id = ${r1.id}`), 'ORGANIZATION_CREATED'), 'ORGANIZATION_CREATED');
	});
	await paso('PLA-07', 'Acceso de soporte con rastro en el centro', async () => {
		need(C.org_a, C.org_b);
		const d0 = maxAudit();
		const s = await Splat();
		const sa = must(await api('POST', `/api/v1/platform/organizations/${C.org_a}/support-access`, { s, json: { reason: 'QA G-12: ticket de soporte sintetico', durationMinutes: 30 } }), 201, 'abrir soporte');
		st(await api('GET', org(C.org_a), { s }), 200, 'la organizacion A con soporte');
		st(await api('GET', org(C.org_a, '/memberships'), { s }), 200, 'sus memberships');
		st(await api('GET', org(C.org_b), { s }), 403, 'B (el soporte es de A)');
		st(await api('GET', '/api/v1/personas', { s }), 403, 'el padron (sigue sin contexto)');
		st(await api('DELETE', `/api/v1/platform/support-access/${sa.id}`, { s }), [200, 204], 'revocar');
		st(await api('GET', org(C.org_a), { s }), 403, 'otra vez 403');
		const f = q(`SELECT id, reason, valid_from, valid_until, revoked_at, revoked_by_account_id FROM support_access WHERE id = ${sa.id}`);
		ok(f[0]?.reason && f[0].valid_until && f[0].revoked_at, 'el acceso con motivo, vencimiento y revocado', f);
		const au = auditDesde(d0, "AND organization_id = @org_a AND event_type LIKE 'SUPPORT_ACCESS%'");
		ok(tiene(au, 'SUPPORT_ACCESS_GRANTED', (x) => x.reason) && tiene(au, 'SUPPORT_ACCESS_REVOKED'), 'GRANTED con el motivo y REVOKED en la auditoria de A', au);
		eq(au.filter((x) => x.event_type === 'SUPPORT_ACCESS_USED' && det(x).permissionCode).length, 2, 'un SUPPORT_ACCESS_USED por cada lectura, con permissionCode', au);
		manual('un acceso vencido deja de servir solo (necesita esperar el vencimiento)');
	});
	await paso('PLA-08', 'Suspender la suscripcion de un centro', async () => {
		need(C.org_b);
		const d0 = maxAudit();
		const s = await Splat();
		must(await api('POST', org(C.org_b, '/subscription/transitions'), { s, json: { toStatus: 'SUSPENDIDA', reason: 'QA G-12: falta de pago sintetica' } }), 200, 'suspender B');
		const t1 = ahoraDb();
		const b = await S('b');
		st(await api('GET', '/api/v1/personas?size=1', { s: b }), 200, 'el padron se lee');
		tipo(await api('POST', '/api/v1/personas', { s: b, json: { nombre: 'No', apellido: 'Deberia', tipoDocumento: 'DNI', numeroDocumento: `6${String(Date.now()).slice(-7)}` } }), 'subscription-suspended', 'dar de alta una persona');
		const t2 = ahoraDb();
		must(await api('POST', org(C.org_b, '/subscription/transitions'), { s, json: { toStatus: 'ACTIVA', reason: 'QA G-12: regulariza' } }), 200, 'reactivar');
		eq(q1('SELECT status FROM subscription WHERE organization_id = @org_b').status, 'ACTIVA', 'ACTIVA de nuevo');
		const t = q('SELECT from_status, to_status, reason FROM subscription_transition WHERE organization_id = @org_b ORDER BY id');
		ok(t.some((x) => x.to_status === 'SUSPENDIDA' && x.reason) && t.some((x) => x.from_status === 'SUSPENDIDA' && x.to_status === 'ACTIVA' && x.reason), 'dos transiciones con motivo', t);
		eq(q1(`SELECT COUNT(*) n FROM persona WHERE organization_id = @org_b AND created_at >= ${lit(t1)} AND created_at <= ${lit(t2)}`).n, 0, 'ninguna persona creada durante la suspension');
		eq(cuenta(auditDesde(d0, 'AND organization_id = @org_b'), 'SUBSCRIPTION_TRANSITIONED'), 2, 'dos SUBSCRIPTION_TRANSITIONED en B');
	});
	await paso('PLA-09', 'Rol de plataforma y bloqueo de cuentas', async () => {
		need(CU.inv);
		const d0 = maxAudit();
		const s = await Splat();
		const cid = n(q1(`SELECT id FROM cuenta WHERE email_normalizado = ${lit(CU.inv.email)}`).id);
		const inv = await entrar(CU.inv.email, { org: C.org_a });
		st(await api('GET', '/api/v1/personas?size=1', { s: inv }), 200, 'el invitado lee el padron de A');
		const r = must(await api('POST', '/api/v1/platform/roles', { s, json: { accountId: cid, reason: 'QA G-12: segundo operador' } }), 201, 'otorgar el rol de plataforma');
		const conRol = await api('GET', '/api/v1/personas?size=1', { s: inv });
		nota(`con el rol de plataforma otorgado, el padron de A con su token responde ${conRol.status} (conocido: pierde los permisos de tenant)`);
		st(await api('DELETE', `/api/v1/platform/roles/${r.id}?reason=QA`, { s }), 204, 'revocar el rol');
		must(await api('POST', `/api/v1/accounts/${cid}/block`, { s, json: { reason: 'QA G-12: bloqueo sintetico' } }), 200, 'bloquear');
		st(await login(CU.inv.email), 401, 'bloqueada no ingresa');
		st(await api('POST', '/api/v1/auth/refresh', { cookie: inv.cookie, headers: ORIGEN }), 401, 'su sesion muere al refrescar');
		const c = q(`SELECT estado, bloqueada_en, bloqueada_motivo FROM cuenta WHERE id = ${cid}`);
		ok(c[0].estado === 'BLOQUEADA' && c[0].bloqueada_motivo, 'BLOQUEADA con motivo', c);
		const rt = q(`SELECT revocado_en, motivo_revocacion FROM refresh_token WHERE cuenta_id = ${cid} ORDER BY id DESC LIMIT 3`);
		ok(rt.some((x) => x.motivo_revocacion === 'BLOQUEO') && !rt.some((x) => x.revocado_en === null), 'los refresh vivos revocados con BLOQUEO', rt);
		must(await api('POST', `/api/v1/accounts/${cid}/unblock`, { s, json: { reason: 'QA G-12: desbloqueo' } }), 200, 'desbloquear');
		eq(q1(`SELECT estado FROM cuenta WHERE id = ${cid}`).estado, 'ACTIVA', 'ACTIVA');
		const pr = q(`SELECT active, revoked_reason FROM platform_role WHERE id = ${r.id}`);
		ok(pr[0].active === '0' && pr[0].revoked_reason, 'el rol con active = 0 y motivo', pr);
		const au = auditDesde(d0);
		for (const e of ['PLATFORM_ROLE_GRANTED', 'PLATFORM_ROLE_REVOKED', 'CUENTA_BLOQUEADA', 'CUENTA_DESBLOQUEADA']) ok(tiene(au, e), e, au);
	});
}
