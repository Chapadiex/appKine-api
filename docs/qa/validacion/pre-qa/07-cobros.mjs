import { randomUUID } from 'node:crypto';
import { APIC, MAILC, RED, API, MAILPIT, P, MYSQL, DB, PLATAFORMA, AQUI, PASSWORD, PASSWORD2, SALIDA, SALIDA_JSON, SOLO, suf, dormir, UA, inicioCorrida, Corte, Bloqueo, R, cur, CIRC, paso, ultimaSql, ok, eq, st, tipo, must, need, nota, manual, soloManual, n, SECRETO, limpiar, descr, api, galletas, cookieRt, V, lit, unesc, q, q1, fmtSql, ahoraDb, maxAudit, auditDesde, tiene, cuenta, det, docker, correos, textoCorreo, esperarCorreos, tokenDe, tokenCorreo, ES_ACTIVACION, ES_INVITACION, pw, correo, registrar, activar, login, entrar, CU, SES, S, Splat, hoy, horaLocal, fechaLocal, instanteLocal, C, usados, agenda, slot, reservar, recepcionHastaLlamado, ses, iniciarSesion, tratar, cerrarSesion, atencionCompleta, PNG, multipart, sha256, J, ORIGEN, GUIA, guia, ZONA, setZona, AUDIT_RANGO } from './lib.mjs';

// 07 · Obligaciones, cobros, anulacion, reintegro y caja; 12 · egresos (corren con la caja abierta,
// antes de ECO-12); y PER-12/PER-15, que dependen de la anulacion de ECO-06.
const cs = (p = '') => `/api/v1/consultorios/${C.sede_a}${p}`;
const cobrar = async (json, s) => api('POST', cs('/cobros'), { s: s ?? (await S('adm')), json: { moneda: 'ARS', idempotencyKey: randomUUID(), ...json } });
const invariantes = () => q(`SELECT c.id, c.total, c.saldo_a_favor,
	(SELECT COALESCE(SUM(m.importe), 0) FROM cobro_medio m WHERE m.cobro_id = c.id) AS suma_medios,
	(SELECT COALESCE(SUM(i.importe), 0) FROM cobro_imputacion i WHERE i.cobro_id = c.id) AS suma_imputaciones,
	(SELECT COALESCE(SUM(r.importe), 0) FROM cobro_reintegro r WHERE r.cobro_id = c.id) AS suma_reintegros
	FROM cobro c WHERE c.organization_id = @org_a AND c.deleted_at IS NULL
	HAVING suma_medios <> c.total OR suma_imputaciones + suma_reintegros + c.saldo_a_favor <> c.total`);

export async function c07() {
	await paso('ECO-02', 'DP-17: sin precio particular, la sesion no cierra', async () => {
		need(C.servicio, C.mprof);
		const a = await S('admin');
		C.oferta2 = must(await api('POST', cs('/ofertas'), { s: a, json: { servicioId: C.servicio, nombreComercial: `Sin precio QA ${suf}`, capacidad: 1, duracionMinutos: 30, requiereProfesional: true, requiereEspacio: false, admiteObraSocial: false } }), 201, 'oferta sin precio').id;
		const at = await atencionCompleta(C.persona_part, { particular: 'Sin precio (pre-QA)', oferta: C.oferta2, cerrar: false });
		if (at.error) { st(at.error, [200, 201], 'preparar la atencion'); return; }
		const sid = at.sesion.id;
		const hc = n(q1(`SELECT historia_clinica_id h FROM sesion WHERE id = ${sid}`).h);
		const numAntes = q1(`SELECT ultimo_numero u FROM sesion_numerador WHERE historia_clinica_id = ${hc}`).u;
		const r = await cerrarSesion(sid);
		st(r, 409, 'cerrar sin precio');
		tipo(r, 'oferta-sin-precio', 'cerrar sin precio');
		ok(/precio/i.test(r.cuerpo?.detail ?? ''), 'el mensaje dice que falta el precio', r);
		const f = q(`SELECT id, estado, numero_sesion, cerrada_en FROM sesion WHERE id = ${sid}`);
		ok(f[0].estado === 'BORRADOR' && f[0].numero_sesion === null, 'sigue BORRADOR sin numero', f);
		eq(q1(`SELECT COUNT(*) n FROM obligacion WHERE sesion_id = ${sid}`).n, 0, 'cero obligaciones');
		eq(q1(`SELECT ultimo_numero u FROM sesion_numerador WHERE historia_clinica_id = ${hc}`).u, numAntes, 'el numerador sin avanzar');
		must(await api('POST', cs(`/ofertas/${C.oferta2}/precios-particulares`), { s: a, json: { importe: 5000, moneda: 'ARS', vigenciaDesde: hoy() } }), 201, 'cargar el precio');
		st(await cerrarSesion(sid), 200, 'ahora cierra');
	});
	await paso('ECO-03', 'Prepago imputado al cerrar', async () => {
		need(C.turno_prepago, C.cobro_prepago, C.obl_part);
		const adm = await S('adm');
		must(await recepcionHastaLlamado(adm, C.turno_prepago, { particular: 'Prepago (pre-QA)' }), 200, 'recepcion del turno con prepago');
		const si = must(await iniciarSesion(C.turno_prepago), 201, 'iniciar');
		must(await tratar(si.id, C.practica), 201, 'tratamiento');
		must(await cerrarSesion(si.id), 200, 'cerrar');
		const f = q(`SELECT c.id AS cobro, c.total, c.saldo_a_favor, i.obligacion_id, i.importe, o.saldo AS saldo_deuda, o.estado, o.sesion_id
			FROM cobro c LEFT JOIN cobro_imputacion i ON i.cobro_id = c.id LEFT JOIN obligacion o ON o.id = i.obligacion_id WHERE c.turno_id = @turno_prepago`);
		ok(f.length === 1 && n(f[0].sesion_id) === si.id && Number(f[0].importe) === 10000 && f[0].estado === 'PAGADA', 'el anticipo imputado solo a la deuda de esa sesion', f);
		ok(Number(f[0]?.saldo_a_favor) === 2000, 'lo que sobra (2000) queda a favor', f);
		eq(Number(q1(`SELECT saldo s FROM obligacion WHERE id = ${C.obl_part}`).s), 10000, 'la deuda vieja (CLI-06) no se toca');
	});
	await paso('ECO-05', 'Cobrar el coseguro en efectivo', async () => {
		need(C.obl_cos1, C.jornada);
		const d0 = maxAudit();
		const k = randomUUID();
		const cuerpo = { personaId: C.persona, total: 2000, medios: [{ medio: 'EFECTIVO', importe: 2000 }], imputaciones: [{ obligacionId: C.obl_cos1, importe: 2000 }], idempotencyKey: k };
		const [r1, r2] = await Promise.all([cobrar(cuerpo), cobrar(cuerpo)]);
		const gano = [r1, r2].find((x) => x.status === 201);
		ok([200, 201].includes(r1.status) && [200, 201].includes(r2.status) && r1.cuerpo.id === r2.cuerpo?.id, 'doble click simultaneo con la misma idempotencyKey: los dos reciben el mismo cobro', r1, r2);
		need(gano);
		C.cobro = V.cobro = gano.cuerpo.id;
		const r3 = await cobrar(cuerpo);
		ok([200, 201].includes(r3.status) && r3.cuerpo?.id === C.cobro, 'reintento posterior con la misma clave: el mismo cobro', r3);
		eq(q1(`SELECT COUNT(*) n FROM cobro WHERE idempotency_key = ${lit(k)}`).n, 1, 'un solo cobro en la base');
		const c = q('SELECT id, total, saldo_a_favor, comprobante_numero, cobrado_por_cuenta_id, idempotency_key FROM cobro WHERE id = @cobro');
		const med = q('SELECT medio, importe FROM cobro_medio WHERE cobro_id = @cobro');
		ok(med.length === 1 && Number(med[0].importe) === Number(c[0].total), 'SUM(medios) = total', med);
		const o = q(`SELECT saldo, estado FROM obligacion WHERE id = ${C.obl_cos1}`);
		ok(o[0].estado === 'PAGADA' && Number(o[0].saldo) === 0, 'el coseguro PAGADA con saldo 0', o);
		const m = q("SELECT tipo, medio, importe, tipo_origen, afecta_arqueo FROM movimiento_caja WHERE tipo_origen = 'COBRO' AND referencia_origen = @cobro");
		ok(m.length === 1 && m[0].tipo === 'INGRESO' && m[0].medio === 'EFECTIVO' && m[0].afecta_arqueo === '1', 'un INGRESO COBRO en efectivo que afecta el arqueo', m);
		const num = q('SELECT ultimo_numero FROM comprobante_numerador WHERE consultorio_id = @sede_a');
		eq(c[0].comprobante_numero, num[0]?.ultimo_numero, 'comprobante_numero = ultimo del numerador', num);
		eq(invariantes().length, 0, 'ningun cobro rompe las invariantes');
		eq(cuenta(auditDesde(d0, 'AND organization_id = @org_a'), 'COBRO_REGISTRADO'), 1, 'un solo COBRO_REGISTRADO (el reintento no audita)');
	});
	await paso('ECO-06', 'Anular una deuda', async () => {
		need(C.obl_cos2, C.obl_cos1);
		const d0 = maxAudit();
		const adm = await S('adm');
		const anular = async (id) => { const o = (await api('GET', cs(`/obligaciones/${id}`), { s: adm })).cuerpo; return api('DELETE', cs(`/obligaciones/${id}`), { s: adm, json: { motivo: 'Error de carga (pre-QA)', version: o?.version } }); };
		C.consumidaAntesAnular = n(q1('SELECT cantidad_consumida c FROM autorizacion WHERE id = @autorizacion').c);
		must(await anular(C.obl_cos2), 200, 'anular el coseguro sin cobros de la sesion del caso');
		st(await anular(C.obl_cos1), 409, 'una deuda con un cobro imputado no se anula');
		const f = q(`SELECT id, estado, saldo, anulada_en, anulada_por_cuenta_id, motivo_anulacion, deleted_at FROM obligacion WHERE id = ${C.obl_cos2}`);
		ok(f[0]?.estado === 'ANULADA' && f[0].motivo_anulacion && f[0].anulada_por_cuenta_id, 'ANULADA con motivo y actor; la fila sigue', f);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'OBLIGACION_ANULADA', (x) => x.reason), 'OBLIGACION_ANULADA con reason');
	});
	await paso('PER-15', '"Consumos a revisar" en cada fila del listado', async () => {
		need(C.autorizacion);
		const s = await S('adm');
		const l = must(await api('GET', `/api/v1/personas/${C.persona}/autorizaciones`, { s }), 200, 'listado');
		const fila = (Array.isArray(l) ? l : l.content ?? []).find((x) => x.id === C.autorizacion);
		const sql = q(`SELECT a.id, (SELECT COUNT(*) FROM autorizacion_alerta al WHERE al.autorizacion_id = a.id AND al.organization_id = a.organization_id AND al.tipo = 'CONSUMO_A_REVISAR' AND al.resuelta_en IS NULL) AS consumos_a_revisar
			FROM autorizacion a WHERE a.organization_id = @org_a AND a.persona_id = @persona ORDER BY a.id`);
		const sal = (await api('GET', `/api/v1/autorizaciones/${C.autorizacion}/saldo`, { s })).cuerpo;
		ok(fila?.consumosARevisar === 1 && sql.find((x) => n(x.id) === C.autorizacion)?.consumos_a_revisar === '1' && sal?.consumosARevisar === 1, 'listado, saldo y base dicen 1 consumo a revisar', sql, JSON.stringify({ fila: fila?.consumosARevisar, saldo: sal?.consumosARevisar }));
		manual('la etiqueta "1 consumo a revisar" de la columna Saldo');
	});
	await paso('PER-12', 'Anular la deuda deja una alerta; la reversion es manual (DP-13)', async () => {
		need(C.autorizacion, C.sesion_caso);
		const d0 = maxAudit();
		const s = await S('adm');
		const al = q('SELECT id, tipo, sesion_id, obligacion_id, resuelta_en FROM autorizacion_alerta WHERE autorizacion_id = @autorizacion ORDER BY id');
		ok(al.length === 1 && al[0].tipo === 'CONSUMO_A_REVISAR' && al[0].resuelta_en === null && n(al[0].sesion_id) === C.sesion_caso, 'una alerta CONSUMO_A_REVISAR sin resolver', al);
		eq(n(q1('SELECT cantidad_consumida c FROM autorizacion WHERE id = @autorizacion').c), C.consumidaAntesAnular, 'cantidad_consumida igual que antes');
		st(await api('GET', `/api/v1/autorizaciones/${C.autorizacion}/alertas`, { s }), 200, 'alertas');
		const mov = n(q1(`SELECT id FROM autorizacion_movimiento WHERE autorizacion_id = @autorizacion AND tipo = 'CONSUMO' AND referencia_origen = ${C.sesion_caso}`).id);
		const rev = (motivo) => api('POST', `/api/v1/autorizaciones/${C.autorizacion}/reversiones`, { s, json: { movimientoId: mov, motivo } });
		tipo(await rev(''), 'reversion-sin-motivo', 'revertir sin motivo');
		must(await rev('Pre-QA: la sesion se anulo por error de carga'), 201, 'revertir');
		tipo(await rev('otra vez'), 'movimiento-ya-revertido', 'revertir dos veces');
		const m = q('SELECT id, tipo, movimiento_origen_id, motivo FROM autorizacion_movimiento WHERE autorizacion_id = @autorizacion ORDER BY id');
		ok(m.some((x) => x.tipo === 'REVERSION' && n(x.movimiento_origen_id) === mov), 'un REVERSION que apunta al CONSUMO', m);
		eq(n(q1('SELECT cantidad_consumida c FROM autorizacion WHERE id = @autorizacion').c), C.consumidaAntesAnular - 1, 'cantidad_consumida -1');
		const al2 = q('SELECT resuelta_en, resolucion FROM autorizacion_alerta WHERE autorizacion_id = @autorizacion');
		ok(al2[0]?.resolucion === 'REVERTIDO' && al2[0].resuelta_en, 'la alerta resuelta REVERTIDO', al2);
		ok(q("SELECT 1 FROM autorizacion_evento WHERE autorizacion_id = @autorizacion AND tipo = 'REVERSION_DE_CONSUMO'").length === 1, 'evento REVERSION_DE_CONSUMO');
		const l = q(`SELECT a.id FROM autorizacion a LEFT JOIN autorizacion_movimiento m ON m.autorizacion_id = a.id WHERE a.organization_id = @org_a GROUP BY a.id, a.cantidad_consumida
			HAVING a.cantidad_consumida <> COALESCE(SUM(CASE m.tipo WHEN 'CONSUMO' THEN m.cantidad WHEN 'REVERSION' THEN -m.cantidad ELSE 0 END), 0)`);
		eq(l.length, 0, 'el ledger sigue cuadrando');
		const sal = (await api('GET', `/api/v1/autorizaciones/${C.autorizacion}/saldo`, { s })).cuerpo;
		ok(sal?.coherente === true && sal?.consumosARevisar === 0, 'el saldo coherente y sin consumos a revisar (PER-15: la etiqueta desaparece)', JSON.stringify(sal));
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(tiene(au, 'AUTORIZACION_CONSUMO_REVERTIDO'), 'AUTORIZACION_CONSUMO_REVERTIDO', au);
		ok(q("SELECT 1 FROM audit_event WHERE organization_id = @org_a AND event_type = 'AUTORIZACION_CONSUMO_A_REVISAR'").length >= 1, 'AUTORIZACION_CONSUMO_A_REVISAR (al anular)');
	});
	await paso('ECO-07', 'Cobro parcial', async () => {
		need(C.obl_part);
		const r = must(await cobrar({ personaId: C.persona_part, total: 4000, medios: [{ medio: 'TRANSFERENCIA', importe: 4000, referencia: 'TRF-QA-1' }], imputaciones: [{ obligacionId: C.obl_part, importe: 4000 }] }), 201, 'cobrar 4000 por transferencia');
		C.cobro_parcial = r.id;
		const o = q(`SELECT importe_original, saldo, estado FROM obligacion WHERE id = ${C.obl_part}`);
		ok(o[0].estado === 'PARCIAL' && Number(o[0].saldo) === 6000, 'PARCIAL con saldo 6000', o);
		const m = q(`SELECT tipo, medio, importe, afecta_arqueo FROM movimiento_caja WHERE tipo_origen = 'COBRO' AND referencia_origen = ${r.id}`);
		ok(m.length === 1 && m[0].medio === 'TRANSFERENCIA' && m[0].afecta_arqueo === '0', 'el movimiento de transferencia no afecta el arqueo', m);
	});
	await paso('ECO-08', 'Anticipo e imputacion posterior', async () => {
		need(C.obl_part);
		const d0 = maxAudit();
		const s = await S('adm');
		const c = must(await cobrar({ personaId: C.persona_part, total: 3000, anticipo: 3000, medios: [{ medio: 'EFECTIVO', importe: 3000 }] }), 201, 'anticipo de 3000');
		eq(Number(c.saldoAFavor), 3000, 'nace con saldo a favor 3000');
		must(await api('POST', cs(`/cobros/${c.id}/imputaciones`), { s, json: { obligacionId: C.obl_part, importe: 3000, idempotencyKey: randomUUID() } }), 200, 'imputar 3000');
		tipo(await api('POST', cs(`/cobros/${c.id}/imputaciones`), { s, json: { obligacionId: C.obl_part, importe: 1, idempotencyKey: randomUUID() } }), 'saldo-a-favor-insuficiente', 'imputar mas que el saldo a favor');
		const f = q(`SELECT total, saldo_a_favor FROM cobro WHERE id = ${c.id}`);
		const i = q(`SELECT obligacion_id, importe, imputada_por_cuenta_id FROM cobro_imputacion WHERE cobro_id = ${c.id}`);
		const o = q(`SELECT saldo, estado FROM obligacion WHERE id = ${C.obl_part}`);
		ok(Number(f[0].saldo_a_favor) === 0 && i.length === 1 && Number(o[0].saldo) === 3000, 'saldo a favor 0, una imputacion, la deuda en 3000', f, i, o);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'COBRO_SALDO_IMPUTADO'), 'COBRO_SALDO_IMPUTADO');
	});
	await paso('ECO-09', 'Reintegrar saldo a favor', async () => {
		const d0 = maxAudit();
		const c = must(await cobrar({ personaId: C.persona_part, total: 1000, anticipo: 1000, medios: [{ medio: 'EFECTIVO', importe: 1000 }] }), 201, 'anticipo de 1000');
		C.cobro_reintegrado = c.id;
		must(await api('POST', cs(`/cobros/${c.id}/reintegros`), { s: await S('adm'), json: { importe: 400, medio: 'EFECTIVO', motivo: 'Devolucion (pre-QA)', idempotencyKey: randomUUID() } }), 201, 'reintegrar 400');
		eq(Number(q1(`SELECT saldo_a_favor s FROM cobro WHERE id = ${c.id}`).s), 600, 'saldo a favor 600');
		const m = q("SELECT tipo, medio, importe, tipo_origen FROM movimiento_caja WHERE tipo_origen = 'REINTEGRO' AND consultorio_id = @sede_a");
		ok(m.length === 1 && m[0].tipo === 'EGRESO' && Number(m[0].importe) === 400, 'un EGRESO con tipo_origen REINTEGRO', m);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'COBRO_REINTEGRADO'), 'COBRO_REINTEGRADO');
	});
	await paso('ECO-10', 'Anular un cobro', async () => {
		need(C.cobro_parcial);
		const d0 = maxAudit();
		const s = await S('adm');
		must(await api('POST', cs(`/cobros/${C.cobro_parcial}/anulacion`), { s, json: { motivo: 'Transferencia rebotada (pre-QA)' } }), 200, 'anular el cobro de ECO-07');
		tipo(await api('POST', cs(`/cobros/${C.cobro_reintegrado}/anulacion`), { s, json: { motivo: 'x' } }), 'cobro-con-reintegros', 'anular el cobro con reintegro');
		const c = q(`SELECT id, deleted_at, anulado_por_cuenta_id, motivo_anulacion FROM cobro WHERE id = ${C.cobro_parcial}`);
		ok(c[0].deleted_at && c[0].motivo_anulacion, 'baja logica con motivo', c);
		eq(Number(q1(`SELECT saldo s FROM obligacion WHERE id = ${C.obl_part}`).s), 7000, 'la deuda vuelve a tener lo que ese cobro habia cancelado (3000 + 4000)');
		const orig = n(q1(`SELECT id FROM movimiento_caja WHERE tipo_origen = 'COBRO' AND referencia_origen = ${C.cobro_parcial} AND tipo = 'INGRESO'`).id);
		const rv = q(`SELECT id, tipo, movimiento_origen_id FROM movimiento_caja WHERE tipo = 'REVERSION_DE_INGRESO' AND movimiento_origen_id = ${orig ?? 0}`);
		eq(rv.length, 1, 'una REVERSION_DE_INGRESO que apunta al movimiento original', rv);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(tiene(au, 'COBRO_ANULADO', (x) => x.reason && det(x).movimientosRevertidos !== undefined && det(x).imputacionesRevertidas !== undefined), 'COBRO_ANULADO con movimientosRevertidos e imputacionesRevertidas', au);
		const l = must(await api('GET', cs(`/cobros?personaId=${C.persona_part}`), { s }), 200, 'listado de cobros');
		const lista = Array.isArray(l) ? l : l.content ?? [];
		const db = q('SELECT id, comprobante_numero, deleted_at IS NOT NULL AS anulado FROM cobro WHERE persona_id = @persona_part ORDER BY cobrado_en DESC, id DESC');
		ok(lista.length === db.length && lista.every((x, i) => x.id === n(db[i].id) && (x.estado === 'ANULADO') === (db[i].anulado === '1')), 'el listado trae todos, en orden, con el anulado marcado (regresion de #101 si falta)', db, JSON.stringify(lista.map((x) => [x.id, x.estado])));
		const nums = q('SELECT comprobante_numero n FROM cobro WHERE consultorio_id = @sede_a ORDER BY comprobante_numero').map((x) => n(x.n));
		ok(nums.every((x, i) => x === i + 1), 'la numeracion de comprobantes de la sede no tiene huecos', nums.join(','));
	});
	await paso('ECO-11', 'Movimiento manual y reversion', async () => {
		need(C.jornada);
		const d0 = maxAudit();
		const s = await S('adm');
		const m = must(await api('POST', cs('/caja/movimientos'), { s, json: { tipo: 'EGRESO', medio: 'EFECTIVO', importe: 500, concepto: 'Compra de insumos QA', idempotencyKey: randomUUID() } }), 201, 'egreso manual');
		must(await api('POST', cs(`/caja/movimientos/${m.id}/reversion`), { s, json: { motivo: 'Cargado por error (pre-QA)' } }), 201, 'revertir');
		const f = q("SELECT id, tipo, importe, concepto, motivo, tipo_origen, movimiento_origen_id FROM movimiento_caja WHERE jornada_caja_id = @jornada AND tipo_origen IN ('MANUAL','REVERSION') ORDER BY id");
		ok(f.some((x) => n(x.id) === m.id && x.tipo === 'EGRESO') && f.some((x) => x.tipo === 'REVERSION_DE_EGRESO' && n(x.movimiento_origen_id) === m.id && x.motivo), 'el EGRESO MANUAL y su REVERSION_DE_EGRESO con motivo', f);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(tiene(au, 'CAJA_MOVIMIENTO_MANUAL') && tiene(au, 'CAJA_MOVIMIENTO_REVERTIDO', (x) => x.reason), 'CAJA_MOVIMIENTO_MANUAL y CAJA_MOVIMIENTO_REVERTIDO con reason', au);
	});
	await c12();
	await paso('ECO-13', 'Egresos por la API (el camino de 07)', async () => {
		need(C.jornada);
		const s = await S('admin');
		const e = must(await api('POST', cs('/egresos'), { s, json: { categoria: 'INSUMOS', tipoBeneficiario: 'EXTERNO', beneficiarioNombre: 'Proveedor QA ECO', concepto: 'Vendas QA', importeTotal: 1500, moneda: 'ARS', comprobanteTipo: 'FACTURA_B', comprobanteNumero: 'B-0002-QA', comprobanteFecha: hoy(), idempotencyKey: randomUUID() } }), 201, 'egreso');
		must(await api('POST', cs(`/egresos/${e.id}/confirmacion`), { s }), 200, 'confirmar');
		const pg = must(await api('POST', cs(`/egresos/${e.id}/pagos`), { s, json: { importe: 1500, medio: 'EFECTIVO', idempotencyKey: randomUUID() } }), 201, 'pagar en efectivo');
		eq(q1(`SELECT estado FROM egreso WHERE id = ${e.id}`).estado, 'PAGADO', 'PAGADO');
		must(await api('POST', cs(`/egresos/${e.id}/pagos/${pg.id}/anulacion`), { s, json: { motivo: 'QA G-12: pago duplicado' } }), 200, 'anular el pago');
		const f = q(`SELECT estado, saldo_pendiente FROM egreso WHERE id = ${e.id}`);
		ok(f[0].estado === 'CONFIRMADO' && Number(f[0].saldo_pendiente) === 1500, 'vuelve a CONFIRMADO con saldo 1500', f);
		C.egreso_eco13 = e.id;
	});
	await paso('ECO-12', 'Cerrar la caja con arqueo', async () => {
		need(C.jornada);
		const d0 = maxAudit();
		const s = await S('adm');
		const t = q(`SELECT j.saldo_inicial, j.saldo_arqueo AS teorico_guardado, j.saldo_inicial + COALESCE(SUM(CASE WHEN m.tipo IN ('INGRESO', 'REVERSION_DE_EGRESO') THEN m.importe ELSE -m.importe END), 0) AS teorico_calculado
			FROM jornada_caja j LEFT JOIN movimiento_caja m ON m.jornada_caja_id = j.id AND m.afecta_arqueo = 1 WHERE j.id = @jornada GROUP BY j.id, j.saldo_inicial, j.saldo_arqueo`);
		const j = must(await api('GET', cs(`/caja/jornadas/${C.jornada}`), { s }), 200, 'la jornada');
		ok(Number(t[0].teorico_guardado) === Number(t[0].teorico_calculado) && Number(j.saldoTeorico) === Number(t[0].teorico_calculado), 'el teorico guardado = calculado desde los movimientos = el de la pantalla', t, `pantalla: ${j.saldoTeorico}`);
		const cerrar = (esperado) => api('POST', cs(`/caja/jornadas/${C.jornada}/cierre`), { s, json: { saldoTeoricoEsperado: esperado, saldoDeclarado: Number(j.saldoTeorico) - 100, motivoDiferencia: 'Faltan 100 (pre-QA)' } });
		tipo(await cerrar(Number(j.saldoTeorico) + 1), 'caja-saldo-cambio', 'cerrar con un teorico desactualizado');
		must(await cerrar(Number(j.saldoTeorico)), 200, 'cerrar declarando 100 menos');
		const f = q('SELECT estado, saldo_teorico_cierre, saldo_declarado, diferencia, motivo_diferencia FROM jornada_caja WHERE id = @jornada');
		ok(f[0].estado === 'CERRADA' && Number(f[0].diferencia) === -100 && f[0].motivo_diferencia, 'CERRADA con diferencia -100 y motivo', f);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'CAJA_CERRADA'), 'CAJA_CERRADA');
		const a = await S('admin');
		tipo(await api('POST', cs(`/egresos/${C.egreso_eco13}/pagos`), { s: a, json: { importe: 100, medio: 'EFECTIVO', idempotencyKey: randomUUID() } }), 'caja-no-abierta', 'ECO-13/EGR-03: con la caja cerrada, un pago en efectivo');
		st(await api('POST', cs(`/egresos/${C.egreso_eco13}/pagos`), { s: a, json: { importe: 100, medio: 'TRANSFERENCIA', referencia: 'TRF-CERRADA', idempotencyKey: randomUUID() } }), 201, 'ECO-13/EGR-04: por transferencia pasa con la caja cerrada');
	});
	await paso('ECO-14', 'Auditoria de devengo, cobro y anulacion (api #86)', async () => {
		const a = q(`SELECT a.event_type, a.entity_id, a.actor_account_id, a.reason, a.details FROM audit_event a WHERE a.organization_id = @org_a AND a.occurred_at >= @desde
			AND a.event_type IN ('OBLIGACION_DEVENGADA','OBLIGACION_ANULADA','COBRO_REGISTRADO','COBRO_ANULADO','COBRO_SALDO_IMPUTADO','COBRO_REINTEGRADO') ORDER BY a.id`);
		const ar = q(`SELECT (SELECT COUNT(*) FROM obligacion WHERE organization_id = @org_a AND devengada_en >= @desde) AS obligaciones,
			(SELECT COUNT(*) FROM audit_event WHERE organization_id = @org_a AND occurred_at >= @desde AND event_type = 'OBLIGACION_DEVENGADA') AS devengos_auditados,
			(SELECT COUNT(*) FROM cobro WHERE organization_id = @org_a AND cobrado_en >= @desde) AS cobros,
			(SELECT COUNT(*) FROM audit_event WHERE organization_id = @org_a AND occurred_at >= @desde AND event_type = 'COBRO_REGISTRADO') AS cobros_auditados`);
		ok(ar[0].obligaciones === ar[0].devengos_auditados && ar[0].cobros === ar[0].cobros_auditados, 'cada devengo y cada cobro tiene su evento (alto si falla)', ar);
		const prof = n(q1('SELECT id FROM cuenta WHERE email_normalizado = @email_profesional').id);
		const dev = a.filter((x) => x.event_type === 'OBLIGACION_DEVENGADA');
		ok(dev.length > 0 && dev.every((x) => n(x.actor_account_id) === prof && det(x).concepto && det(x).responsable && det(x).importe && det(x).sesionId), 'OBLIGACION_DEVENGADA a nombre del profesional con concepto, responsable, importe y sesionId', dev);
		ok(dev.filter((x) => det(x).responsable === 'FINANCIADOR').every((x) => det(x).financiadorId), 'la del financiador trae financiadorId', dev);
		ok(a.filter((x) => x.event_type === 'COBRO_REGISTRADO').every((x) => det(x).total && det(x).comprobanteNumero), 'COBRO_REGISTRADO con total y comprobanteNumero', a);
		ok(a.filter((x) => ['OBLIGACION_ANULADA', 'COBRO_ANULADO'].includes(x.event_type)).every((x) => x.reason), 'las anulaciones con reason', a);
	});
}

// =================================================================== 12 · egresos y pagos
async function c12() {
	const eg = (id, p = '') => cs(`/egresos/${id}${p}`);
	await paso('EGR-01', 'Alta de un egreso a un proveedor externo', async () => {
		const d0 = maxAudit();
		const s = await S('adm');
		const k = randomUUID();
		const cuerpo = { categoria: 'INSUMOS', tipoBeneficiario: 'EXTERNO', beneficiarioNombre: 'Proveedor QA', concepto: 'Vendas QA', importeTotal: 1500, moneda: 'ARS', comprobanteTipo: 'FACTURA_B', comprobanteNumero: 'B-0001-QA', comprobanteFecha: hoy(), idempotencyKey: k };
		const r1 = must(await api('POST', cs('/egresos'), { s, json: cuerpo }), 201, 'registrar');
		const r2 = await api('POST', cs('/egresos'), { s, json: cuerpo });
		ok([200, 201].includes(r2.status) && r2.cuerpo.id === r1.id, 'doble click: un solo egreso', r2);
		C.egreso = V.egreso = r1.id;
		const d = await api('POST', cs('/egresos'), { s, json: { ...cuerpo, idempotencyKey: randomUUID() } });
		tipo(d, 'egreso-comprobante-duplicado', 'el mismo comprobante del mismo beneficiario');
		ok(JSON.stringify(d.cuerpo).includes(String(r1.id)), 'el mensaje nombra el egreso que ya lo tiene', d);
		const f = q('SELECT estado, importe_total, saldo_pendiente, beneficiario_membership_id, registrado_por_cuenta_id, idempotency_key FROM egreso WHERE id = @egreso');
		ok(f[0].estado === 'BORRADOR' && f[0].saldo_pendiente === f[0].importe_total && f[0].beneficiario_membership_id === null && n(f[0].registrado_por_cuenta_id) === n(q1('SELECT id FROM cuenta WHERE email_normalizado = @email_administrativo').id) && f[0].idempotency_key, 'BORRADOR, saldo = importe, sin colaborador, registrado por el administrativo, con clave', f);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'EGRESO_REGISTRADO'), 'EGRESO_REGISTRADO');
	});
	await paso('EGR-02', 'Borrador sin comprobante: se guarda, no se confirma', async () => {
		const d0 = maxAudit();
		const s = await S('adm');
		const e = must(await api('POST', cs('/egresos'), { s, json: { categoria: 'SERVICIOS', tipoBeneficiario: 'EXTERNO', beneficiarioNombre: 'Limpieza QA', concepto: 'Limpieza', importeTotal: 800, moneda: 'ARS', idempotencyKey: randomUUID() } }), 201, 'alta sin comprobante');
		const c = await api('POST', eg(e.id, '/confirmacion'), { s });
		st(c, 400, 'confirmar sin comprobante');
		tipo(c, 'egreso-sin-comprobante', 'confirmar sin comprobante');
		const f = q(`SELECT estado, confirmado_en FROM egreso WHERE id = ${e.id}`);
		ok(f[0].estado === 'BORRADOR' && f[0].confirmado_en === null, 'sigue BORRADOR', f);
		const ed = must(await api('PUT', eg(e.id), { s, json: { categoria: 'SERVICIOS', concepto: 'Limpieza', importeTotal: 800, comprobanteTipo: 'FACTURA_C', comprobanteNumero: `C-${suf}`, comprobanteFecha: hoy(), version: e.version } }), 200, 'corregir con el comprobante');
		must(await api('POST', eg(e.id, '/confirmacion'), { s }), 200, 'ahora confirma');
		const v = n(q1(`SELECT version FROM egreso WHERE id = ${e.id}`).version);
		tipo(await api('PUT', eg(e.id), { s, json: { categoria: 'SERVICIOS', concepto: 'x', importeTotal: 800, version: v } }), 'egreso-no-editable', 'editar un confirmado');
		const au = auditDesde(d0, `AND organization_id = @org_a AND entity_id = ${e.id}`);
		ok(tiene(au, 'EGRESO_CONFIRMADO') && !au.some((x) => /EDIT|CORREG|UPDATED/.test(x.event_type)), 'confirmar deja EGRESO_CONFIRMADO; la correccion no deja evento propio', au);
		nota(`la correccion subio la version a ${ed.version}`);
	});
	await paso('EGR-03', 'Confirmar y pagar en efectivo', async () => {
		need(C.egreso, C.jornada);
		const d0 = maxAudit();
		const s = await S('adm');
		must(await api('POST', eg(C.egreso, '/confirmacion'), { s }), 200, 'confirmar');
		const p = must(await api('POST', eg(C.egreso, '/pagos'), { s, json: { importe: 1500, medio: 'EFECTIVO', idempotencyKey: randomUUID() } }), 201, 'pagar 1500 en efectivo');
		C.pago_egreso = p.id;
		const f = q('SELECT estado, saldo_pendiente, confirmado_en FROM egreso WHERE id = @egreso');
		ok(f[0].estado === 'PAGADO' && Number(f[0].saldo_pendiente) === 0, 'PAGADO con saldo 0', f);
		const m = q(`SELECT m.id, m.jornada_caja_id, m.tipo, m.medio, m.afecta_arqueo FROM movimiento_caja m WHERE m.tipo_origen = 'PAGO_EGRESO' AND m.referencia_origen = ${p.id}`);
		ok(m.length === 1 && m[0].tipo === 'EGRESO' && n(m[0].jornada_caja_id) === C.jornada && m[0].afecta_arqueo === '1', 'un EGRESO PAGO_EGRESO en la jornada abierta que afecta el arqueo', m);
		C.mov_pago_egreso = n(m[0]?.id);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(tiene(au, 'EGRESO_CONFIRMADO') && tiene(au, 'EGRESO_PAGADO'), 'EGRESO_CONFIRMADO y EGRESO_PAGADO', au);
		nota('el 409 caja-no-abierta con la caja cerrada se prueba despues de ECO-12');
	});
	await paso('EGR-04', 'Honorarios a un colaborador, pago parcial por transferencia', async () => {
		need(C.mprof);
		const s = await S('adm');
		const e = must(await api('POST', cs('/egresos'), { s, json: { categoria: 'HONORARIOS_PROFESIONALES', tipoBeneficiario: 'COLABORADOR', beneficiarioMembershipId: C.mprof, concepto: 'Honorarios QA', periodoDesde: hoy(-30), periodoHasta: hoy(-1), importeTotal: 4000, moneda: 'ARS', comprobanteTipo: 'RECIBO', comprobanteNumero: `R-${suf}`, comprobanteFecha: hoy(), idempotencyKey: randomUUID() } }), 201, 'honorarios');
		C.egreso_honorarios = e.id;
		must(await api('POST', eg(e.id, '/confirmacion'), { s }), 200, 'confirmar');
		C.pago_transf = must(await api('POST', eg(e.id, '/pagos'), { s, json: { importe: 1500, medio: 'TRANSFERENCIA', referencia: 'TRF-QA-EGR', idempotencyKey: randomUUID() } }), 201, 'pagar 1500 por transferencia').id;
		tipo(await api('POST', eg(e.id, '/pagos'), { s, json: { importe: 9999, medio: 'TRANSFERENCIA', idempotencyKey: randomUUID() } }), 'egreso-saldo-insuficiente', 'pagar mas que el saldo');
		const f = q(`SELECT beneficiario_membership_id, beneficiario_nombre, importe_total, saldo_pendiente, estado FROM egreso WHERE id = ${e.id}`);
		ok(n(f[0].beneficiario_membership_id) === C.mprof && f[0].beneficiario_nombre && f[0].estado === 'CONFIRMADO' && Number(f[0].saldo_pendiente) === 2500, 'del profesional, nombre resuelto por el backend, CONFIRMADO con saldo 2500', f);
		const m = q(`SELECT m.jornada_caja_id, m.medio, m.afecta_arqueo FROM movimiento_caja m JOIN pago_egreso p ON p.id = m.referencia_origen WHERE m.tipo_origen = 'PAGO_EGRESO' AND p.egreso_id = ${e.id}`);
		ok(m.every((x) => x.medio === 'TRANSFERENCIA' && x.afecta_arqueo === '0'), 'el movimiento de transferencia no afecta el arqueo', m);
		nota('EGR-04: con la caja abierta el movimiento de la transferencia queda en la jornada (afecta_arqueo = 0); jornada_caja_id es NULL solo con la caja cerrada');
		const mb = n(q1('SELECT id FROM membership WHERE organization_id = @org_b AND is_founder = 1').id);
		tipo(await api('POST', cs('/egresos'), { s, json: { categoria: 'HONORARIOS_PROFESIONALES', tipoBeneficiario: 'COLABORADOR', beneficiarioMembershipId: mb, concepto: 'QA: ajeno', importeTotal: 10, moneda: 'ARS', idempotencyKey: randomUUID() } }), 'beneficiario-no-vinculado', 'un colaborador de otra organizacion');
	});
	await paso('EGR-05', 'Anular un pago', async () => {
		need(C.pago_egreso);
		const d0 = maxAudit();
		const s = await S('adm');
		const url = eg(C.egreso, `/pagos/${C.pago_egreso}/anulacion`);
		must(await api('POST', url, { s, json: { motivo: 'QA: pago duplicado' } }), 200, 'anular');
		tipo(await api('POST', url, { s, json: { motivo: 'otra vez' } }), 'pago-egreso-ya-anulado', 'anular otra vez');
		const f = q('SELECT estado, saldo_pendiente FROM egreso WHERE id = @egreso');
		ok(f[0].estado === 'CONFIRMADO' && Number(f[0].saldo_pendiente) === 1500, 'vuelve a CONFIRMADO con 1500', f);
		const p = q(`SELECT estado, anulado_en, anulado_por_cuenta_id, motivo_anulacion FROM pago_egreso WHERE id = ${C.pago_egreso}`);
		ok(p[0].estado === 'ANULADO' && p[0].motivo_anulacion && p[0].anulado_por_cuenta_id, 'pago ANULADO con motivo y actor', p);
		const r = q(`SELECT id, tipo FROM movimiento_caja WHERE tipo = 'REVERSION_DE_EGRESO' AND movimiento_origen_id = ${C.mov_pago_egreso ?? 0}`);
		eq(r.length, 1, 'una REVERSION_DE_EGRESO que apunta al movimiento del pago', r);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'EGRESO_PAGO_ANULADO', (x) => x.reason), 'EGRESO_PAGO_ANULADO con reason');
	});
	await paso('EGR-06', 'Anular el egreso', async () => {
		need(C.egreso_honorarios, C.egreso);
		const d0 = maxAudit();
		const s = await S('adm');
		tipo(await api('POST', eg(C.egreso_honorarios, '/anulacion'), { s, json: { motivo: 'x' } }), 'egreso-con-pagos', 'anular el de honorarios con un pago vigente');
		must(await api('POST', eg(C.egreso, '/anulacion'), { s, json: { motivo: 'Proveedor equivocado (pre-QA)' } }), 200, 'anular el egreso sin pagos');
		const f = q('SELECT estado, anulado_en, anulado_por_cuenta_id, motivo_anulacion, anulado_key FROM egreso WHERE id = @egreso');
		ok(f[0].estado === 'ANULADO' && f[0].motivo_anulacion && f[0].anulado_key, 'ANULADO con motivo, actor y anulado_key', f);
		tipo(await api('POST', eg(C.egreso, '/pagos'), { s, json: { importe: 10, medio: 'TRANSFERENCIA', idempotencyKey: randomUUID() } }), 'egreso-no-pagable', 'pagar un anulado');
		const arq = () => n(q1("SELECT COUNT(*) n FROM movimiento_caja WHERE tipo LIKE 'REVERSION%' AND consultorio_id = @sede_a AND afecta_arqueo = 1").n);
		const antes = arq();
		must(await api('POST', eg(C.egreso_honorarios, `/pagos/${C.pago_transf}/anulacion`), { s, json: { motivo: 'Transferencia anulada (pre-QA)' } }), 200, 'EGR-05: anular el pago por transferencia');
		eq(arq(), antes, 'EGR-05: anular la transferencia no genera reversion en el arqueo');
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'EGRESO_ANULADO', (x) => x.reason && det(x).estado === 'ANULADO'), 'EGRESO_ANULADO con reason y details.estado');
	});
	await paso('EGR-07', 'Filtros del listado y el dia de la sede', async () => {
		const s = await S('admin');
		const desde = `${hoy().slice(0, 8)}01`;
		const l = must(await api('GET', cs(`/egresos?desde=${desde}`), { s }), 200, 'listado del mes');
		const lista = Array.isArray(l) ? l : l.content ?? [];
		const ids = lista.map((x) => x.id);
		ok(ids.length >= 3 && ids.every((x, i) => i === 0 || ids[i - 1] > x), 'los egresos del mes, del mas nuevo al mas viejo', ids.join(','));
		const an = (await api('GET', cs(`/egresos?desde=${desde}&estado=ANULADO`), { s })).cuerpo;
		ok((Array.isArray(an) ? an : an.content ?? []).every((x) => x.estado === 'ANULADO'), 'filtro por estado');
		const ho = (await api('GET', cs(`/egresos?desde=${desde}&categoria=HONORARIOS_PROFESIONALES`), { s })).cuerpo;
		ok((Array.isArray(ho) ? ho : ho.content ?? []).every((x) => x.categoria === 'HONORARIOS_PROFESIONALES'), 'filtro por categoria');
		const sede = must(await api('GET', `/api/v1/organizations/${C.org_a}/consultorios/${C.sede_a}`, { s }), 200, 'la sede');
		const zonaOrig = sede.timezone;
		const otra = new Date().getUTCHours() >= 10 ? 'Pacific/Kiritimati' : 'Pacific/Pago_Pago';
		const mv = must(await api('PATCH', `/api/v1/organizations/${C.org_a}/consultorios/${C.sede_a}`, { s, json: { timezone: otra, version: sede.version } }), 200, `mover la sede a ${otra}`);
		try {
			const diaSede = new Date().toLocaleDateString('en-CA', { timeZone: otra });
			const diaUtc = new Date().toISOString().slice(0, 10);
			const e = must(await api('POST', cs('/egresos'), { s, json: { categoria: 'OTRO', tipoBeneficiario: 'EXTERNO', beneficiarioNombre: 'Zona QA', concepto: 'Egreso del dia de la sede', importeTotal: 10, moneda: 'ARS', idempotencyKey: randomUUID() } }), 201, 'un egreso despues del cambio');
			const en = async (d) => { const x = (await api('GET', cs(`/egresos?desde=${d}&hasta=${d}`), { s })).cuerpo; return (Array.isArray(x) ? x : x.content ?? []).some((y) => y.id === e.id); };
			ok(await en(diaSede), `con Desde = Hasta = ${diaSede} (dia de la sede) aparece`);
			ok(diaSede === diaUtc || !(await en(diaUtc)), `con Desde = Hasta = ${diaUtc} (dia UTC) no aparece`);
			const db = q(`SELECT DATE(registrado_en) dia_utc, DATE(CONVERT_TZ(registrado_en, '+00:00', ${lit(otra)})) dia_sede FROM egreso WHERE id = ${e.id}`);
			nota(`egreso ${e.id}: ${JSON.stringify(db[0])}`);
		} finally {
			await api('PATCH', `/api/v1/organizations/${C.org_a}/consultorios/${C.sede_a}`, { s, json: { timezone: zonaOrig, version: mv.version } });
		}
		manual('"Desde" por defecto en el primer dia del mes de la sede (pantalla)');
	});
	await paso('EGR-08', 'Quien ve los egresos', async () => {
		const d0 = maxAudit();
		st(await api('GET', cs('/egresos'), { s: await S('prof') }), 403, 'el profesional');
		st(await api('GET', cs('/egresos'), { s: await S('b') }), 404, 'el centro B sobre la sede de A');
		const prof = n(q1('SELECT id FROM cuenta WHERE email_normalizado = @email_profesional').id);
		const ev = q("SELECT event_type, actor_account_id, reason FROM audit_event WHERE organization_id = @org_a AND entity_type = 'Egreso' AND occurred_at >= @desde ORDER BY id");
		ok(ev.length > 0 && !ev.some((x) => n(x.actor_account_id) === prof), 'eventos de egreso, ninguno del profesional', ev);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'PERMISSION_DENIED', (x) => n(x.actor_account_id) === prof), 'el 403 deja PERMISSION_DENIED');
		manual('el menu del profesional no tiene "Egresos"');
	});
	await paso('EGR-09', 'Corregir un borrador con la version leida', async () => {
		const s = await S('adm');
		const e = must(await api('POST', cs('/egresos'), { s, json: { categoria: 'MANTENIMIENTO', tipoBeneficiario: 'EXTERNO', beneficiarioNombre: 'Plomero QA', concepto: 'Arreglo', importeTotal: 300, moneda: 'ARS', idempotencyKey: randomUUID() } }), 201, 'borrador');
		const base = { categoria: 'MANTENIMIENTO', concepto: 'Arreglo', importeTotal: 300 };
		const r1 = must(await api('PUT', eg(e.id), { s, json: { ...base, concepto: 'Arreglo de canilla', version: e.version } }), 200, 'primera ventana');
		tipo(await api('PUT', eg(e.id), { s, json: { ...base, importeTotal: 350, version: e.version } }), 'concurrent-modification', 'segunda ventana con la version vieja');
		tipo(await api('PUT', eg(e.id), { s, json: { ...base } }), 'validation-error', 'sin version');
		const r2 = must(await api('PUT', eg(e.id), { s, json: { ...base, concepto: 'Arreglo de canilla', importeTotal: 350, version: r1.version } }), 200, 'encadenada con la version de la respuesta');
		const f = q(`SELECT concepto, importe_total, moneda, beneficiario_nombre, version FROM egreso WHERE id = ${e.id}`);
		ok(f[0].concepto === 'Arreglo de canilla' && Number(f[0].importe_total) === 350 && f[0].moneda === 'ARS' && f[0].beneficiario_nombre === 'Plomero QA' && n(f[0].version) === n(e.version) + 2, 'concepto e importe nuevos, version +2, moneda y beneficiario del alta', f);
		must(await api('PUT', eg(e.id), { s, json: { ...base, concepto: 'Arreglo de canilla', importeTotal: 350, comprobanteTipo: 'FACTURA_C', comprobanteNumero: `C2-${suf}`, comprobanteFecha: hoy(), version: r2.version } }), 200, 'cargar comprobante');
		must(await api('POST', eg(e.id, '/confirmacion'), { s }), 200, 'confirmar');
		const v = n(q1(`SELECT version FROM egreso WHERE id = ${e.id}`).version);
		tipo(await api('PUT', eg(e.id), { s, json: { ...base, version: v } }), 'egreso-no-editable', 'confirmado con la version vigente');
		tipo(await api('PUT', eg(e.id), { s, json: { ...base, version: e.version } }), 'concurrent-modification', 'confirmado con una version vieja (gana la version)');
	});
}
