import { randomUUID } from 'node:crypto';
import { APIC, MAILC, RED, API, MAILPIT, P, MYSQL, DB, PLATAFORMA, AQUI, PASSWORD, PASSWORD2, SALIDA, SALIDA_JSON, SOLO, suf, dormir, UA, inicioCorrida, Corte, Bloqueo, R, cur, CIRC, paso, ultimaSql, ok, eq, st, tipo, must, need, nota, manual, soloManual, n, SECRETO, limpiar, descr, api, galletas, cookieRt, V, lit, unesc, q, q1, fmtSql, ahoraDb, maxAudit, auditDesde, tiene, cuenta, det, docker, correos, textoCorreo, esperarCorreos, tokenDe, tokenCorreo, ES_ACTIVACION, ES_INVITACION, pw, correo, registrar, activar, login, entrar, CU, SES, S, Splat, hoy, horaLocal, fechaLocal, instanteLocal, C, usados, agenda, slot, reservar, recepcionHastaLlamado, ses, iniciarSesion, tratar, cerrarSesion, atencionCompleta, PNG, multipart, sha256, J, ORIGEN, GUIA, guia, ZONA, setZona, AUDIT_RANGO } from './lib.mjs';

// =================================================================== 10 · plataforma (inicio)
export async function esperarSalud(segs = 600) {
	for (let i = 0; i < segs / 5; i++) {
		try { if ((await fetch(`${API}/actuator/health/readiness`)).ok) return true; } catch { /* arrancando */ }
		await dormir(5000);
	}
	return false;
}
export async function c10inicio() {
	V.desde = ahoraDb();
	await paso('PLA-01', 'Tomar posesion de la plataforma (bootstrap DP-14)', async () => {
		const s = await Splat();
		ok(s.token, 'la cuenta de plataforma ingresa');
		const roles = q(`SELECT c.id, c.email, c.estado, c.password_hash IS NOT NULL AS con_clave, pr.role_code, pr.rol_activo, pr.valid_until
			FROM platform_role pr JOIN cuenta c ON c.id = pr.account_id WHERE pr.active = 1`);
		ok(roles.length === 1 && roles[0].email === PLATAFORMA && roles[0].estado === 'ACTIVA' && roles[0].con_clave === '1',
			'una sola cuenta con rol de plataforma activo, el email del bootstrap, ACTIVA y con clave', roles);
		const ev = q(`SELECT id, event_type, entity_id, organization_id, details FROM audit_event WHERE event_type = 'PLATFORM_ADMIN_BOOTSTRAP' ORDER BY id`);
		ok(ev.length === 1 && ev[0].organization_id === null && det(ev[0]).emailAnterior === 'plataforma@akine.app' && det(ev[0]).emailNuevo === PLATAFORMA,
			'un PLATFORM_ADMIN_BOOTSTRAP sin organizacion, de plataforma@akine.app a la casilla de la variable', ev);
		if (process.env.QA_SIN_REINICIO) { manual('reiniciar el backend y ver que el bootstrap no vuelve a actuar (salteado con QA_SIN_REINICIO)'); return; }
		const antes = (await correos(PLATAFORMA)).length;
		docker('restart', APIC);
		ok(await esperarSalud(), 'el backend vuelve a arrancar despues del reinicio');
		await dormir(20000);
		const ev2 = q(`SELECT COUNT(*) n FROM audit_event WHERE event_type = 'PLATFORM_ADMIN_BOOTSTRAP'`);
		eq(ev2[0].n, 1, 'tras reiniciar, el bootstrap no vuelve a actuar', ev2);
		eq((await correos(PLATAFORMA)).length, antes, 'tras reiniciar, no sale otro correo de activacion a la plataforma');
		SES.plat = null;
		manual('ingresar por /auth/ingresar y ver que no hay selector de contexto');
	});
	await paso('PLA-02', 'Servicio global', async () => {
		const s = await Splat();
		const r = await api('POST', '/api/v1/servicios', { s, json: { codigo: `KINE_${suf}`.toUpperCase(), nombre: `Kinesiología QA ${suf}`, naturaleza: 'TERAPEUTICO', modalidadDefault: 'INDIVIDUAL' } });
		C.servicio = must(r, 201, 'alta del servicio global').id;
		V.servicio = C.servicio;
		const fila = q(`SELECT id, codigo, nombre, naturaleza, modalidad_default, requiere_caso_clinico_default, genera_registro_clinico_default, active FROM servicio WHERE id = @servicio`);
		ok(fila.length === 1 && fila[0].active === '1' && fila[0].naturaleza === 'TERAPEUTICO', 'la fila del servicio, activa', fila);
		const col = q(`SELECT COUNT(*) n FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'servicio' AND column_name = 'organization_id'`);
		eq(col[0].n, 0, 'la tabla servicio no tiene organization_id (catalogo global)', col);
		const dup = await api('POST', '/api/v1/servicios', { s, json: { codigo: `KINE2_${suf}`.toUpperCase(), nombre: `KINESIOLOGIA QA ${suf.toUpperCase()}`, naturaleza: 'TERAPEUTICO', modalidadDefault: 'INDIVIDUAL' } });
		st(dup, 409, 'nombre repetido sin distinguir mayusculas ni acentos');
		tipo(dup, 'servicio-nombre-taken', 'nombre repetido');
		const au = q(`SELECT organization_id FROM audit_event WHERE event_type = 'SERVICIO_CREATED' AND entity_id = @servicio`);
		ok(au.length === 1 && au[0].organization_id === null, 'SERVICIO_CREATED sin organizacion', au);
		manual('alta por la pantalla /servicios/catalogo');
	});
}

// =================================================================== 01 · onboarding
export async function esperarOutbox(where, estado = 'ENVIADA', segs = 60) {
	let ob = [];
	for (let i = 0; i < segs / 2; i++) {
		ob = q(`SELECT id, tipo, estado, intentos, enviado_en, payload_sanitizado, error_sanitizado FROM notification_outbox WHERE ${where} ORDER BY id`);
		if (ob.length && ob.every((x) => x.estado === estado)) break;
		await dormir(2000);
	}
	return ob;
}
export async function c01() {
	CU.admin = { email: correo('admin-a') };
	V.email_admin_a = CU.admin.email;
	await paso('ON-01', 'Alta self-service del centro A', async () => {
		const r = await registrar(CU.admin.email, `Centro QA A ${suf}`);
		must(r, 202, 'alta self-service');
		C.cuerpoAlta = r.cuerpo;
		ok(!JSON.stringify(r.cuerpo).includes(CU.admin.email), 'la respuesta no dice nada del email (ADR-0018)', r);
		const c = q(`SELECT id, email, estado, password_hash LIKE '{argon2}$argon2id$%' AS hash_argon2id, intentos_fallidos FROM cuenta WHERE email_normalizado = @email_admin_a`);
		ok(c.length === 1 && c[0].estado === 'PENDIENTE_ACTIVACION' && c[0].hash_argon2id === '1', 'cuenta PENDIENTE_ACTIVACION con hash Argon2id', c);
		const reg = q(`SELECT r.estado, r.organization_id, r.consultorio_id, o.timezone, c.slot_minutes FROM onboarding_registro r
			JOIN organization o ON o.id = r.organization_id JOIN consultorio c ON c.id = r.consultorio_id WHERE r.email_normalizado = @email_admin_a`);
		ok(reg.length === 1 && reg[0].estado === 'COMPLETADO' && reg[0].timezone === 'America/Argentina/Cordoba', 'registro COMPLETADO con una sede en America/Argentina/Cordoba', reg);
		V.cuenta_admin_a = n(c[0]?.id);
		V.org_a = n(reg[0]?.organization_id);
		V.sede_a = n(reg[0]?.consultorio_id);
		Object.assign(C, { org_a: V.org_a, sede_a: V.sede_a, cuenta_admin: V.cuenta_admin_a });
		CU.admin.org = C.org_a;
		need(C.org_a);
		const sedes = q('SELECT COUNT(*) n FROM consultorio WHERE organization_id = @org_a');
		eq(sedes[0].n, 1, 'una sola sede', sedes);
		const m = q('SELECT id, role_code, is_founder, consultorio_id, estado FROM membership WHERE organization_id = @org_a AND account_id = @cuenta_admin_a');
		ok(m.length === 1 && m[0].role_code === 'ORG_ADMIN' && m[0].is_founder === '1' && m[0].consultorio_id === null && m[0].estado === 'ACTIVA', 'membership ORG_ADMIN fundadora de alcance organizacion', m);
		C.madmin = n(m[0]?.id);
		const sub = q('SELECT s.status, p.code AS plan FROM subscription s JOIN plan p ON p.id = s.plan_id WHERE s.organization_id = @org_a');
		ok(sub[0]?.status === 'ACTIVA' && sub[0]?.plan === 'BASICO', 'suscripcion ACTIVA en BASICO', sub);
		const tk = q('SELECT tipo, usado_en, invalidado_en FROM token_verificacion WHERE cuenta_id = @cuenta_admin_a ORDER BY id');
		ok(tk.length === 1 && tk[0].tipo === 'ACTIVACION' && tk[0].usado_en === null, 'un token ACTIVACION sin usar', tk);
		const mails = await esperarCorreos(CU.admin.email, 1, (s) => /Activa tu cuenta/i.test(s));
		ok(mails.length === 1, 'llega "Activa tu cuenta de AKINE" a la casilla');
		const tokenMail = mails.length ? tokenDe(await textoCorreo(mails[0].ID)) : null;
		C.tokenActivacionAdmin = tokenMail;
		const ob = await esperarOutbox('destinatario = @email_admin_a');
		ok(ob.length === 1 && ob[0].tipo === 'ACTIVACION_CUENTA' && ob[0].estado === 'ENVIADA', 'notificacion ACTIVACION_CUENTA en ENVIADA', ob);
		const pl = ob[0]?.payload_sanitizado ?? '';
		ok(!/https?:\/\//i.test(pl) && !(tokenMail && pl.includes(tokenMail)), 'payload_sanitizado sin enlace ni token (critico si falla)', ob);
		const au = q(`SELECT organization_id, event_type, entity_type, new_state, details FROM audit_event WHERE actor_account_id = @cuenta_admin_a AND occurred_at >= @desde ORDER BY id`);
		ok(tiene(au, 'ORGANIZATION_CREATED'), 'auditoria ORGANIZATION_CREATED', au);
		ok(tiene(au, 'MEMBERSHIP_CREATED', (x) => String(det(x).isFounder) === 'true'), 'auditoria MEMBERSHIP_CREATED con isFounder', au);
		ok(tiene(au, 'CUENTA_CREADA', (x) => x.new_state === 'PENDIENTE_ACTIVACION'), 'auditoria CUENTA_CREADA PENDIENTE_ACTIVACION', au);
		manual('ver la pantalla /auth/registro y el mensaje que no revela si el email existia');
	});
	await paso('ON-02', 'Activar por el enlace del correo', async () => {
		need(C.tokenActivacionAdmin);
		const r = await api('POST', '/api/v1/auth/activate', { json: { token: C.tokenActivacionAdmin } });
		st(r, [200, 204], 'activacion');
		const e = q('SELECT estado FROM cuenta WHERE id = @cuenta_admin_a');
		eq(e[0]?.estado, 'ACTIVA', 'cuenta ACTIVA', e);
		const t = q('SELECT tipo, usado_en, invalidado_en, LENGTH(token_hash) largo FROM token_verificacion WHERE cuenta_id = @cuenta_admin_a ORDER BY id');
		ok(t[0]?.usado_en !== null && t[0]?.largo === '64', 'token con usado_en y solo el hash (64)', t);
		const crudo = q(`SELECT COUNT(*) n FROM token_verificacion WHERE token_hash = ${lit(C.tokenActivacionAdmin)}`);
		eq(crudo[0].n, 0, 'el valor del enlace no esta guardado tal cual');
		const otra = await api('POST', '/api/v1/auth/activate', { json: { token: C.tokenActivacionAdmin } });
		ok(otra.status >= 400 && otra.status < 500, 'reabrir el mismo enlace falla con 4xx', otra);
		ok(!/exception|\bat com\.|sql/i.test(JSON.stringify(otra.cuerpo)), 'el error no trae internals', otra);
		const au = q(`SELECT organization_id, previous_state, new_state FROM audit_event WHERE event_type = 'CUENTA_ACTIVADA' AND entity_id = @cuenta_admin_a`);
		ok(au.length === 1 && au[0].organization_id === null && au[0].previous_state === 'PENDIENTE_ACTIVACION' && au[0].new_state === 'ACTIVA', 'CUENTA_ACTIVADA PENDIENTE_ACTIVACION -> ACTIVA sin organizacion', au);
	});
	await paso('ON-03', 'Ingresar y elegir contexto', async () => {
		const d0 = maxAudit();
		const s = await entrar(CU.admin.email);
		CU.admin.sede = s.consultorioId;
		SES.admin = s;
		eq(s.organizationId, C.org_a, 'el contexto es la organizacion A');
		const sc = galletas(s.respuesta).find((x) => x.startsWith('akine_rt=')) ?? '';
		ok(/httponly/i.test(sc), 'la cookie akine_rt es HttpOnly', `Set-Cookie: ${sc.replace(/akine_rt=[^;]+/, 'akine_rt=***')}`);
		const rt = q(`SELECT id, context_organization_id, context_consultorio_id, revocado_en, token_hash REGEXP '^[0-9a-f]{64}$' AS hash_ok FROM refresh_token WHERE cuenta_id = @cuenta_admin_a ORDER BY id DESC LIMIT 5`);
		ok(rt.some((x) => n(x.context_organization_id) === C.org_a && n(x.context_consultorio_id) === C.sede_a && x.revocado_en === null), 'un refresh vigente con el contexto A / sede A', rt);
		ok(rt.every((x) => x.hash_ok === '1'), 'token_hash SHA-256 de 64 hexadecimales', rt);
		const ac = q('SELECT organization_id, consultorio_id FROM account_active_context WHERE account_id = @cuenta_admin_a');
		eq(n(ac[0]?.organization_id), C.org_a, 'account_active_context apunta a A', ac);
		const au = auditDesde(d0, 'AND (actor_account_id = @cuenta_admin_a OR entity_id = @cuenta_admin_a)');
		ok(tiene(au, 'LOGIN_EXITOSO', (x) => x.organization_id === null), 'LOGIN_EXITOSO sin organizacion', au);
		ok(tiene(au, 'SESION_ABIERTA', (x) => x.organization_id === null), 'SESION_ABIERTA sin organizacion', au);
		ok(tiene(au, 'CONTEXTO_SELECCIONADO', (x) => n(x.organization_id) === C.org_a), 'CONTEXTO_SELECCIONADO en A', au);
		manual('ver en DevTools que la cookie no es legible desde JavaScript');
	});
	await paso('ON-04', 'Contrasena equivocada y cuenta sin activar: misma respuesta', async () => {
		const d0 = maxAudit();
		CU.sinact = { email: correo('sin-activar') };
		must(await registrar(CU.sinact.email, `Sin activar ${suf}`), 202, 'alta sin activar');
		const r1 = await login(CU.admin.email, `${PASSWORD}-equivocada`);
		const r2 = await login(CU.sinact.email, PASSWORD);
		const r3 = await login(`qa.no-existe.${suf}@ejemplo.test`, PASSWORD);
		const firma = (r) => `${r.status}|${r.tipo}|${r.cuerpo?.detail}|${r.cuerpo?.title}`;
		ok(r1.status === 401 && firma(r1) === firma(r2) && firma(r2) === firma(r3), 'la misma respuesta para clave equivocada, cuenta sin activar y email inexistente', r1, r2, r3);
		const c = q('SELECT email, estado, intentos_fallidos, bloqueada_en FROM cuenta WHERE id = @cuenta_admin_a');
		ok(c[0]?.intentos_fallidos === '1' && c[0]?.bloqueada_en === null, 'intentos_fallidos = 1 y sin bloqueo', c);
		must(await login(CU.admin.email), 200, 'login correcto');
		const c2 = q('SELECT intentos_fallidos FROM cuenta WHERE id = @cuenta_admin_a');
		eq(c2[0]?.intentos_fallidos, 0, 'intentos_fallidos vuelve a 0', c2);
		const sin = q(`SELECT id FROM cuenta WHERE email_normalizado = ${lit(CU.sinact.email)}`);
		const au = auditDesde(d0, "AND event_type IN ('LOGIN_FALLIDO','LOGIN_RECHAZADO_ESTADO')");
		ok(tiene(au, 'LOGIN_FALLIDO', (x) => n(x.entity_id) === C.cuenta_admin && x.organization_id === null), 'LOGIN_FALLIDO de admin-a sin organizacion', au);
		if (!tiene(au, 'LOGIN_FALLIDO', (x) => det(x).intentosFallidos !== undefined)) guia('ON-04 dice que LOGIN_FALLIDO lleva details.intentosFallidos; el codigo lo pone solo en ACTIVIDAD_SOSPECHOSA (LOGIN_FALLIDO trae details.motivo)');
		ok(tiene(au, 'LOGIN_RECHAZADO_ESTADO', (x) => n(x.entity_id) === n(sin[0]?.id)), 'LOGIN_RECHAZADO_ESTADO para la cuenta sin activar', au);
	});
	await paso('ON-05', 'Registrarse con un email que ya existe', async () => {
		const antes = (await correos(CU.admin.email)).length;
		const r = await registrar(CU.admin.email, `Otro centro ${suf}`, { password: pw[CU.admin.email] });
		st(r, 202, 'mismo 202 que un alta nueva');
		ok(JSON.stringify(r.cuerpo) === JSON.stringify(C.cuerpoAlta), 'el cuerpo es identico al de un alta nueva', r);
		const reg = q('SELECT estado, organization_id FROM onboarding_registro WHERE email_normalizado = @email_admin_a ORDER BY id');
		ok(reg.length === 2 && reg[1].estado === 'DUPLICADO' && reg[1].organization_id === null, 'un segundo registro DUPLICADO sin organizacion', reg);
		const f = q('SELECT COUNT(*) n FROM membership WHERE account_id = @cuenta_admin_a AND is_founder = 1');
		eq(f[0].n, 1, 'sigue habiendo una organizacion fundada', f);
		const ob = await esperarOutbox("destinatario = @email_admin_a AND tipo = 'CUENTA_YA_REGISTRADA'", 'ENVIADA', 40);
		ok(ob.length === 1, 'notificacion CUENTA_YA_REGISTRADA', ob);
		const ms = await esperarCorreos(CU.admin.email, antes + 1);
		ok(ms.length === antes + 1 && !ES_ACTIVACION(ms[0].Subject) && !tokenDe(await textoCorreo(ms[0].ID)), 'llega un aviso de "ya tenes cuenta", sin enlace de activacion', `asunto: ${ms[0]?.Subject}`);
	});
	await paso('ON-06', 'Reenviar la activacion invalida el enlace anterior', async () => {
		const d0 = maxAudit();
		CU.reenvio = { email: correo('reenvio') };
		must(await registrar(CU.reenvio.email, `Reenvio ${suf}`), 202, 'alta');
		await esperarCorreos(CU.reenvio.email, 1, ES_ACTIVACION);
		must(await api('POST', '/api/v1/auth/activation/resend', { json: { email: CU.reenvio.email } }), 202, 'reenvio');
		const ms = await esperarCorreos(CU.reenvio.email, 2, ES_ACTIVACION);
		need(ms[1]);
		const nuevo = tokenDe(await textoCorreo(ms[0].ID)), viejo = tokenDe(await textoCorreo(ms[1].ID));
		const rv = await api('POST', '/api/v1/auth/activate', { json: { token: viejo } });
		ok(rv.status >= 400 && rv.status < 500, 'el primer enlace falla', rv);
		st(await api('POST', '/api/v1/auth/activate', { json: { token: nuevo } }), [200, 204], 'el segundo activa');
		const t = q(`SELECT t.id, t.tipo, t.usado_en, t.invalidado_en FROM token_verificacion t JOIN cuenta c ON c.id = t.cuenta_id WHERE c.email_normalizado = ${lit(CU.reenvio.email)} ORDER BY t.id`);
		ok(t.length === 2 && t[0].invalidado_en !== null && t[0].usado_en === null && t[1].usado_en !== null, 'el primero invalidado sin usar, el segundo usado', t);
		const au = auditDesde(d0, "AND event_type IN ('ACTIVACION_REENVIADA','CUENTA_ACTIVADA')");
		ok(tiene(au, 'ACTIVACION_REENVIADA') && tiene(au, 'CUENTA_ACTIVADA'), 'ACTIVACION_REENVIADA y CUENTA_ACTIVADA', au);
	});
	await paso('ON-07', 'Restablecer la contrasena revoca las sesiones abiertas', async () => {
		const d0 = maxAudit();
		const s1 = await entrar(CU.admin.email);
		const r = await api('POST', '/api/v1/auth/password-reset', { json: { email: CU.admin.email } });
		const r0 = await api('POST', '/api/v1/auth/password-reset', { json: { email: `qa.no-existe.${suf}@ejemplo.test` } });
		ok(r.status === 202 && r.status === r0.status && JSON.stringify(r.cuerpo) === JSON.stringify(r0.cuerpo), 'el pedido responde igual exista o no la cuenta', r, r0);
		const tok = await tokenCorreo(CU.admin.email, (s) => !ES_ACTIVACION(s) && !/ya ten|registrad/i.test(s));
		need(tok);
		const t = ahoraDb();
		must(await api('POST', '/api/v1/auth/password-reset/confirm', { json: { token: tok, password: PASSWORD2 } }), 204, 'canje del reset');
		pw[CU.admin.email] = PASSWORD2;
		SES.admin = null;
		st(await api('POST', '/api/v1/auth/refresh', { cookie: s1.cookie, headers: ORIGEN }), 401, 'la sesion abierta antes muere al refrescar');
		st(await login(CU.admin.email, PASSWORD), 401, 'la contrasena vieja ya no entra');
		st(await login(CU.admin.email, PASSWORD2), 200, 'la nueva si');
		const tk = q("SELECT usado_en FROM token_verificacion WHERE cuenta_id = @cuenta_admin_a AND tipo = 'RESET' ORDER BY id");
		ok(tk.length === 1 && tk[0].usado_en !== null, 'un token RESET usado', tk);
		const vivos = q(`SELECT id, revocado_en, motivo_revocacion FROM refresh_token WHERE cuenta_id = @cuenta_admin_a AND emitido_en < ${lit(t)} AND revocado_en IS NULL`);
		eq(vivos.length, 0, 'ningun refresh anterior al canje queda vivo', vivos);
		const rev = q("SELECT COUNT(*) n FROM refresh_token WHERE cuenta_id = @cuenta_admin_a AND motivo_revocacion = 'RESET_PASSWORD'");
		ok(n(rev[0].n) >= 1, 'refresh revocados con RESET_PASSWORD', rev);
		const ob = await esperarOutbox("destinatario = @email_admin_a AND tipo IN ('RESET_PASSWORD','RECUPERACION_PASSWORD')", 'ENVIADA', 40);
		ok(ob[0]?.estado === 'ENVIADA', 'notificacion de recuperacion ENVIADA', ob);
		if (ob[0]?.tipo === 'RECUPERACION_PASSWORD') guia("ON-07 consulta notification_outbox con tipo = 'RESET_PASSWORD'; el tipo real es RECUPERACION_PASSWORD (la consulta de la guia devuelve cero filas)");
		const au = auditDesde(d0, "AND event_type IN ('RESET_SOLICITADO','RESET_COMPLETADO')");
		ok(tiene(au, 'RESET_SOLICITADO') && tiene(au, 'RESET_COMPLETADO'), 'RESET_SOLICITADO y RESET_COMPLETADO', au);
	});
	await paso('ON-08', 'Cerrar sesion', async () => {
		const d0 = maxAudit();
		const s = await entrar(CU.admin.email);
		const ultimo = n(q1('SELECT MAX(id) m FROM refresh_token WHERE cuenta_id = @cuenta_admin_a').m);
		st(await api('POST', '/api/v1/auth/logout', { s, headers: ORIGEN }), 204, 'logout');
		const rt = q(`SELECT id, revocado_en, motivo_revocacion FROM refresh_token WHERE id = ${ultimo}`);
		eq(rt[0]?.motivo_revocacion, 'LOGOUT', 'el refresh de la sesion queda revocado con LOGOUT', rt);
		st(await api('POST', '/api/v1/auth/refresh', { cookie: s.cookie, headers: ORIGEN }), 401, 'el refresh ya no sirve');
		const au = auditDesde(d0, "AND event_type = 'SESION_CERRADA'");
		ok(tiene(au, 'SESION_CERRADA', (x) => x.entity_type === 'Cuenta'), "SESION_CERRADA con entity_type = 'Cuenta'", au);
		manual('el boton "atras" del navegador no muestra datos');
	});
	await paso('ON-09', 'Las demas cuentas sinteticas', async () => {
		for (const rol of ['profesional', 'administrativo', 'paciente', 'admin-b', 'profesional2']) {
			const email = correo(rol);
			must(await registrar(email, rol === 'admin-b' ? `Centro QA B ${suf}` : `Propio ${rol} ${suf}`), 202, `alta de ${rol}`);
			st(await activar(email), [200, 204], `activacion de ${rol}`);
		}
		CU.prof = { email: correo('profesional') };
		CU.adm = { email: correo('administrativo') };
		CU.pac = { email: correo('paciente') };
		CU.b = { email: correo('admin-b') };
		CU.prof2 = { email: correo('profesional2') };
		Object.assign(V, { email_admin_b: CU.b.email, email_profesional: CU.prof.email, email_administrativo: CU.adm.email, email_paciente: CU.pac.email });
		const f = q(`SELECT c.email, c.estado, m.organization_id, m.role_code, m.is_founder FROM cuenta c JOIN membership m ON m.account_id = c.id
			WHERE c.email_normalizado IN (@email_admin_a, @email_admin_b, @email_profesional, @email_administrativo, @email_paciente) ORDER BY c.email, m.organization_id`);
		ok(f.length === 5 && f.every((x) => x.estado === 'ACTIVA' && x.role_code === 'ORG_ADMIN' && x.is_founder === '1') && new Set(f.map((x) => x.organization_id)).size === 5,
			'las cinco ACTIVA, cada una fundadora de su propia organizacion', f);
		V.org_b = n(q1("SELECT m.organization_id o FROM membership m JOIN cuenta c ON c.id = m.account_id WHERE c.email_normalizado = @email_admin_b AND m.is_founder = 1").o);
		V.sede_b = n(q1('SELECT MIN(id) s FROM consultorio WHERE organization_id = @org_b').s);
		Object.assign(C, { org_b: V.org_b, sede_b: V.sede_b });
		CU.b.org = C.org_b;
		V.cuenta_profesional = n(q1('SELECT id FROM cuenta WHERE email_normalizado = @email_profesional').id);
	});
	await paso('ON-11', 'La sesion guarda la IP del cliente, no la del proxy', async () => {
		const gw = docker('network', 'inspect', RED, '-f', '{{(index .IPAM.Config 0).Gateway}}');
		must(await login(CU.admin.email), 200, 'login');
		must(await login(CU.admin.email, undefined, { 'X-Forwarded-For': '203.0.113.9' }), 200, 'login con X-Forwarded-For falso');
		const rt = q('SELECT id, ip, LEFT(user_agent, 60) AS user_agent FROM refresh_token WHERE cuenta_id = @cuenta_admin_a ORDER BY id DESC LIMIT 2');
		ok(rt.length === 2 && rt.every((x) => x.ip === gw), `la ip guardada es la del socket (gateway ${gw}) en las dos sesiones`, rt);
		ok(rt.every((x) => x.ip !== '203.0.113.9'), 'un X-Forwarded-For de un cliente que no es proxy de confianza no aparece en ip', rt);
		ok(rt.every((x) => x.user_agent === UA), 'user_agent del cliente', rt);
		manual('el caso que importa es con la imagen del frontend detras de nginx y AKINE_TRUSTED_PROXIES: no se arma en este stack');
	});
	await paso('ON-12', 'La base no responde: 503 y la sesion sigue viva', async () => {
		const s = await S('admin');
		st(await api('GET', '/api/v1/personas?size=1', { s }), 200, 'antes de pausar');
		const tPausa = ahoraDb();
		docker('pause', MYSQL);
		let r;
		try { r = await api('GET', '/api/v1/personas?size=1', { s, timeoutMs: 120000 }); }
		finally { docker('unpause', MYSQL); }
		st(r, 503, 'con la base pausada');
		tipo(r, 'service-unavailable', 'con la base pausada');
		ok(/sigue valida/i.test(r.cuerpo?.detail ?? ''), 'el detalle dice que la sesion sigue valida', r);
		nota(`respondio a los ${(r.ms / 1000).toFixed(1)} s`);
		await dormir(3000);
		st(await api('GET', '/api/v1/personas?size=1', { s }), 200, 'despues del unpause, el mismo token responde 200');
		const rt = q(`SELECT id, revocado_en, motivo_revocacion FROM refresh_token WHERE cuenta_id = @cuenta_admin_a AND revocado_en >= ${lit(tPausa)}`);
		eq(rt.length, 0, 'ningun refresh revocado por el corte', rt);
		manual('el aviso "Servicio no disponible" de la pantalla y que no redirige al login');
	});
	await paso('ON-13', 'La misma Idempotency-Key con otros datos es 409', async () => {
		const email = correo('idem');
		const k = randomUUID();
		const alta = { email, password: PASSWORD, firstName: 'QA', lastName: 'Idem', organizationName: `Centro QA Idem ${suf}` };
		const reg = (b) => api('POST', '/api/v1/auth/register', { headers: { 'Idempotency-Key': k }, json: b });
		st(await reg(alta), 202, 'alta');
		st(await reg(alta), 202, 'replay');
		st(await reg({ ...alta, password: `${PASSWORD}-otra` }), 202, 'solo otra contrasena: replay');
		const c1 = await reg({ ...alta, organizationName: `Otro nombre QA ${suf}` });
		st(c1, 409, 'otro nombre de organizacion');
		tipo(c1, 'idempotency-key-conflict', 'otro nombre de organizacion');
		ok(!JSON.stringify(c1.cuerpo).includes(email) && !/cuenta|existe/i.test(c1.cuerpo?.detail ?? ''), 'el 409 no dice nada de la cuenta ni del email', c1);
		const c2 = await reg({ ...alta, email: CU.admin.email });
		tipo(c2, 'idempotency-key-conflict', 'otro email (uno existente)');
		await dormir(15000);
		eq((await correos(email)).filter((m) => ES_ACTIVACION(m.Subject)).length, 1, 'un solo correo de activacion');
		const f = q(`SELECT estado, organization_id IS NOT NULL AS creo_organizacion, LENGTH(request_hash) AS largo_hash FROM onboarding_registro WHERE email_normalizado = ${lit(email)} ORDER BY id`);
		ok(f.length === 1 && f[0].estado === 'COMPLETADO' && f[0].largo_hash === '64', 'una fila COMPLETADO con request_hash de 64', f);
		const o = q(`SELECT COUNT(*) n FROM organization WHERE name = ${lit(`Otro nombre QA ${suf}`)}`);
		eq(o[0].n, 0, 'ninguna organizacion del conflicto', o);
	});
}
