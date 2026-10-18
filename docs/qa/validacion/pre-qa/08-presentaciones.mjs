import { randomUUID } from 'node:crypto';
import { APIC, MAILC, RED, API, MAILPIT, P, MYSQL, DB, PLATAFORMA, AQUI, PASSWORD, PASSWORD2, SALIDA, SALIDA_JSON, SOLO, suf, dormir, UA, inicioCorrida, Corte, Bloqueo, R, cur, CIRC, paso, ultimaSql, ok, eq, st, tipo, must, need, nota, manual, soloManual, n, SECRETO, limpiar, descr, api, galletas, cookieRt, V, lit, unesc, q, q1, fmtSql, ahoraDb, maxAudit, auditDesde, tiene, cuenta, det, docker, correos, textoCorreo, esperarCorreos, tokenDe, tokenCorreo, ES_ACTIVACION, ES_INVITACION, pw, correo, registrar, activar, login, entrar, CU, SES, S, Splat, hoy, horaLocal, fechaLocal, instanteLocal, C, usados, agenda, slot, reservar, recepcionHastaLlamado, ses, iniciarSesion, tratar, cerrarSesion, atencionCompleta, PNG, multipart, sha256, J, ORIGEN, GUIA, guia, ZONA, setZona, AUDIT_RANGO } from './lib.mjs';

// 08 · Presentaciones a financiadores, facturacion y pagos
const pr = (id, p = '') => `/api/v1/consultorios/${C.sede_a}/presentaciones/${id}${p}`;
const elegiblesSql = () => q(`SELECT o.id FROM obligacion o WHERE o.organization_id = @org_a AND o.responsable = 'FINANCIADOR' AND o.financiador_id = @financiador
	AND o.estado IN ('PENDIENTE','PARCIAL') AND NOT EXISTS (SELECT 1 FROM presentacion_item i WHERE i.obligacion_id = o.id AND i.estado IN ('INCLUIDO','ACEPTADO')) ORDER BY o.id`).map((x) => n(x.id));
const lista = (x) => (Array.isArray(x) ? x : x?.content ?? []);

export async function c08() {
	await paso('PRE-01', 'Bandeja de prestaciones elegibles', async () => {
		need(C.financiador, C.obl_fin1, C.obl_fin2);
		const r = must(await api('GET', `/api/v1/consultorios/${C.sede_a}/prestaciones-elegibles?financiadorId=${C.financiador}`, { s: await S('adm') }), 200, 'bandeja');
		const api_ = lista(r).map((x) => x.id).sort((a, b) => a - b), db = elegiblesSql();
		ok(JSON.stringify(api_) === JSON.stringify(db) && db.includes(C.obl_fin1) && db.includes(C.obl_fin2), 'la bandeja coincide fila por fila con la base (las dos obligaciones del financiador)', `api: ${api_} / base: ${db}`);
		ok(lista(r).every((x) => x.responsable === 'FINANCIADOR' && x.convenio), 'solo obligaciones del financiador, con el convenio congelado', JSON.stringify(lista(r)).slice(0, 400));
	});
	await paso('PRE-02', 'Crear el borrador y agregar items', async () => {
		need(C.obl_fin1, C.obl_fin2);
		const d0 = maxAudit();
		const s = await S('adm');
		const cuerpo = { financiadorId: C.financiador, moneda: 'ARS', periodoDesde: `${hoy().slice(0, 8)}01`, periodoHasta: hoy(), obligacionIds: [C.obl_fin1, C.obl_fin2] };
		C.presentacion = V.presentacion = must(await api('POST', `/api/v1/consultorios/${C.sede_a}/presentaciones`, { s, json: cuerpo }), 201, 'borrador').id;
		tipo(await api('POST', `/api/v1/consultorios/${C.sede_a}/presentaciones`, { s, json: { ...cuerpo, obligacionIds: [C.obl_fin1] } }), 'obligacion-ya-presentada', 'la misma obligacion en otro borrador');
		const p = q('SELECT numero, estado, total_presentado, saldo FROM presentacion WHERE id = @presentacion');
		const i = q('SELECT id, obligacion_id, estado, importe_presentado, ocupa_marca FROM presentacion_item WHERE presentacion_id = @presentacion ORDER BY id');
		ok(p[0].estado === 'BORRADOR' && p[0].numero === null, 'BORRADOR sin numero', p);
		ok(i.length === 2 && i.every((x) => x.estado === 'INCLUIDO' && x.ocupa_marca === '1'), 'un item INCLUIDO por obligacion con ocupa_marca', i);
		const suma = i.reduce((a, x) => a + Number(x.importe_presentado), 0);
		ok(Number(p[0].total_presentado) === suma && Number(p[0].saldo) === suma, 'total_presentado = suma y saldo = total', p, i);
		C.items = Object.fromEntries(i.map((x) => [n(x.obligacion_id), n(x.id)]));
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'PRESENTACION_CREADA'), 'PRESENTACION_CREADA');
	});
	await paso('PRE-03', 'Revisar antes de confirmar', async () => {
		need(C.presentacion);
		const v0 = q1('SELECT version FROM presentacion WHERE id = @presentacion').version;
		const r = must(await api('GET', pr(C.presentacion, '/validacion'), { s: await S('adm') }), 200, 'revisar');
		ok(r.confirmable === true && Array.isArray(r.hallazgos), 'los hallazgos avisan y no bloquean (confirmable)', JSON.stringify(r).slice(0, 400));
		eq(q1('SELECT version FROM presentacion WHERE id = @presentacion').version, v0, 'solo lectura');
	});
	await paso('PRE-04', 'Confirmar: numero correlativo por financiador', async () => {
		need(C.presentacion);
		const d0 = maxAudit();
		const s = await S('adm');
		must(await api('POST', pr(C.presentacion, '/confirmacion'), { s }), 200, 'confirmar');
		const p = q('SELECT numero, estado, confirmada_en, confirmada_por_cuenta_id FROM presentacion WHERE id = @presentacion');
		const nm = q('SELECT financiador_id, ultimo_numero FROM presentacion_numerador WHERE consultorio_id = @sede_a');
		ok(p[0].estado === 'PRESENTADA' && p[0].numero && p[0].numero === nm.find((x) => n(x.financiador_id) === C.financiador)?.ultimo_numero, 'PRESENTADA con el numero del numerador de ese financiador', p, nm);
		st(await api('POST', pr(C.presentacion, '/items'), { s, json: { obligacionId: C.obl_fin1 } }), 409, 'ya no se agregan items');
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'PRESENTACION_CONFIRMADA'), 'PRESENTACION_CONFIRMADA');
	});
	await paso('PRE-05', 'Registrar la factura', async () => {
		need(C.presentacion);
		const d0 = maxAudit();
		const movs = q1('SELECT COUNT(*) n FROM movimiento_caja WHERE consultorio_id = @sede_a').n;
		must(await api('POST', pr(C.presentacion, '/factura'), { s: await S('adm'), json: { numero: 'FC-0001-QA', fecha: hoy() } }), 200, 'factura');
		const p = q('SELECT estado, factura_numero, factura_fecha, factura_registrada_por_cuenta_id FROM presentacion WHERE id = @presentacion');
		ok(p[0].estado === 'FACTURADA' && p[0].factura_numero === 'FC-0001-QA', 'FACTURADA', p);
		eq(q1('SELECT COUNT(*) n FROM movimiento_caja WHERE consultorio_id = @sede_a').n, movs, 'facturar no mueve caja');
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'PRESENTACION_FACTURADA'), 'PRESENTACION_FACTURADA');
	});
	await paso('PRE-06', 'Debitar un item: que la base diga lo mismo que la pantalla', async () => {
		need(C.items);
		const d0 = maxAudit();
		const s = await S('adm');
		const it1 = C.items[C.obl_fin1], it2 = C.items[C.obl_fin2];
		st(await api('POST', pr(C.presentacion, `/items/${it2}/debito`), { s, json: { importe: 999999, motivo: 'excesivo' } }), 400, 'un debito mayor que lo presentado');
		const r = must(await api('POST', pr(C.presentacion, `/items/${it1}/debito`), { s, json: { importe: 6000, motivo: '=1+1 Falta firma en la orden' } }), 200, 'debitar el total del item');
		eq(r.estado, 'DEBITADO', 'la respuesta dice DEBITADO');
		const i = q(`SELECT estado, importe_debitado, motivo_debito, debitado_por_cuenta_id, ocupa_marca FROM presentacion_item WHERE id = ${it1}`);
		ok(i[0].estado === 'DEBITADO' && Number(i[0].importe_debitado) === 6000 && i[0].motivo_debito && i[0].debitado_por_cuenta_id && i[0].ocupa_marca === null, 'DEBITADO en la base con importe, motivo, actor y sin ocupa_marca (defecto #7 si no)', i);
		const p = q('SELECT total_presentado, total_debitado, total_cobrado, saldo FROM presentacion WHERE id = @presentacion');
		ok(Number(p[0].total_debitado) === 6000 && Number(p[0].saldo) === Number(p[0].total_presentado) - 6000 - Number(p[0].total_cobrado), 'saldo = presentado - debitado - cobrado', p);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'PRESENTACION_ITEM_DEBITADO', (x) => x.reason), 'PRESENTACION_ITEM_DEBITADO con reason');
	});
	await paso('PRE-07', 'Registrar el pago del financiador', async () => {
		need(C.presentacion);
		const d0 = maxAudit();
		const s = await S('adm');
		const saldo = Number(q1('SELECT saldo FROM presentacion WHERE id = @presentacion').saldo);
		must(await api('POST', pr(C.presentacion, '/pagos'), { s, json: { importe: saldo, medio: 'TRANSFERENCIA', fechaPago: hoy(), referencia: 'TRF-OS-QA', idempotencyKey: randomUUID() } }), 201, 'pago por el saldo');
		st(await api('POST', pr(C.presentacion, '/pagos'), { s, json: { importe: 1, medio: 'TRANSFERENCIA', fechaPago: hoy(), idempotencyKey: randomUUID() } }), 409, 'pagar mas que el saldo');
		const p = q('SELECT total_cobrado, saldo, estado FROM presentacion WHERE id = @presentacion');
		eq(Number(p[0].saldo), 0, 'saldo del lote en 0', p);
		const m = q("SELECT jornada_caja_id, tipo, medio, importe, afecta_arqueo FROM movimiento_caja WHERE tipo_origen = 'PAGO_FINANCIADOR' AND referencia_origen IN (SELECT id FROM financiador_pago WHERE presentacion_id = @presentacion)");
		ok(m.length === 1 && m[0].tipo === 'INGRESO' && m[0].afecta_arqueo === '0', 'un INGRESO PAGO_FINANCIADOR que no afecta el arqueo', m);
		const o = q('SELECT id, saldo, estado FROM obligacion WHERE id IN (SELECT obligacion_id FROM presentacion_item WHERE presentacion_id = @presentacion)');
		ok(o.every((x) => x.estado === 'PENDIENTE'), 'las obligaciones todavia PENDIENTE', o);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'PRESENTACION_PAGO_REGISTRADO'), 'PRESENTACION_PAGO_REGISTRADO');
	});
	await paso('PRE-08', 'Conciliar: recien aca se salda la deuda', async () => {
		need(C.presentacion);
		const d0 = maxAudit();
		must(await api('POST', pr(C.presentacion, '/conciliacion'), { s: await S('adm') }), 200, 'conciliar');
		eq(q1('SELECT estado FROM presentacion WHERE id = @presentacion').estado, 'CONCILIADA', 'CONCILIADA');
		const f = q('SELECT i.id, i.estado AS item, o.id AS obligacion, o.saldo, o.estado FROM presentacion_item i JOIN obligacion o ON o.id = i.obligacion_id WHERE i.presentacion_id = @presentacion ORDER BY i.id');
		const ac = f.find((x) => n(x.obligacion) === C.obl_fin2), db = f.find((x) => n(x.obligacion) === C.obl_fin1);
		ok(ac?.item === 'ACEPTADO' && ac.estado === 'PAGADA' && Number(ac.saldo) === 0, 'el INCLUIDO pasa a ACEPTADO y su obligacion PAGADA', f);
		ok(db?.item === 'DEBITADO' && db.estado === 'PENDIENTE', 'el DEBITADO sigue y su obligacion sigue PENDIENTE', f);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'PRESENTACION_CONCILIADA'), 'PRESENTACION_CONCILIADA');
	});
	await paso('PRE-09', 'Anular un lote y la cuenta corriente del financiador', async () => {
		need(C.obl_fin1);
		const d0 = maxAudit();
		const s = await S('adm');
		const b = must(await api('POST', `/api/v1/consultorios/${C.sede_a}/presentaciones`, { s, json: { financiadorId: C.financiador, moneda: 'ARS', periodoDesde: `${hoy().slice(0, 8)}01`, periodoHasta: hoy(), obligacionIds: [C.obl_fin1] } }), 201, 'borrador con la obligacion debitada');
		must(await api('POST', pr(b.id, '/anulacion'), { s, json: { motivo: 'Borrador de prueba (pre-QA)' } }), 200, 'anular el borrador');
		st(await api('POST', pr(C.presentacion, '/anulacion'), { s, json: { motivo: 'x' } }), 409, 'un lote ya presentado no se anula');
		const p = q(`SELECT numero, estado, anulada_en, motivo_anulacion FROM presentacion WHERE id = ${b.id}`);
		ok(p[0].estado === 'ANULADA' && p[0].numero === null && p[0].motivo_anulacion, 'ANULADA sin numero y con motivo', p);
		const i = q(`SELECT estado, ocupa_marca FROM presentacion_item WHERE presentacion_id = ${b.id}`);
		ok(i.every((x) => x.estado === 'ANULADO' && x.ocupa_marca === null), 'los items ANULADO y la obligacion libre', i);
		ok(elegiblesSql().includes(C.obl_fin1), 'la obligacion vuelve a ser elegible');
		const cc = must(await api('GET', `/api/v1/consultorios/${C.sede_a}/financiadores/${C.financiador}/cuenta-corriente`, { s }), 200, 'cuenta corriente del financiador');
		const t = q("SELECT COUNT(*) lotes, SUM(total_presentado) presentado, SUM(total_debitado) debitado, SUM(total_cobrado) cobrado, SUM(saldo) saldo FROM presentacion WHERE organization_id = @org_a AND financiador_id = @financiador AND estado <> 'ANULADA'");
		ok(Number(cc.totalPresentado) === Number(t[0].presentado) && Number(cc.totalDebitado) === Number(t[0].debitado) && Number(cc.totalCobrado) === Number(t[0].cobrado) && Number(cc.saldo) === Number(t[0].saldo), 'la cuenta corriente suma lo mismo que la base', t, JSON.stringify(cc));
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'PRESENTACION_ANULADA'), 'PRESENTACION_ANULADA');
	});
	await paso('PRE-10', 'Exportar el lote a CSV', async () => {
		need(C.presentacion);
		const r = await api('GET', pr(C.presentacion, '/export'), { s: await S('adm'), headers: { Accept: 'text/csv, application/problem+json' } });
		st(r, 200, 'exportar');
		const numero = q1('SELECT numero FROM presentacion WHERE id = @presentacion').numero;
		ok(/text\/csv/i.test(r.headers.get('content-type') ?? '') && (r.headers.get('content-disposition') ?? '').includes(`presentacion-${C.financiador}-${numero}.csv`), 'text/csv y attachment con el nombre presentacion-<financiador>-<numero>.csv', `content-type: ${r.headers.get('content-type')}; content-disposition: ${r.headers.get('content-disposition')}`);
		const txt = r.buf.toString('utf8');
		const partes = txt.split(/\r?\n\r?\n/);
		const filas = (partes[1] ?? '').split(/\r?\n/).filter(Boolean);
		const items = q('SELECT COUNT(*) items, SUM(importe_presentado) presentado FROM presentacion_item WHERE presentacion_id = @presentacion');
		eq(filas.length - 1, n(items[0].items), 'una fila por item (mas el encabezado de columnas)');
		ok(!/Ana|30111222|Duplicada/.test(txt), 'PHI minima: sin nombre ni documento');
		ok(/'=1\+1/.test(txt), 'un motivo que empieza con = sale con apostrofo', txt.slice(0, 1200));
		st(await api('GET', pr(C.presentacion, '/export'), { s: await S('b'), headers: { Accept: 'text/csv, application/problem+json' } }), 404, 'con el token de B');
		st(await api('GET', pr(C.presentacion, '/export'), { s: await S('prof'), headers: { Accept: 'text/csv, application/problem+json' } }), 403, 'con el profesional');
		st(await api('GET', pr(999999, '/export'), { s: await S('adm'), headers: { Accept: 'text/csv, application/problem+json' } }), 404, 'id inexistente');
		nota(`suma esperada de "Importe presentado": ${items[0].presentado}`);
	});
	await paso('PRE-11', 'La bandeja de elegibles filtra por dia de la sede', async () => {
		need(C.obl_fin1);
		const s = await S('admin');
		const sede = must(await api('GET', `/api/v1/organizations/${C.org_a}/consultorios/${C.sede_a}`, { s }), 200, 'la sede');
		const otra = new Date().getUTCHours() >= 10 ? 'Pacific/Kiritimati' : 'Pacific/Pago_Pago';
		const mv = must(await api('PATCH', `/api/v1/organizations/${C.org_a}/consultorios/${C.sede_a}`, { s, json: { timezone: otra, version: sede.version } }), 200, `mover la sede a ${otra}`);
		try {
			const dev = q1(`SELECT DATE(devengada_en) dia_utc, DATE(CONVERT_TZ(devengada_en, '+00:00', ${lit(otra)})) dia_sede FROM obligacion WHERE id = ${C.obl_fin1}`);
			const en = async (d) => lista((await api('GET', `/api/v1/consultorios/${C.sede_a}/prestaciones-elegibles?financiadorId=${C.financiador}&desde=${d}&hasta=${d}`, { s })).cuerpo).some((x) => x.id === C.obl_fin1);
			ok(await en(dev.dia_sede), `con Desde = Hasta = ${dev.dia_sede} (dia de la sede) aparece`, JSON.stringify(dev));
			ok(dev.dia_sede === dev.dia_utc || !(await en(dev.dia_utc)), `con ${dev.dia_utc} (dia UTC) no aparece`, JSON.stringify(dev));
		} finally {
			await api('PATCH', `/api/v1/organizations/${C.org_a}/consultorios/${C.sede_a}`, { s, json: { timezone: sede.timezone, version: mv.version } });
		}
	});
}
