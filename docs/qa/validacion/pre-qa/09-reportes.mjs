import { randomUUID } from 'node:crypto';
import { APIC, MAILC, RED, API, MAILPIT, P, MYSQL, DB, PLATAFORMA, AQUI, PASSWORD, PASSWORD2, SALIDA, SALIDA_JSON, SOLO, suf, dormir, UA, inicioCorrida, Corte, Bloqueo, R, cur, CIRC, paso, ultimaSql, ok, eq, st, tipo, must, need, nota, manual, soloManual, n, SECRETO, limpiar, descr, api, galletas, cookieRt, V, lit, unesc, q, q1, fmtSql, ahoraDb, maxAudit, auditDesde, tiene, cuenta, det, docker, correos, textoCorreo, esperarCorreos, tokenDe, tokenCorreo, ES_ACTIVACION, ES_INVITACION, pw, correo, registrar, activar, login, entrar, CU, SES, S, Splat, hoy, horaLocal, fechaLocal, instanteLocal, C, usados, agenda, slot, reservar, recepcionHastaLlamado, ses, iniciarSesion, tratar, cerrarSesion, atencionCompleta, PNG, multipart, sha256, J, ORIGEN, GUIA, guia, ZONA, setZona, AUDIT_RANGO } from './lib.mjs';

// 09 · Reportes. El periodo es el del mes de la sede, en la zona de la sede (la guia fija UTC-3).
const rep = (r, x = '') => `/api/v1/consultorios/${C.sede_a}/reportes/${r}?desde=${desdeRep()}&hasta=${hoy()}${x}`;
const desdeRep = () => `${hoy().slice(0, 8)}01`;
const ind = (body, clave) => {
	for (const s of body?.secciones ?? []) for (const i of s.indicadores ?? []) if (i.clave === clave) return Number(i.valor);
	return undefined;
};
const omit = (body) => (body?.omitidas ?? []).map((x) => `${x.seccion}:${x.permisoRequerido}`).sort().join(',');
function periodo() {
	V.rep_desde = instanteLocal(desdeRep(), '00:00').replace('T', ' ').replace('Z', '');
	V.rep_hasta = instanteLocal(hoy(1), '00:00').replace('T', ' ').replace('Z', '');
}

export async function c09() {
	periodo();
	await paso('REP-01', 'Turnos del periodo', async () => {
		need(C.sede_a);
		const r = must(await api('GET', rep('TURNOS'), { s: await S('admin') }), 200, 'reporte de turnos');
		const f = q('SELECT estado, COUNT(*) AS turnos FROM turno WHERE consultorio_id = @sede_a AND inicio >= @rep_desde AND inicio < @rep_hasta GROUP BY estado');
		const c = Object.fromEntries(f.map((x) => [x.estado, n(x.turnos)]));
		const total = f.reduce((a, x) => a + n(x.turnos), 0);
		eq(ind(r, 'turnos-totales'), total, 'turnos-totales = turnos del periodo en la base', f);
		eq(ind(r, 'turnos-cancelados'), c.CANCELADO ?? 0, 'turnos-cancelados = CANCELADO de la base (regresion de #86 si da 0)', f);
		eq(ind(r, 'turnos-ausentes'), c.AUSENTE ?? 0, 'turnos-ausentes', f);
		eq(ind(r, 'turnos-confirmados'), c.CONFIRMADO ?? 0, 'turnos-confirmados', f);
		const esp = q1("SELECT COUNT(*) n FROM recepcion WHERE consultorio_id = @sede_a AND estado = 'EN_ESPERA'").n;
		eq(ind(r, 'turnos-en-espera'), esp, 'turnos-en-espera sale de la recepcion');
		nota('periodo SQL hasta el fin del dia de la sede (el reporte incluye "hasta"); la guia usa UTC_TIMESTAMP()');
	});
	await paso('REP-02', 'Economico', async () => {
		const r = must(await api('GET', rep('ECONOMICO'), { s: await S('admin') }), 200, 'reporte economico');
		const o = q("SELECT SUM(CASE WHEN estado <> 'ANULADA' THEN importe_original ELSE 0 END) devengado, SUM(CASE WHEN estado = 'ANULADA' THEN importe_original ELSE 0 END) anulado, SUM(CASE WHEN estado <> 'ANULADA' THEN saldo ELSE 0 END) pendiente FROM obligacion WHERE consultorio_id = @sede_a AND devengada_en >= @rep_desde AND devengada_en < @rep_hasta AND deleted_at IS NULL");
		const c = q('SELECT COALESCE(SUM(m.importe), 0) AS cobrado_en_caja FROM cobro c JOIN cobro_medio m ON m.cobro_id = c.id WHERE c.consultorio_id = @sede_a AND c.deleted_at IS NULL AND c.cobrado_en >= @rep_desde AND c.cobrado_en < @rep_hasta');
		eq(ind(r, 'devengado'), Number(o[0].devengado), 'devengado', o);
		eq(ind(r, 'devengado-anulado'), Number(o[0].anulado), 'devengado-anulado (solo ANULADA vigentes)', o);
		eq(ind(r, 'deuda-vigente'), Number(o[0].pendiente), 'deuda vigente = saldo pendiente', o);
		eq(ind(r, 'cobrado'), Number(c[0].cobrado_en_caja), 'cobrado', c);
	});
	await paso('REP-03', 'Financiadores', async () => {
		const r = must(await api('GET', rep('FINANCIADORES'), { s: await S('admin') }), 200, 'reporte de financiadores');
		const f = q("SELECT o.financiador_id, COUNT(*) prestaciones, SUM(o.importe_original) prestado, SUM(o.saldo) pendiente FROM obligacion o WHERE o.consultorio_id = @sede_a AND o.responsable = 'FINANCIADOR' AND o.estado <> 'ANULADA' AND o.devengada_en >= @rep_desde AND o.devengada_en < @rep_hasta GROUP BY o.financiador_id");
		const sec = (r.secciones ?? []).find((x) => x.seccion === 'financiadores');
		const col = sec?.columnas?.indexOf('Prestado') ?? -1;
		const sumaDet = (sec?.filas ?? []).reduce((a, x) => a + Number(x[col]), 0);
		ok(sumaDet === ind(r, 'prestado') && ind(r, 'prestado') === f.reduce((a, x) => a + Number(x.prestado), 0), 'el detalle suma al indicador prestado y coincide con la base', f, JSON.stringify(sec));
		const pend = f.reduce((a, x) => a + Number(x.pendiente), 0);
		if (ind(r, 'pendiente') !== pend) nota(`PREGUNTA: el indicador "pendiente" del reporte da ${ind(r, 'pendiente')} y la consulta de la guia (SUM(saldo) de las obligaciones del financiador) da ${pend}: el indicador mide lo pendiente de los lotes, no la deuda del financiador sin presentar`);
	});
	await paso('REP-04', 'Clinico: la consulta queda auditada', async () => {
		const d0 = maxAudit();
		const a = await S('admin');
		const con = must(await api('GET', rep('CLINICO'), { s: a }), 200, 'clinico con el grant hc:read (CLI-08)');
		nota(`ORG_ADMIN con hc:read: omitidas = [${omit(con)}]`);
		ok(!omit(con).includes('casos'), 'con el grant, la seccion de casos viene', JSON.stringify(con.omitidas));
		if (omit(con).includes('sesiones')) guia('REP-04/REP-05 dicen que el ORG_ADMIN con hc:read ve casos y sesiones; la seccion sesiones exige sesion:register, que el ORG_ADMIN no tiene ni puede recibir por grant, asi que sigue omitida');
		const prof = must(await api('GET', rep('CLINICO'), { s: await S('prof') }), 200, 'clinico del profesional');
		ok((prof.advertencias ?? []).some((x) => x.codigo === 'alcance-actividad-propia'), 'el profesional ve solo lo suyo, con la advertencia', JSON.stringify(prof.advertencias));
		const au = auditDesde(d0, "AND organization_id = @org_a AND event_type = 'REPORTE_CONSULTADO'");
		ok(au.length >= 1 && au.every((x) => det(x).reporte === 'CLINICO' && det(x).alcance), 'REPORTE_CONSULTADO con reporte CLINICO y alcance', au);
		ok(!/Gonalgia|rodilla/i.test(JSON.stringify(au)), 'sin contenido clinico en details', au);
		const ses = q("SELECT COUNT(*) n, SUM(asistencia = 'PRESENTE') presentes FROM sesion WHERE consultorio_id = @sede_a AND estado = 'CERRADA' AND cerrada_en >= @rep_desde AND cerrada_en < @rep_hasta");
		nota(`sesiones cerradas en la base: ${JSON.stringify(ses[0])}; indicadores: ${JSON.stringify((con.secciones ?? []).flatMap((s) => (s.indicadores ?? []).map((i) => `${i.clave}=${i.valor}`)))}`);
	});
	await paso('REP-05', 'El mismo reporte con cada rol', async () => {
		const d0 = maxAudit();
		const adm = await S('adm'), prof = await S('prof'), pac = await S('pac');
		const ae = must(await api('GET', rep('ECONOMICO'), { s: adm }), 200, 'administrativo economico');
		eq(omit(ae), '', 'administrativo: economico completo');
		const ac = must(await api('GET', rep('CLINICO'), { s: adm }), 200, 'administrativo clinico');
		eq(omit(ac), 'casos:hc:read,sesiones:sesion:register', 'administrativo: clinico omite casos y sesiones');
		const pe = must(await api('GET', rep('ECONOMICO'), { s: prof }), 200, 'profesional economico');
		ok(omit(pe).includes('economia:cobro:register') && (pe.advertencias ?? []).some((x) => x.codigo === 'alcance-actividad-propia'), 'profesional: economia omitida y advertencia', JSON.stringify(pe));
		const pc = must(await api('GET', rep('CLINICO'), { s: prof }), 200, 'profesional clinico');
		const mis = n(q1('SELECT COUNT(*) n FROM sesion WHERE consultorio_id = @sede_a AND profesional_membership_id = @membership_profesional AND estado = \'CERRADA\' AND cerrada_en >= @rep_desde AND cerrada_en < @rep_hasta').n);
		const vals = (pc.secciones ?? []).flatMap((s) => s.indicadores ?? []);
		nota(`profesional: mis_sesiones en la base = ${mis}; indicadores = ${vals.map((i) => `${i.clave}=${i.valor}`).join(', ')}`);
		ok(vals.some((i) => /sesion/.test(i.clave) && Number(i.valor) === mis), 'el profesional ve el numero de sus sesiones');
		st(await api('GET', rep('ECONOMICO'), { s: pac }), 403, 'paciente economico');
		st(await api('GET', rep('CLINICO'), { s: pac }), 403, 'paciente clinico');
		const au = auditDesde(d0, "AND organization_id = @org_a AND event_type = 'PERMISSION_DENIED'");
		eq(au.filter((x) => det(x).permissionCode === 'reporte:read').length, 2, 'cada 403 deja PERMISSION_DENIED reporte:read', au);
	});
	await paso('REP-06', 'Exportar a CSV', async () => {
		const a = await S('admin');
		const j = must(await api('GET', rep('OPERATIVO'), { s: a }), 200, 'operativo');
		const r = await api('GET', `/api/v1/consultorios/${C.sede_a}/reportes/OPERATIVO/export?desde=${desdeRep()}&hasta=${hoy()}`, { s: a, headers: { Accept: 'text/csv, application/problem+json' } });
		st(r, 200, 'exportar');
		const cd = r.headers.get('content-disposition') ?? '';
		ok(/attachment/i.test(cd) && cd.includes(`reporte-operativo-${desdeRep()}_${hoy()}.csv`) && /text\/csv/i.test(r.headers.get('content-type') ?? ''), 'attachment reporte-operativo-<desde>_<hasta>.csv, text/csv', `content-disposition: ${cd}; content-type: ${r.headers.get('content-type')}`);
		const txt = r.buf.toString('utf8');
		ok(!txt.includes('�'), 'UTF-8 valido');
		const faltan = (j.secciones ?? []).flatMap((s) => s.indicadores ?? []).filter((i) => !txt.includes(String(i.valor).replace(/\.0+$/, '')));
		eq(faltan.length, 0, 'las cifras de la respuesta aparecen en el archivo', JSON.stringify(faltan));
		for (const o of j.omitidas ?? []) ok(!new RegExp(`^${o.seccion}\\b`, 'mi').test(txt) || txt.includes(o.permisoRequerido), `la seccion omitida ${o.seccion} tambien se omite (o se declara) en el archivo`, txt.slice(0, 800));
		manual('abrirlo y ver los acentos en un editor');
	});
}
