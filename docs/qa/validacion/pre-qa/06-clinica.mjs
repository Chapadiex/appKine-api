import { randomUUID } from 'node:crypto';
import { APIC, MAILC, RED, API, MAILPIT, P, MYSQL, DB, PLATAFORMA, AQUI, PASSWORD, PASSWORD2, SALIDA, SALIDA_JSON, SOLO, suf, dormir, UA, inicioCorrida, Corte, Bloqueo, R, cur, CIRC, paso, ultimaSql, ok, eq, st, tipo, must, need, nota, manual, soloManual, n, SECRETO, limpiar, descr, api, galletas, cookieRt, V, lit, unesc, q, q1, fmtSql, ahoraDb, maxAudit, auditDesde, tiene, cuenta, det, docker, correos, textoCorreo, esperarCorreos, tokenDe, tokenCorreo, ES_ACTIVACION, ES_INVITACION, pw, correo, registrar, activar, login, entrar, CU, SES, S, Splat, hoy, horaLocal, fechaLocal, instanteLocal, C, usados, agenda, slot, reservar, recepcionHastaLlamado, ses, iniciarSesion, tratar, cerrarSesion, atencionCompleta, PNG, multipart, sha256, J, ORIGEN, GUIA, guia, ZONA, setZona, AUDIT_RANGO } from './lib.mjs';

// 06 · Sesion clinica, historia clinica, casos, plan y enmiendas (+ PER-11 y ECO-01, que dependen del cierre)
const hcp = (p = '') => `/api/v1/historias-clinicas/por-persona/${C.persona}${p}`;
const sesDb = (id) => q(`SELECT id, turno_id, oferta_id, historia_clinica_id, caso_id, profesional_membership_id, estado, modo, asistencia, numero_sesion, numero_en_caso, ultimo_numero_version,
	motivo_clinico, dolor_eva, dolor_zona, dolor_lateralidad, evolucion, objetivo_sesion, borrador, cerrada_en, tolerancia, proxima_conducta, nota_de_cierre, version FROM sesion WHERE id = ${id}`);

export async function c06() {
	await paso('CLI-01', 'Iniciar la sesion desde el turno', async () => {
		need(C.turno_os);
		const d0 = maxAudit();
		const r = await iniciarSesion(C.turno_os);
		C.sesion = V.sesion = must(r, 201, 'iniciar la atencion').id;
		const f = sesDb(C.sesion);
		ok(f[0]?.estado === 'BORRADOR' && f[0].numero_sesion === null && f[0].caso_id === null, 'BORRADOR, sin numero ni caso', f);
		const h = q('SELECT id, persona_id, abierta_en, abierta_por FROM historia_clinica WHERE organization_id = @org_a AND persona_id = @persona');
		ok(h.length === 1, 'una historia clinica por persona y organizacion', h);
		C.historia = V.historia = n(h[0]?.id);
		await api('GET', ses(C.sesion), { s: await S('prof') });
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(tiene(au, 'HISTORIA_CLINICA_OPENED', (x) => det(x).viaDeAcceso === 'MODULO' && x.consultorio_id === null), 'HISTORIA_CLINICA_OPENED via MODULO sin consultorio', au);
		ok(tiene(au, 'SESION_INICIADA') && tiene(au, 'SESION_ACCEDIDA'), 'SESION_INICIADA y SESION_ACCEDIDA (la pantalla lee la sesion al abrirla; aca un GET)', au);
		const t = q1(`SELECT version FROM turno WHERE id = ${C.turno_os}`);
		const can = await api('POST', `/api/v1/consultorios/${C.sede_a}/turnos/${C.turno_os}/cancelacion`, { s: await S('adm'), json: { expectedVersion: n(t.version), motivo: 'no deberia' } });
		tipo(can, 'turno-con-atencion', 'AGE-06: un turno con sesion iniciada no se cancela');
	});
	await paso('CLI-02', 'Evaluar y guardar borrador', async () => {
		need(C.sesion);
		const s = await S('prof');
		const x = (await api('GET', ses(C.sesion), { s })).cuerpo;
		const ev = { modo: 'COMPLETA', motivoClinico: 'Dolor de rodilla (sintetico)', dolorEva: 6, dolorZona: 'Rodilla', dolorLateralidad: 'DERECHA', evolucion: 'IGUAL', objetivoSesion: 'Bajar el dolor' };
		const r1 = must(await api('PUT', `${ses(C.sesion)}/evaluacion`, { s, json: { ...ev, version: x.version } }), 200, 'guardar la evaluacion');
		const r2 = must(await api('PUT', `${ses(C.sesion)}/borrador`, { s, json: { contenido: JSON.stringify({ nota: 'borrador sintetico' }), version: r1.version } }), 200, 'autosave del borrador');
		tipo(await api('PUT', `${ses(C.sesion)}/evaluacion`, { s, json: { ...ev, version: x.version } }), 'concurrent-modification', 'la otra pestana con la version vieja');
		st(await api('PUT', `${ses(C.sesion)}/evaluacion`, { s, json: { ...ev, dolorEva: 11, version: r2.version } }), 400, 'EVA fuera de 0-10');
		if (CU.prof2?.org) st(await api('PUT', `${ses(C.sesion)}/evaluacion`, { s: await S('prof2'), json: { ...ev, version: r2.version } }), 409, 'otro profesional de la sede escribe en la sesion ajena: 409, no 403');
		const f = sesDb(C.sesion);
		ok(f[0].motivo_clinico === ev.motivoClinico && f[0].dolor_eva === '6' && f[0].dolor_zona === 'Rodilla' && f[0].borrador?.includes('borrador sintetico'), 'los campos clinicos y el borrador', f);
	});
	await paso('CLI-03', 'Tratamientos realizados', async () => {
		need(C.sesion);
		const d0 = maxAudit();
		const r = await tratar(C.sesion, C.practica, { tecnica: 'Movilizacion', parametros: [{ clave: 'intensidad', tipoDato: 'NUMERICO', valorNumerico: 3, unidad: 'nivel' }] });
		must(r, 201, 'registrar tratamiento');
		st(await tratar(C.sesion, C.practica, { zona: null, lateralidad: 'DERECHA' }), 400, 'lateralidad sin zona');
		const t = q('SELECT t.practica_id, t.practica_codigo, t.practica_nombre, t.tecnica, t.zona, t.lateralidad, t.duracion_minutos, t.active FROM tratamiento_realizado t WHERE t.sesion_id = @sesion ORDER BY t.orden');
		ok(t.length === 1 && t[0].practica_codigo === C.codigoPractica && t[0].practica_nombre, 'la practica copiada', t);
		const p = q('SELECT p.clave, p.tipo_dato, p.valor_numerico, p.valor_texto, p.valor_booleano FROM tratamiento_parametro p JOIN tratamiento_realizado t ON t.id = p.tratamiento_realizado_id WHERE t.sesion_id = @sesion');
		ok(p.length === 1 && Number(p[0].valor_numerico) === 3 && p[0].valor_texto === null && p[0].valor_booleano === null, 'el parametro tipado (solo valor_numerico)', p);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'TRATAMIENTO_REGISTRADO', (x) => x.entity_type === 'TRATAMIENTO_REALIZADO'), 'TRATAMIENTO_REGISTRADO');
		nota('la practica no habilitada en la oferta (DP-11) se prueba en CLI-12');
	});
	await paso('CLI-04', 'Mediciones', async () => {
		need(C.sesion);
		const d0 = maxAudit();
		const def = must(await api('POST', '/api/v1/mediciones/definiciones', { s: await S('admin'), json: { codigo: `QA-FLEX-${suf}`, name: 'Flexion de rodilla QA', tipo: 'NUMERICO', unidad: 'grados', minimo: 0, maximo: 160, alcance: 'ORGANIZACION' } }), 201, 'definicion');
		const s = await S('prof');
		must(await api('PUT', `${ses(C.sesion)}/mediciones/${def.id}?lateralidad=DERECHA`, { s, json: { valorNumerico: 95, nota: 'QA' } }), 200, 'registrar 95');
		st(await api('PUT', `${ses(C.sesion)}/mediciones/${def.id}?lateralidad=IZQUIERDA`, { s, json: { valorNumerico: 200 } }), 400, 'fuera de 0-160');
		const f = q('SELECT definicion_codigo, definicion_nombre, definicion_unidad, definicion_tipo, lateralidad, valor_numerico FROM sesion_medicion WHERE sesion_id = @sesion');
		ok(f.length === 1 && f[0].definicion_codigo === `QA-FLEX-${suf}` && Number(f[0].valor_numerico) === 95, 'el valor con la definicion congelada', f);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'MEDICION_DEFINICION_CREATED'), 'MEDICION_DEFINICION_CREATED');
	});
	await paso('CLI-05', 'Cerrar la sesion con cobertura: deuda, consumo y numeracion', async () => {
		need(C.sesion);
		const d0 = maxAudit();
		C.consumidaAntes = n(q1('SELECT cantidad_consumida c FROM autorizacion WHERE id = @autorizacion').c);
		must(await cerrarSesion(C.sesion), 200, 'cerrar');
		const f = sesDb(C.sesion);
		ok(f[0].estado === 'CERRADA' && f[0].asistencia === 'PRESENTE' && f[0].numero_sesion, 'CERRADA, PRESENTE, numerada', f);
		eq(q1('SELECT ultimo_numero u FROM sesion_numerador WHERE historia_clinica_id = @historia').u, f[0].numero_sesion, 'numero_sesion = ultimo del numerador de la historia');
		const o = q(`SELECT id, responsable, concepto, financiador_id, practica_id, importe_original, saldo, estado, snapshot_convenio_codigo, snapshot_importe_total, snapshot_importe_financiador, snapshot_coseguro FROM obligacion WHERE sesion_id = @sesion ORDER BY responsable`);
		const fin = o.find((x) => x.responsable === 'FINANCIADOR'), pac = o.find((x) => x.responsable === 'PACIENTE');
		ok(o.length === 2 && fin?.concepto === 'FINANCIADOR' && Number(fin.importe_original) === 6000 && n(fin.financiador_id) === C.financiador && pac?.concepto === 'COSEGURO' && Number(pac.importe_original) === 2000,
			'dos obligaciones: FINANCIADOR 6000 y PACIENTE/COSEGURO 2000', o);
		ok(o.every((x) => x.snapshot_convenio_codigo === 'CONV-QA' && Number(x.snapshot_importe_total) === 8000), 'el convenio congelado en snapshot_* (el arancel por oferta, no el general importado)', o);
		C.obl_fin1 = n(fin?.id);
		C.obl_cos1 = n(pac?.id);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(tiene(au, 'SESION_CERRADA', (x) => x.entity_type === 'Sesion' && x.new_state === 'CERRADA'), 'SESION_CERRADA de la Sesion', au);
		ok(tiene(au, 'AUTORIZACION_CONSUMIDA'), 'AUTORIZACION_CONSUMIDA', au);
		if (tiene(au, 'OBLIGACION_DEVENGADA')) guia('CLI-05 dice "El devengo no escribe audit_event"; desde #86 deja OBLIGACION_DEVENGADA (lo que ECO-14 espera)');
	});
	await paso('PER-11', 'Consumo de la autorizacion al cerrar la sesion (DP-12)', async () => {
		need(C.sesion, C.autorizacion);
		const a = q('SELECT cantidad_autorizada, cantidad_consumida FROM autorizacion WHERE id = @autorizacion');
		eq(n(a[0].cantidad_consumida), C.consumidaAntes + 1, 'el cierre consume una unidad', a);
		const m = q('SELECT id, tipo, cantidad, tipo_origen, referencia_origen FROM autorizacion_movimiento WHERE autorizacion_id = @autorizacion ORDER BY id');
		ok(m.length === 1 && m[0].tipo === 'CONSUMO' && m[0].tipo_origen === 'SESION' && n(m[0].referencia_origen) === C.sesion, 'un CONSUMO con tipo_origen SESION y la sesion de referencia', m);
		C.mov_consumo1 = n(m[0]?.id);
		const e = q("SELECT tipo, cantidad, movimiento_id FROM autorizacion_evento WHERE autorizacion_id = @autorizacion AND tipo IN ('CONSUMO','REVERSION_DE_CONSUMO') ORDER BY id");
		ok(e.length === 1 && e[0].tipo === 'CONSUMO', 'el mismo hecho en autorizacion_evento', e);
		const l = q(`SELECT a.id, a.cantidad_consumida, COALESCE(SUM(CASE m.tipo WHEN 'CONSUMO' THEN m.cantidad WHEN 'REVERSION' THEN -m.cantidad ELSE 0 END), 0) AS segun_ledger
			FROM autorizacion a LEFT JOIN autorizacion_movimiento m ON m.autorizacion_id = a.id WHERE a.organization_id = @org_a GROUP BY a.id, a.cantidad_consumida HAVING a.cantidad_consumida <> segun_ledger`);
		eq(l.length, 0, 'el ledger cuadra (critico si falla)', l);
		const au = q("SELECT details FROM audit_event WHERE organization_id = @org_a AND event_type = 'AUTORIZACION_CONSUMIDA' AND entity_id = @autorizacion");
		ok(au.some((x) => det(x).sesionId && det(x).movimientoId), 'AUTORIZACION_CONSUMIDA con details.sesionId y movimientoId', au);
	});
	await paso('CLI-06', 'Cerrar la sesion particular', async () => {
		need(C.turno_part);
		const si = await iniciarSesion(C.turno_part);
		C.sesion_part = V.sesion_part = must(si, 201, 'iniciar').id;
		must(await tratar(C.sesion_part, C.practica), 201, 'tratamiento');
		must(await cerrarSesion(C.sesion_part), 200, 'cerrar presente');
		const o = q('SELECT o.id, o.responsable, o.concepto, o.importe_original, o.saldo, o.estado, o.snapshot_precio, o.snapshot_convenio_id FROM obligacion o WHERE o.sesion_id = @sesion_part');
		ok(o.length === 1 && o[0].responsable === 'PACIENTE' && o[0].concepto === 'PARTICULAR' && Number(o[0].importe_original) === 10000 && o[0].snapshot_convenio_id === null,
			'una obligacion PACIENTE/PARTICULAR por 10000, sin convenio, aunque la persona tenga obra social (DP-17)', o);
		C.obl_part = V.obligacion = n(o[0]?.id);
	});
	await paso('ECO-01', 'La cuenta corriente muestra solo lo que debe el paciente', async () => {
		need(C.obl_cos1);
		const r = must(await api('GET', `/api/v1/consultorios/${C.sede_a}/obligaciones?personaId=${C.persona}`, { s: await S('adm') }), 200, 'cuenta corriente');
		const l = Array.isArray(r) ? r : r.content ?? [];
		ok(l.some((x) => x.id === C.obl_cos1) && !l.some((x) => x.responsable === 'FINANCIADOR'), 'aparece el coseguro y no la parte del financiador', JSON.stringify(l).slice(0, 500));
		const f = q('SELECT id, responsable, concepto, importe_original, saldo, estado FROM obligacion WHERE persona_id = @persona ORDER BY id');
		eq(f.length, 2, 'dos filas en la base', f);
	});
	await paso('CLI-08', 'Historia clinica: leer con relacion, con justificacion y sin nada', async () => {
		need(C.historia);
		const d0 = maxAudit();
		const prof = await S('prof'), adm = await S('adm');
		st(await api('GET', hcp(), { s: prof }), 200, '1. el profesional que atiende, sin header');
		st(await api('GET', hcp(), { s: await S('admin') }), 403, '2. admin-a sin grant');
		const { grant } = await import('./02-organizacion.mjs');
		must(await grant(C.madmin, 'hc:read'), 201, 'grant hc:read a admin-a');
		SES.admin = null;
		const a = await S('admin');
		const r3 = await api('GET', hcp(), { s: a });
		ok(r3.status === 403 && r3.cuerpo?.requiereJustificacion === true && r3.cuerpo?.cabecera === 'X-Justificacion-Acceso', '3. con hc:read y sin header: 403 con requiereJustificacion', r3);
		st(await api('GET', hcp(), { s: a, headers: { 'X-Justificacion-Acceso': 'Auditoria interna QA' } }), 200, '4. con justificacion');
		st(await api('GET', hcp(), { s: adm }), 403, '5. el administrativo (tambien cubre ORG-09)');
		st(await api('GET', hcp(), { s: adm, headers: J }), 403, '5. el administrativo con justificacion');
		const au = auditDesde(d0, "AND organization_id = @org_a AND (event_type = 'HISTORIA_CLINICA_ACCESSED' OR event_type = 'PERMISSION_DENIED')");
		const acc = au.filter((x) => x.event_type === 'HISTORIA_CLINICA_ACCESSED');
		ok(acc.length === 2, 'un HISTORIA_CLINICA_ACCESSED por cada 200 (critico si falta)', au);
		ok(acc.some((x) => det(x).viaDeAcceso === 'RELACION_ASISTENCIAL' && x.reason === null) && acc.some((x) => det(x).viaDeAcceso === 'JUSTIFICACION' && x.reason === 'Auditoria interna QA'), 'el del profesional por relacion sin reason; el de admin-a por justificacion con reason', au);
		ok(au.filter((x) => x.event_type === 'PERMISSION_DENIED' && det(x).permissionCode === 'hc:read').length >= 2, 'PERMISSION_DENIED hc:read por los 403 de permiso', au);
	});
	await paso('CLI-09', 'Resumen, antecedentes, entradas y adjuntos clinicos', async () => {
		need(C.historia);
		const d0 = maxAudit();
		const s = await S('prof');
		const hc = must(await api('GET', hcp(), { s }), 200, 'leer la historia');
		must(await api('PUT', hcp('/resumen'), { s, json: { texto: 'Gonalgia derecha de 3 meses (sintetico)', expectedVersion: hc.version } }), 200, 'resumen');
		must(await api('POST', hcp('/antecedentes'), { s, json: { tipo: 'ALERGIA', descripcion: 'Alergia sintetica QA' } }), 201, 'antecedente');
		const e = must(await api('POST', `/api/v1/historias-clinicas/${C.historia}/entradas`, { s, json: { tipo: 'EVOLUCION', cuerpo: 'Evolucion sintetica QA' } }), 201, 'entrada');
		must(await api('POST', `/api/v1/entradas-clinicas/${e.id}/enmiendas`, { s, json: { cuerpo: 'Evolucion corregida QA', motivo: 'Error de tipeo', expectedVersion: e.version } }), 200, 'enmienda de la entrada');
		const ad = must(await api('POST', `/api/v1/historias-clinicas/${C.historia}/adjuntos?categoria=IMAGEN&titulo=QA`, { s, form: multipart('rx-sintetica-nombre-secreto.png', PNG, 'image/png') }), [200, 201], 'adjunto clinico');
		const d = await api('GET', `/api/v1/historias-clinicas/${C.historia}/adjuntos/${ad.id}/contenido`, { s, headers: { Accept: '*/*' } });
		ok(d.status === 200 && d.buf.equals(PNG), 'la descarga 200 y el archivo identico', `HTTP ${d.status}, ${d.buf.length} bytes`);
		const h = q('SELECT resumen, version FROM historia_clinica WHERE id = @historia');
		ok(h[0].resumen?.startsWith('Gonalgia'), 'resumen guardado', h);
		const v = q('SELECT e.id, v.numero_version, v.motivo_enmienda, LEFT(v.cuerpo, 60) cuerpo FROM entrada_clinica e JOIN entrada_clinica_version v ON v.entrada_clinica_id = e.id WHERE e.historia_clinica_id = @historia AND e.origen = \'MANUAL\' ORDER BY e.id, v.numero_version');
		ok(v.length === 2 && v[0].cuerpo === 'Evolucion sintetica QA' && v[1].motivo_enmienda === 'Error de tipeo', 'la entrada con dos versiones, la 1 intacta', v);
		const adj = q('SELECT estado, checksum_sha256 FROM adjunto_clinico WHERE historia_clinica_id = @historia');
		ok(adj.length === 1 && adj[0].estado === 'DISPONIBLE' && adj[0].checksum_sha256 === sha256(PNG), 'el adjunto DISPONIBLE con su checksum', adj);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		for (const ev of ['HISTORIA_CLINICA_RESUMEN_UPDATED', 'ANTECEDENTE_CLINICO_REGISTERED', 'ENTRADA_CLINICA_REGISTERED', 'ENTRADA_CLINICA_AMENDED', 'ADJUNTO_CLINICO_UPLOADED', 'ADJUNTO_CLINICO_DOWNLOADED']) ok(tiene(au, ev), ev, au);
		const dl = au.find((x) => x.event_type === 'ADJUNTO_CLINICO_DOWNLOADED');
		ok(dl && det(dl).checksumSha256 && !JSON.stringify(au).includes('nombre-secreto'), 'la descarga audita el checksum y ningun evento lleva el nombre del archivo', au);
	});
	await paso('CLI-10', 'Timeline clinico', async () => {
		need(C.historia);
		const d0 = maxAudit();
		const s = await S('prof');
		const base = `/api/v1/historias-clinicas/${C.historia}/timeline`;
		const todo = must(await api('GET', `${base}?limite=100`, { s }), 200, 'todo en una pagina');
		const ids = [];
		let cursor = null, paginas = 0;
		do {
			const r = must(await api('GET', `${base}?limite=2${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, { s }), 200, 'pagina');
			ids.push(...r.eventos.map((e) => `${e.tipo}:${e.referencia}`));
			cursor = r.proximoCursor;
			paginas++;
		} while (cursor && paginas < 50);
		const total = todo.eventos.map((e) => `${e.tipo}:${e.referencia}`);
		ok(ids.length === total.length && new Set(ids).size === ids.length, `recorrer de a 2 da los mismos ${total.length} hechos, sin perder ni repetir`, `paginado: ${ids.join(',')}`, `entero: ${total.join(',')}`);
		ok(!total.some((x) => /TURNO/i.test(x)), 'el turno no aparece');
		const o = todo.eventos.map((e) => e.ocurrioEn);
		ok(o.every((x, i) => i === 0 || o[i - 1] >= x), 'los mas nuevos primero', o.join(','));
		eq(cuenta(auditDesde(d0, 'AND organization_id = @org_a'), 'TIMELINE_ACCESSED'), paginas + 1, 'un TIMELINE_ACCESSED por pagina');
	});
	await paso('CLI-11', 'Caso clinico: abrir, equipo, cerrar y reabrir', async () => {
		need(C.historia);
		const d0 = maxAudit();
		const s = await S('prof');
		const cuerpo = { diagnosticoPresuntivo: `Gonalgia derecha ${suf} (sintetico)`, ofertaId: C.oferta, objetivoTerapeutico: 'Volver a correr', equipo: [{ profesionalMembershipId: C.mprof, rol: 'RESPONSABLE' }] };
		let c = must(await api('POST', `/api/v1/historias-clinicas/${C.historia}/casos`, { s, json: cuerpo }), 201, 'abrir el caso');
		C.caso = V.caso = c.id;
		const dup = await api('POST', `/api/v1/historias-clinicas/${C.historia}/casos`, { s, json: cuerpo });
		ok(dup.status === 409 && /duplicad|confirmaPosibleDuplicado/i.test(JSON.stringify(dup.cuerpo)), 'un segundo caso con el mismo diagnostico avisa posible duplicado', dup);
		c = must(await api('POST', `/api/v1/casos-clinicos/${c.id}/cierre`, { s, json: { expectedVersion: c.version, motivo: 'Alta QA' } }), 200, 'cerrar');
		c = must(await api('POST', `/api/v1/casos-clinicos/${c.id}/reapertura`, { s, json: { expectedVersion: c.version, motivo: 'Recaida QA' } }), 200, 'reabrir');
		const f = q('SELECT id, numero_caso, estado, cerrado_en, motivo_cierre FROM caso_clinico WHERE historia_clinica_id = @historia ORDER BY id');
		ok(f.length === 1 && f[0].numero_caso === '1' && f[0].estado === 'ACTIVO', 'numero_caso 1, ACTIVO', f);
		const ev = q('SELECT tipo, motivo FROM caso_evento WHERE caso_id = @caso ORDER BY id');
		ok(ev.map((x) => x.tipo).join(',').startsWith('APERTURA') && ev.some((x) => x.tipo === 'CIERRE' && x.motivo) && ev.some((x) => x.tipo === 'REAPERTURA' && x.motivo), 'APERTURA, CIERRE y REAPERTURA con motivo', ev);
		const eqp = q('SELECT profesional_membership_id, rol, hasta FROM caso_profesional WHERE caso_id = @caso');
		ok(eqp.some((x) => n(x.profesional_membership_id) === C.mprof && x.rol === 'RESPONSABLE'), 'el equipo con el responsable', eqp);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		for (const e of ['CASO_CLINICO_OPENED', 'CASO_CLINICO_CLOSED', 'CASO_CLINICO_REOPENED']) ok(tiene(au, e), e, au);
	});
	await paso('CLI-12', 'Sesion dentro de un caso', async () => {
		need(C.caso);
		const d0 = maxAudit();
		const at = await atencionCompleta(C.persona, { cobertura: C.cobertura, casoId: C.caso, cerrar: false });
		if (at.error) { st(at.error, [200, 201], 'preparar la atencion con caso'); return; }
		C.sesion_caso = at.sesion.id;
		const nh = await tratar(C.sesion_caso, C.practica2);
		st(nh, 201, 'CLI-03 / DP-11: una practica no habilitada en la oferta no se rechaza');
		must(await cerrarSesion(C.sesion_caso), 200, 'cerrar');
		const f = q('SELECT id, caso_id, numero_sesion, numero_en_caso FROM sesion WHERE caso_id = @caso ORDER BY id');
		ok(f.length === 1 && f[0].numero_en_caso === '1' && f[0].numero_sesion === '2', 'numero_sesion 2 (de la historia) y numero_en_caso 1 (del caso)', f);
		eq(q1('SELECT ultimo_numero u FROM caso_sesion_numerador WHERE caso_id = @caso').u, 1, 'numerador del caso');
		const o = q(`SELECT responsable, concepto, alerta_practica_no_habilitada FROM obligacion WHERE sesion_id = ${C.sesion_caso}`);
		nota(`alerta_practica_no_habilitada de las obligaciones: ${o.map((x) => x.alerta_practica_no_habilitada).join(',')} (la marca solo se pone si la practica que resolvio el arancel no esta habilitada; aca resolvio la habilitada)`);
		C.obl_fin2 = n(q1(`SELECT id FROM obligacion WHERE sesion_id = ${C.sesion_caso} AND responsable = 'FINANCIADOR'`).id);
		C.obl_cos2 = n(q1(`SELECT id FROM obligacion WHERE sesion_id = ${C.sesion_caso} AND responsable = 'PACIENTE'`).id);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'SESION_CERRADA', (x) => det(x).numeroEnCaso !== undefined), 'SESION_CERRADA con details.numeroEnCaso');
	});
	await paso('CLI-07', 'Enmendar una sesion cerrada', async () => {
		need(C.sesion);
		const d0 = maxAudit();
		const s = await S('prof');
		const x = (await api('GET', ses(C.sesion), { s })).cuerpo;
		const consumida = n(q1('SELECT cantidad_consumida c FROM autorizacion WHERE id = @autorizacion').c);
		const cuerpo = { version: x.version, motivo: 'Pre-QA: se corrige el EVA', dolorEva: 4, dolorZona: 'Rodilla', dolorLateralidad: 'DERECHA', motivoClinico: 'Dolor de rodilla (sintetico)', evolucion: 'IGUAL', objetivoSesion: 'Bajar el dolor', tolerancia: 'BUENA', proximaConducta: 'CONTINUA', indicaciones: 'Hielo 15 min (sintetico)', notaDeCierre: 'Sesion sintetica pre-QA' };
		tipo(await api('POST', `${ses(C.sesion)}/enmiendas`, { s, json: { ...cuerpo, tratamientos: [{ practicaId: C.practica2, zona: 'Rodilla', lateralidad: 'DERECHA', duracionMinutos: 10 }] } }), 'enmienda-cambia-practicas', 'cambiar el conjunto de practicas');
		if (CU.prof2?.org) st(await api('POST', `${ses(C.sesion)}/enmiendas`, { s: await S('prof2'), json: cuerpo }), 409, 'otro profesional');
		must(await api('POST', `${ses(C.sesion)}/enmiendas`, { s, json: cuerpo }), 200, 'enmendar');
		const at = await atencionCompleta(C.persona_part, { particular: 'pre-QA', cerrar: false, practica: null });
		if (!at.error) {
			C.sesion_abierta = at.sesion.id;
			C.turno_ausente_ses = at.turno.id;
			const y = (await api('GET', ses(at.sesion.id), { s })).cuerpo;
			tipo(await api('POST', `${ses(at.sesion.id)}/enmiendas`, { s, json: { ...cuerpo, version: y.version } }), 'sesion-no-cerrada', 'enmendar una sesion abierta');
		} else nota(`no se pudo preparar una sesion abierta: HTTP ${at.error.status}`);
		const v = q('SELECT numero_version, motivo_enmienda, dolor_eva FROM sesion_version WHERE sesion_id = @sesion ORDER BY numero_version');
		ok(v.length === 2 && v[0].dolor_eva === '6' && v[0].motivo_enmienda === null && v[1].dolor_eva === '4' && v[1].motivo_enmienda, 'la version 1 intacta y la 2 con el motivo', v);
		const f = sesDb(C.sesion);
		ok(f[0].ultimo_numero_version === '2' && f[0].dolor_eva === '4', 'ultimo_numero_version = 2', f);
		eq(q1('SELECT COUNT(*) n FROM obligacion WHERE sesion_id = @sesion').n, 2, 'las mismas dos obligaciones (no se devenga otra vez)');
		eq(n(q1('SELECT cantidad_consumida c FROM autorizacion WHERE id = @autorizacion').c), consumida, 'no se consume otra vez');
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'SESION_AMENDED'), 'SESION_AMENDED');
	});
	await paso('CLI-13', 'Plan de tratamiento: borrador, activo, suspendido, reanudado', async () => {
		need(C.caso);
		const d0 = maxAudit();
		const s = await S('prof');
		const cuerpo = { objetivos: 'Recuperar movilidad', frecuenciaSemanal: 2, duracionSemanas: 4, items: [{ ofertaId: C.oferta, cantidadPlanificada: 8 }] };
		let p = must(await api('POST', `/api/v1/casos-clinicos/${C.caso}/planes`, { s, json: cuerpo }), 201, 'crear');
		const pid = C.plan_tratamiento = V.plan_tratamiento = p.id;
		p = must(await api('POST', `/api/v1/planes-tratamiento/${pid}/activacion`, { s, json: { expectedVersion: p.version } }), 200, 'activar');
		p = must(await api('POST', `/api/v1/planes-tratamiento/${pid}/suspension`, { s, json: { expectedVersion: p.version, motivo: 'Viaje del paciente' } }), 200, 'suspender');
		const otro = await api('POST', `/api/v1/casos-clinicos/${C.caso}/planes`, { s, json: cuerpo });
		if (otro.status === 201) st(await api('POST', `/api/v1/planes-tratamiento/${otro.cuerpo.id}/activacion`, { s, json: { expectedVersion: otro.cuerpo.version } }), 409, 'un suspendido ocupa el lugar del activo: activar otro es rechazo (defecto 4 del 29/09)');
		else st(otro, 409, 'otro plan mientras el suspendido vive');
		guia('CLI-13 dice que crear y activar otro plan mientras el activo vive es rechazo; el producto finaliza solo el ACTIVO anterior al activar uno nuevo ("Finalizado automaticamente al activar el plan N") y rechaza solo si el anterior esta SUSPENDIDO (PlanTratamientoService.finalizarElVigente)');
		p = must(await api('POST', `/api/v1/planes-tratamiento/${pid}/reanudacion`, { s, json: { expectedVersion: p.version } }), 200, 'reanudar');
		st(await api('GET', `/api/v1/planes-tratamiento/${pid}/avance`, { s }), 200, 'avance');
		must(await api('PATCH', `/api/v1/planes-tratamiento/${pid}`, { s, json: { ...cuerpo, objetivos: 'Recuperar movilidad y fuerza', motivo: 'Ajuste QA', expectedVersion: p.version } }), 200, 'modificar con motivo');
		const ev = q('SELECT tipo, estado_anterior, estado_nuevo, motivo FROM plan_evento WHERE plan_tratamiento_id = @plan_tratamiento ORDER BY id');
		ok(ev.map((x) => x.tipo).join(',').startsWith('CREACION,ACTIVACION,SUSPENSION,REANUDACION') && ev.find((x) => x.tipo === 'SUSPENSION')?.motivo, 'CREACION, ACTIVACION, SUSPENSION (con motivo), REANUDACION', ev);
		const v = q('SELECT v.numero_version, v.objetivos FROM plan_tratamiento_version v WHERE v.plan_tratamiento_id = @plan_tratamiento ORDER BY v.numero_version');
		ok(v.length === 2 && v[0].objetivos === 'Recuperar movilidad', 'la modificacion crea la version 2 y deja la 1', v);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		for (const e of ['PLAN_TRATAMIENTO_CREATED', 'PLAN_TRATAMIENTO_ACTIVATED', 'PLAN_TRATAMIENTO_SUSPENDED', 'PLAN_TRATAMIENTO_RESUMED', 'PLAN_TRATAMIENTO_ACCESSED']) ok(tiene(au, e), e, au);
	});
	await paso('CLI-14', 'Ausente en la sesion', async () => {
		need(C.sesion_abierta);
		must(await cerrarSesion(C.sesion_abierta, { asistencia: 'AUSENTE' }), 200, 'cerrar AUSENTE');
		const f = q(`SELECT s.estado, s.asistencia, (SELECT COUNT(*) FROM obligacion o WHERE o.sesion_id = s.id) AS obligaciones FROM sesion s WHERE s.id = ${C.sesion_abierta}`);
		const ns = q1('SELECT devenga_no_show d FROM oferta_servicio_consultorio WHERE id = @oferta').d;
		ok(f[0]?.estado === 'CERRADA' && f[0].asistencia === 'AUSENTE' && (ns === '1' ? f[0].obligaciones !== '0' : f[0].obligaciones === '0'), `cerrada AUSENTE; deuda segun devenga_no_show (${ns})`, f);
	});
	await paso('CLI-15', 'Lo clinico no se ve desde roles sin permiso', async () => {
		need(C.sesion, C.historia);
		const rutas = [ses(C.sesion), hcp(), `/api/v1/historias-clinicas/${C.historia}/timeline`];
		for (const [rol, esperado] of [['adm', 403], ['pac', 403], ['b', 404]]) {
			const s = await S(rol);
			for (const r of rutas) st(await api('GET', r, { s, headers: J }), esperado, `${rol} ${r}`);
		}
		const inex = await api('GET', '/api/v1/historias-clinicas/por-persona/99999999', { s: await S('b'), headers: J });
		nota(`con el token de B, la historia de una persona inexistente responde ${inex.status} ${inex.tipo ?? ''}: si es igual que con la persona de A, el 403 no es oraculo de existencia`);
		const f = q(`SELECT a.event_type, c.email FROM audit_event a JOIN cuenta c ON c.id = a.actor_account_id WHERE a.organization_id = @org_a AND a.occurred_at >= @desde
			AND a.event_type IN ('HISTORIA_CLINICA_ACCESSED','SESION_ACCEDIDA','TIMELINE_ACCESSED') AND c.email_normalizado IN (@email_administrativo, @email_paciente, @email_admin_b)`);
		eq(f.length, 0, 'ninguna lectura clinica auditada de esos roles', f);
		manual('/atencion/turnos/<id> lleva a /sin-permiso');
	});
	await paso('CLI-16', 'Datos administrativos de la HC para el administrativo', async () => {
		need(C.historia);
		const d0 = maxAudit();
		const a = await S('admin');
		const sinHc = must(await nuevaPersonaLocal(a, 'Sin', 'Historia', true), 201, 'persona con perfil y sin historia');
		const sinPerfil = must(await nuevaPersonaLocal(a, 'Sin', 'Perfil', false), 201, 'persona sin perfil');
		const url = (id) => `/api/v1/historias-clinicas/por-persona/${id}/datos-administrativos`;
		const adm = await S('adm');
		const r = must(await api('GET', url(C.persona), { s: adm }), 200, 'administrativo');
		const claves = new Set(Object.keys(r));
		const extra = [...claves].filter((k) => !['personaId', 'tieneHistoriaClinica', 'historiaClinicaId', 'abiertaEn', 'casos'].includes(k));
		const casoKeys = new Set((r.casos ?? []).flatMap((c) => Object.keys(c)));
		const extraCaso = [...casoKeys].filter((k) => !['id', 'numeroCaso', 'estado', 'ofertaId', 'ofertaConsultorioId', 'abiertoEn', 'cerradoEn', 'equipo'].includes(k));
		ok(extra.length === 0 && extraCaso.length === 0 && !/Gonalgia|Volver a correr|Alta QA/.test(JSON.stringify(r)), 'solo metadatos, ningun dato clinico (critico si falla)', JSON.stringify(r));
		st(await api('GET', hcp(), { s: adm }), 403, 'el contenido sigue cerrado');
		st(await api('GET', url(C.persona), { s: await S('prof') }), 200, 'profesional');
		st(await api('GET', url(C.persona), { s: await S('pac') }), 403, 'paciente');
		st(await api('GET', url(C.persona), { s: await S('b') }), 404, 'admin-b');
		const r2 = await api('GET', url(sinHc.id), { s: adm });
		ok(r2.status === 200 && r2.cuerpo.tieneHistoriaClinica === false, 'persona sin historia: 200 con tieneHistoriaClinica = false', r2);
		st(await api('GET', url(sinPerfil.id), { s: adm }), 404, 'persona sin perfil de paciente (regresion de #115 si da 200)');
		const vig = q('SELECT cp.caso_id, cp.profesional_membership_id FROM caso_profesional cp JOIN caso_clinico c ON c.id = cp.caso_id WHERE c.historia_clinica_id = @historia AND cp.hasta IS NULL');
		const eqApi = (r.casos ?? []).flatMap((c) => (c.equipo ?? []).map((e) => e.profesionalMembershipId));
		eq(eqApi.length, vig.length, 'el equipo vigente coincide con la base', vig);
		const au = auditDesde(d0, "AND organization_id = @org_a AND event_type IN ('HISTORIA_CLINICA_ACCESSED','PERMISSION_DENIED')");
		const idAdm = n(q1('SELECT id FROM cuenta WHERE email_normalizado = @email_administrativo').id), idPac = n(q1('SELECT id FROM cuenta WHERE email_normalizado = @email_paciente').id);
		eq(au.filter((x) => x.event_type === 'HISTORIA_CLINICA_ACCESSED' && n(x.actor_account_id) === idAdm).length, 0, 'ninguna lectura del administrativo auditada como acceso clinico');
		ok(au.some((x) => x.event_type === 'PERMISSION_DENIED' && n(x.actor_account_id) === idPac && det(x).permissionCode === 'paciente:read'), 'el 403 del paciente deja PERMISSION_DENIED paciente:read', au);
		manual('la tarjeta "Historia clinica: datos administrativos" de la ficha');
	});
	await paso('CLI-17', 'Cambiar el equipo de un caso devuelve la version nueva', async () => {
		need(C.caso, C.mprof2);
		const d0 = maxAudit();
		const s = await S('prof');
		const c = must(await api('GET', `/api/v1/casos-clinicos/${C.caso}`, { s }), 200, 'leer el caso');
		const r1 = await api('PUT', `/api/v1/casos-clinicos/${C.caso}/equipo`, { s, json: { expectedVersion: c.version, integrantes: [{ profesionalMembershipId: C.mprof, rol: 'RESPONSABLE' }, { profesionalMembershipId: C.mprof2, rol: 'TRATANTE' }] } });
		must(r1, 200, 'sumar un tratante');
		eq(r1.cuerpo.version, n(c.version) + 1, 'r1 devuelve version leida + 1');
		const r2 = await api('PUT', `/api/v1/casos-clinicos/${C.caso}/equipo`, { s, json: { expectedVersion: r1.cuerpo.version, integrantes: [{ profesionalMembershipId: C.mprof, rol: 'RESPONSABLE' }] } });
		st(r2, 200, 'encadenar con la version de la respuesta (un 409 es hallazgo)');
		tipo(await api('PUT', `/api/v1/casos-clinicos/${C.caso}/equipo`, { s, json: { expectedVersion: c.version, integrantes: [{ profesionalMembershipId: C.mprof, rol: 'RESPONSABLE' }] } }), 'concurrent-modification', 'la version vieja');
		eq(n(q1('SELECT version FROM caso_clinico WHERE id = @caso').version), n(r2.cuerpo?.version), 'la version de la base es la de r2');
		const eqp = q('SELECT profesional_membership_id, rol, hasta FROM caso_profesional WHERE caso_id = @caso ORDER BY id');
		ok(eqp.some((x) => n(x.profesional_membership_id) === C.mprof2 && x.hasta !== null), 'el que salio queda con hasta, nadie borrado', eqp);
		eq(q1("SELECT COUNT(*) n FROM caso_evento WHERE caso_id = @caso AND tipo = 'CAMBIO_DE_EQUIPO'").n, 2, 'dos CAMBIO_DE_EQUIPO');
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(cuenta(au, 'CASO_EQUIPO_CHANGED', (x) => det(x).entraron !== undefined || det(x).salieron !== undefined) === 2, 'dos CASO_EQUIPO_CHANGED con entraron/salieron', au);
		const c2 = must(await api('POST', `/api/v1/historias-clinicas/${C.historia}/casos`, { s, json: { diagnosticoPresuntivo: `Lumbalgia ${suf} (sintetico)`, ofertaId: C.oferta, confirmaPosibleDuplicado: true } }), 201, 'otro caso');
		const cc = must(await api('POST', `/api/v1/casos-clinicos/${c2.id}/cierre`, { s, json: { expectedVersion: c2.version, motivo: 'Alta' } }), 200, 'cerrarlo');
		tipo(await api('PUT', `/api/v1/casos-clinicos/${c2.id}/equipo`, { s, json: { expectedVersion: cc.version, integrantes: [{ profesionalMembershipId: C.mprof, rol: 'RESPONSABLE' }] } }), 'caso-clinico-cerrado', 'un caso cerrado');
	});
}
async function nuevaPersonaLocal(s, nombre, apellido, perfil) {
	const r = await api('POST', '/api/v1/personas', { s, json: { nombre, apellido: `${apellido} ${suf}`, tipoDocumento: 'DNI', numeroDocumento: `5${String(Date.now()).slice(-7)}` } });
	if (r.status === 201 && perfil) await api('POST', `/api/v1/personas/${r.cuerpo.id}/perfil-paciente`, { s, json: { motivo: 'pre-QA' } });
	return r;
}
