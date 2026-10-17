import { randomUUID } from 'node:crypto';
import { APIC, MAILC, RED, API, MAILPIT, P, MYSQL, DB, PLATAFORMA, AQUI, PASSWORD, PASSWORD2, SALIDA, SALIDA_JSON, SOLO, suf, dormir, UA, inicioCorrida, Corte, Bloqueo, R, cur, CIRC, paso, ultimaSql, ok, eq, st, tipo, must, need, nota, manual, soloManual, n, SECRETO, limpiar, descr, api, galletas, cookieRt, V, lit, unesc, q, q1, fmtSql, ahoraDb, maxAudit, auditDesde, tiene, cuenta, det, docker, correos, textoCorreo, esperarCorreos, tokenDe, tokenCorreo, ES_ACTIVACION, ES_INVITACION, pw, correo, registrar, activar, login, entrar, CU, SES, S, Splat, hoy, horaLocal, fechaLocal, instanteLocal, C, usados, agenda, slot, reservar, recepcionHastaLlamado, ses, iniciarSesion, tratar, cerrarSesion, atencionCompleta, PNG, multipart, sha256, J, ORIGEN, GUIA, guia, ZONA, setZona, AUDIT_RANGO } from './lib.mjs';

// 11 · Aislamiento entre organizaciones y rol PACIENTE
export async function c11() {
	await paso('AIS-01', 'El token de B contra los ids de A', async () => {
		need(C.persona, C.sesion, C.cobro, C.presentacion, C.historia);
		const b = await S('b');
		// datos minimos en B: una persona con perfil y un cobro por transferencia (sin caja)
		const pb = must(await api('POST', '/api/v1/personas', { s: b, json: { nombre: 'Persona', apellido: `De B ${suf}`, tipoDocumento: 'DNI', numeroDocumento: `9${String(Date.now()).slice(-7)}` } }), 201, 'persona de B');
		await api('POST', `/api/v1/personas/${pb.id}/perfil-paciente`, { s: b, json: {} });
		C.persona_b = V.persona_b = pb.id;
		must(await api('POST', `/api/v1/consultorios/${C.sede_b}/cobros`, { s: b, json: { personaId: pb.id, total: 100, anticipo: 100, moneda: 'ARS', medios: [{ medio: 'TRANSFERENCIA', importe: 100 }], idempotencyKey: randomUUID() } }), 201, 'cobro de B');
		const rutas = [`/api/v1/personas/${C.persona}`, `/api/v1/personas/${C.persona}/coberturas`, `/api/v1/personas/${C.persona}/autorizaciones`, `/api/v1/personas/${C.persona}/resumen`, `/api/v1/personas/${C.persona}/adjuntos`,
			`/api/v1/consultorios/${C.sede_a}/turnos/${C.turno_os}`, `/api/v1/consultorios/${C.sede_a}/sesiones/${C.sesion}`, `/api/v1/consultorios/${C.sede_a}/obligaciones?personaId=${C.persona}`,
			`/api/v1/consultorios/${C.sede_a}/cobros/${C.cobro}`, `/api/v1/consultorios/${C.sede_a}/presentaciones/${C.presentacion}`, `/api/v1/consultorios/${C.sede_a}/caja/jornadas`,
			`/api/v1/consultorios/${C.sede_a}/reportes/ECONOMICO?desde=2026-10-01&hasta=2026-10-31`, `/api/v1/autorizaciones/${C.autorizacion}/saldo`, `/api/v1/historias-clinicas/${C.historia}/timeline`,
			`/api/v1/organizations/${C.org_a}`, `/api/v1/organizations/${C.org_a}/memberships`];
		for (const r of rutas) {
			const x = await api('GET', r, { s: b, headers: { 'X-Justificacion-Acceso': 'QA aislamiento' } });
			ok(x.status === 404 && x.tipo === 'not-found', `404 not-found: ${r} (200 es critico, 403 alto)`, x);
		}
		const f = q('SELECT a.id, a.event_type FROM audit_event a WHERE a.organization_id = @org_a AND a.occurred_at >= @desde AND a.actor_account_id = (SELECT id FROM cuenta WHERE email_normalizado = @email_admin_b)');
		eq(f.length, 0, 'ninguna accion de admin-b quedo registrada en A', f);
		manual('AIS-02: lo mismo pegando las URLs en la barra del navegador');
	});
	await paso('AIS-03', 'Una cuenta administradora en las dos organizaciones (#70)', async () => {
		need(C.org_b, C.sede_b);
		const d0 = maxAudit();
		must(await api('POST', '/api/v1/memberships', { s: await S('b'), json: { email: CU.admin.email, roleCode: 'ORG_ADMIN', reason: 'QA #70' } }), 201, 'admin-b vincula a admin-a como ORG_ADMIN de B');
		const mb = n(q1('SELECT m.id FROM membership m JOIN cuenta c ON c.id = m.account_id WHERE m.organization_id = @org_b AND c.email_normalizado = @email_admin_b AND m.is_founder = 1').id);
		const aa = await entrar(CU.admin.email, { org: C.org_a });
		const rutas = [`/api/v1/organizations/${C.org_b}`, `/api/v1/organizations/${C.org_b}/audit-events?${AUDIT_RANGO()}`, `/api/v1/organizations/${C.org_b}/memberships`, `/api/v1/organizations/${C.org_b}/memberships/${mb}`, `/api/v1/organizations/${C.org_b}/consultorios/${C.sede_b}`];
		for (const r of rutas) st(await api('GET', r, { s: aa }), 404, `con el contexto de A: ${r}`);
		st(await api('POST', `/api/v1/organizations/${C.org_b}/memberships/${mb}/suspend`, { s: aa, json: { reason: 'QA #70: no deberia pasar' } }), 404, 'suspender un colaborador de B con el contexto de A');
		eq(q1(`SELECT estado FROM membership WHERE id = ${mb}`).estado, 'ACTIVA', 'sigue ACTIVA');
		eq(cuenta(auditDesde(d0, 'AND organization_id = @org_b'), 'MEMBERSHIP_SUSPENDED'), 0, 'ninguna suspension en B');
		const ab = await entrar(CU.admin.email, { org: C.org_b });
		for (const r of rutas) st(await api('GET', r, { s: ab }), 200, `con el contexto de B: ${r}`);
		C.aa = aa;
	});
	await paso('AIS-04', 'Escribir con ids de la otra organizacion', async () => {
		need(C.persona_b, C.aa);
		const aa = await entrar(CU.admin.email, { org: C.org_a });
		const cob = { personaId: C.persona_b, total: 100, anticipo: 100, moneda: 'ARS', medios: [{ medio: 'TRANSFERENCIA', importe: 100 }] };
		st(await api('POST', `/api/v1/consultorios/${C.sede_a}/cobros`, { s: aa, json: { ...cob, idempotencyKey: randomUUID() } }), 404, 'cobro en A a la persona de B');
		st(await reservar(aa, C.persona_b, await slot({ dia: 2 })), 404, 'turno en A para la persona de B');
		st(await api('POST', `/api/v1/personas/${C.persona_b}/coberturas`, { s: aa, json: { tipo: 'PARTICULAR', vigenciaDesde: '2026-01-01' } }), 404, 'cobertura a la persona de B');
		st(await api('POST', `/api/v1/consultorios/${C.sede_b}/cobros`, { s: aa, json: { ...cob, idempotencyKey: randomUUID() } }), 404, 'cobro en la sede de B con el contexto de A');
		const f = q(`SELECT 'cobro' AS tabla, COUNT(*) AS filas FROM cobro WHERE persona_id = @persona_b AND organization_id <> @org_b
			UNION ALL SELECT 'turno', COUNT(*) FROM turno WHERE persona_id = @persona_b AND organization_id <> @org_b
			UNION ALL SELECT 'cobertura', COUNT(*) FROM cobertura_paciente WHERE persona_id = @persona_b AND organization_id <> @org_b`);
		ok(f.every((x) => x.filas === '0'), 'nada escrito', f);
	});
	await paso('AIS-05', 'Barrido de integridad entre tenants', async () => {
		const f = q(`SELECT 'turno->persona' AS relacion, COUNT(*) AS cruzadas FROM turno t JOIN persona p ON p.id = t.persona_id WHERE t.organization_id <> p.organization_id
UNION ALL SELECT 'turno->oferta', COUNT(*) FROM turno t JOIN oferta_servicio_consultorio o ON o.id = t.oferta_id WHERE t.organization_id <> o.organization_id
UNION ALL SELECT 'turno->sede', COUNT(*) FROM turno t JOIN consultorio c ON c.id = t.consultorio_id WHERE t.organization_id <> c.organization_id
UNION ALL SELECT 'sesion->turno', COUNT(*) FROM sesion s JOIN turno t ON t.id = s.turno_id WHERE s.organization_id <> t.organization_id
UNION ALL SELECT 'sesion->historia', COUNT(*) FROM sesion s JOIN historia_clinica h ON h.id = s.historia_clinica_id WHERE s.organization_id <> h.organization_id
UNION ALL SELECT 'historia->persona', COUNT(*) FROM historia_clinica h JOIN persona p ON p.id = h.persona_id WHERE h.organization_id <> p.organization_id
UNION ALL SELECT 'obligacion->sesion', COUNT(*) FROM obligacion o JOIN sesion s ON s.id = o.sesion_id WHERE o.organization_id <> s.organization_id
UNION ALL SELECT 'obligacion->persona', COUNT(*) FROM obligacion o JOIN persona p ON p.id = o.persona_id WHERE o.organization_id <> p.organization_id
UNION ALL SELECT 'cobro->persona', COUNT(*) FROM cobro c JOIN persona p ON p.id = c.persona_id WHERE c.organization_id <> p.organization_id
UNION ALL SELECT 'imputacion->obligacion', COUNT(*) FROM cobro_imputacion i JOIN obligacion o ON o.id = i.obligacion_id WHERE i.organization_id <> o.organization_id
UNION ALL SELECT 'imputacion->cobro', COUNT(*) FROM cobro_imputacion i JOIN cobro c ON c.id = i.cobro_id WHERE i.organization_id <> c.organization_id
UNION ALL SELECT 'movimiento->jornada', COUNT(*) FROM movimiento_caja m JOIN jornada_caja j ON j.id = m.jornada_caja_id WHERE m.organization_id <> j.organization_id
UNION ALL SELECT 'item->obligacion', COUNT(*) FROM presentacion_item i JOIN obligacion o ON o.id = i.obligacion_id WHERE i.organization_id <> o.organization_id
UNION ALL SELECT 'presentacion->financiador', COUNT(*) FROM presentacion p JOIN financiador f ON f.id = p.financiador_id WHERE p.organization_id <> f.organization_id
UNION ALL SELECT 'cobertura->persona', COUNT(*) FROM cobertura_paciente c JOIN persona p ON p.id = c.persona_id WHERE c.organization_id <> p.organization_id
UNION ALL SELECT 'autorizacion->cobertura', COUNT(*) FROM autorizacion a JOIN cobertura_paciente c ON c.id = a.cobertura_id WHERE a.organization_id <> c.organization_id
UNION ALL SELECT 'convenio->financiador', COUNT(*) FROM convenio c JOIN financiador f ON f.id = c.financiador_id WHERE c.organization_id <> f.organization_id
UNION ALL SELECT 'membership->sede', COUNT(*) FROM membership m JOIN consultorio c ON c.id = m.consultorio_id WHERE m.organization_id <> c.organization_id
UNION ALL SELECT 'recepcion->turno', COUNT(*) FROM recepcion r JOIN turno t ON t.id = r.turno_id WHERE r.organization_id <> t.organization_id
UNION ALL SELECT 'adjunto->persona', COUNT(*) FROM adjunto_administrativo a JOIN persona p ON p.id = a.persona_id WHERE a.organization_id <> p.organization_id`);
		ok(f.length === 20 && f.every((x) => x.cruzadas === '0'), '0 filas cruzadas en las 20 relaciones (critico si falla)', f);
	});
	await paso('PAC-01', 'El rol PACIENTE no ve el padron (DP-22)', async () => {
		need(C.persona);
		const d0 = maxAudit();
		const pa = await S('pac');
		for (const r of ['/api/v1/personas?size=5', '/api/v1/personas?q=AF-QA-001', `/api/v1/personas/${C.persona}`, `/api/v1/personas/${C.persona}/resumen`, `/api/v1/personas/${C.persona}/coberturas`,
			`/api/v1/personas/${C.persona}/cobertura-aplicable?ofertaId=${C.oferta}`, `/api/v1/personas/${C.persona}/ordenes`, `/api/v1/personas/${C.persona}/autorizaciones`,
			`/api/v1/personas/${C.persona}/elegibilidad?coberturaId=${C.cobertura}&practicaId=${C.practica}`, `/api/v1/personas/${C.persona}/adjuntos`, `/api/v1/autorizaciones/${C.autorizacion}/saldo`]) {
			st(await api('GET', r, { s: pa }), 403, `paciente ${r} (200 es critico)`);
		}
		const f = auditDesde(d0, "AND organization_id = @org_a AND event_type = 'PERMISSION_DENIED'");
		ok(f.length >= 11 && f.filter((x) => det(x).permissionCode === 'paciente:read').length >= 10, 'los rechazos con paciente:read', f);
		manual('el menu no ofrece el padron y /pacientes lleva a /sin-permiso');
	});
	await paso('PAC-02', 'El paciente tampoco entra a los otros modulos', async () => {
		const pa = await S('pac');
		const sede = `/api/v1/consultorios/${C.sede_a}`;
		for (const r of [`${sede}/turnos?fecha=${hoy()}`, `${sede}/sesiones/${C.sesion}`, `/api/v1/historias-clinicas/por-persona/${C.persona}`, `${sede}/obligaciones?personaId=${C.persona}`, `${sede}/cobros?personaId=${C.persona}`,
			`${sede}/caja/jornadas`, `${sede}/presentaciones`, `${sede}/reportes/OPERATIVO?desde=${hoy()}&hasta=${hoy()}`, `/api/v1/organizations/${C.org_a}/memberships`]) {
			st(await api('GET', r, { s: pa }), 403, `paciente ${r}`);
		}
	});
	await paso('PAC-03', 'El personal sigue leyendo, y la persona ajena sigue siendo 404', async () => {
		need(C.persona_b);
		for (const rol of ['prof', 'adm']) {
			const s = await S(rol);
			st(await api('GET', `/api/v1/personas/${C.persona}`, { s }), 200, `${rol} lee la persona de A`);
			st(await api('GET', `/api/v1/personas/${C.persona_b}`, { s }), 404, `${rol} con la persona de B`);
		}
		const f = q(`SELECT r.role_code, COUNT(*) AS rechazos_paciente_read FROM audit_event a JOIN membership r ON r.account_id = a.actor_account_id AND r.organization_id = a.organization_id AND r.estado = 'ACTIVA'
			WHERE a.organization_id = @org_a AND a.event_type = 'PERMISSION_DENIED' AND a.occurred_at >= @desde AND JSON_UNQUOTE(JSON_EXTRACT(a.details, '$.permissionCode')) = 'paciente:read' GROUP BY r.role_code`);
		ok(f.every((x) => x.role_code === 'PACIENTE'), 'rechazos de paciente:read solo para PACIENTE', f);
	});
}
