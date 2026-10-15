import { randomUUID } from 'node:crypto';
import { APIC, MAILC, RED, API, MAILPIT, P, MYSQL, DB, PLATAFORMA, AQUI, PASSWORD, PASSWORD2, SALIDA, SALIDA_JSON, SOLO, suf, dormir, UA, inicioCorrida, Corte, Bloqueo, R, cur, CIRC, paso, ultimaSql, ok, eq, st, tipo, must, need, nota, manual, soloManual, n, SECRETO, limpiar, descr, api, galletas, cookieRt, V, lit, unesc, q, q1, fmtSql, ahoraDb, maxAudit, auditDesde, tiene, cuenta, det, docker, correos, textoCorreo, esperarCorreos, tokenDe, tokenCorreo, ES_ACTIVACION, ES_INVITACION, pw, correo, registrar, activar, login, entrar, CU, SES, S, Splat, hoy, horaLocal, fechaLocal, instanteLocal, C, usados, agenda, slot, reservar, recepcionHastaLlamado, ses, iniciarSesion, tratar, cerrarSesion, atencionCompleta, PNG, multipart, sha256, J, ORIGEN, GUIA, guia, ZONA, setZona, AUDIT_RANGO } from './lib.mjs';

// 04 · Personas, coberturas, ordenes y autorizaciones (PER-11/12/15 van despues de 06 y 07; PER-13/14 al final)
export const per = (id = C.persona, p = '') => `/api/v1/personas/${id}${p}`;
export async function nuevaPersona(s, nombre, apellido, dni, extra = {}) {
	return api('POST', '/api/v1/personas', { s, json: { nombre, apellido, tipoDocumento: 'DNI', numeroDocumento: dni, ...extra } });
}
export async function conPerfil(s, id) {
	return api('POST', per(id, '/perfil-paciente'), { s, json: { motivo: 'Alta pre-QA' } });
}

export async function c04() {
	await paso('PER-01', 'Alta de persona y deteccion de duplicado', async () => {
		const d0 = maxAudit();
		const s = await S('admin');
		C.persona = V.persona = must(await nuevaPersona(s, 'Ana', 'QA', '30111222', { fechaNacimiento: '1990-05-10', email: 'ana.qa@ejemplo.test' }), 201, 'alta de Ana QA').id;
		const dup = await nuevaPersona(s, 'Beatriz', 'Duplicada', '30111222');
		st(dup, 409, 'mismo DNI con otro nombre: rechazo');
		const conf = await nuevaPersona(s, 'Beatriz', 'Duplicada', '30111222', { confirmaPosibleDuplicado: true });
		if (conf.status === 409 && conf.tipo === 'persona-documento-taken') guia('PER-01 dice que el mismo DNI "avisa posible duplicado y solo sigue si lo confirmas"; el producto lo rechaza siempre con 409 persona-documento-taken (el documento es unico en la organizacion), aun confirmando');
		const pd = await nuevaPersona(s, 'Ana', 'QA', '30999888', { fechaNacimiento: '1990-05-10' });
		ok(pd.status === 409 && /duplicad/i.test(`${pd.tipo} ${pd.cuerpo?.detail}`), 'mismo nombre y nacimiento con otro DNI: avisa posible duplicado', pd);
		C.persona_dup = must(await nuevaPersona(s, 'Ana', 'QA', '30999888', { fechaNacimiento: '1990-05-10', confirmaPosibleDuplicado: true }), 201, 'confirmado, sigue').id;
		const f = q(`SELECT id, numero_documento, documento_clave, apellido, nombre, apellido_clave, email, active FROM persona WHERE organization_id = @org_a AND numero_documento IN ('30111222', '30999888') ORDER BY id`);
		ok(f.length === 2 && f[0].documento_clave && f[0].apellido_clave, 'Ana y su posible duplicado confirmado, con las columnas *_clave normalizadas', f);
		eq(q1('SELECT COUNT(*) n FROM perfil_paciente WHERE persona_id = @persona').n, 0, 'la persona nace sin perfil de paciente');
		ok(cuenta(auditDesde(d0, 'AND organization_id = @org_a'), 'PERSONA_CREATED') >= 2, 'PERSONA_CREATED');
		nota('se confirmo el posible duplicado para tener la persona descartable de PER-13; @persona es la primera, no MAX(id)');
		C.persona_part = must(await nuevaPersona(s, 'Pedro', `Particular ${suf}`, `2${Date.now() % 10000000}`), 201, 'persona particular').id;
		V.persona_part = C.persona_part;
		must(await conPerfil(s, C.persona_part), 200, 'perfil de la persona particular');
	});
	await paso('PER-02', 'Activar el perfil de paciente', async () => {
		need(C.persona);
		const d0 = maxAudit();
		const r = await conPerfil(await S('admin'), C.persona);
		ok(must(r, 200, 'activar perfil').esPaciente === true, 'la ficha dice esPaciente', r);
		const f = q('SELECT id, persona_id, activado_en, activado_por, motivo, active FROM perfil_paciente WHERE persona_id = @persona');
		ok(f.length === 1 && f[0].active === '1' && f[0].activado_por, 'perfil activo con actor', f);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'PERFIL_PACIENTE_ACTIVATED'), 'PERFIL_PACIENTE_ACTIVATED');
		nota('el rechazo persona-sin-perfil-paciente al reservar se prueba en AGE-01');
	});
	await paso('PER-03', 'Editar la persona y el control de concurrencia', async () => {
		need(C.persona);
		const d0 = maxAudit();
		const s = await S('admin');
		const p = must(await api('GET', per(), { s }), 200, 'leer');
		must(await api('PATCH', per(), { s, json: { telefono: '3511234567', expectedVersion: p.version } }), 200, 'primera pestana: telefono');
		const r = await api('PATCH', per(), { s, json: { email: 'otra.qa@ejemplo.test', expectedVersion: p.version } });
		tipo(r, 'concurrent-modification', 'segunda pestana con la version vieja');
		const f = q('SELECT telefono, email, version FROM persona WHERE id = @persona');
		ok(f[0].telefono === '3511234567' && f[0].email === 'ana.qa@ejemplo.test' && n(f[0].version) === n(p.version) + 1, 'telefono guardado, email sin pisar, version +1', f);
		eq(cuenta(auditDesde(d0, 'AND organization_id = @org_a'), 'PERSONA_UPDATED'), 1, 'un solo PERSONA_UPDATED');
	});
	await paso('PER-04', 'Documento de la persona: el tipo lo deciden los bytes', async () => {
		need(C.persona);
		const d0 = maxAudit();
		const s = await S('admin');
		const url = per(C.persona, '/adjuntos?categoria=DOCUMENTO_IDENTIDAD&titulo=DNI');
		const r = await api('POST', url, { s, form: multipart('dni.png', PNG, 'image/png') });
		const adj = must(r, [200, 201], 'subir un PNG real');
		const falso = await api('POST', per(C.persona, '/adjuntos?categoria=DOCUMENTO_IDENTIDAD&titulo=Falso'), { s, form: multipart('falso.pdf', Buffer.from('esto es texto, no un pdf'), 'application/pdf') });
		ok(falso.status >= 400 && falso.status < 500, 'un .txt renombrado a .pdf se rechaza', falso);
		const f = q('SELECT id, categoria, content_type, tamano_bytes, checksum_sha256, estado, active FROM adjunto_administrativo WHERE persona_id = @persona ORDER BY id');
		ok(f.length === 1 && f[0].estado === 'DISPONIBLE' && f[0].checksum_sha256?.length === 64 && f[0].checksum_sha256 === sha256(PNG), 'una sola fila DISPONIBLE con el checksum del archivo', f);
		const d = await api('GET', per(C.persona, `/adjuntos/${adj.id}/contenido`), { s, headers: { Accept: '*/*' } });
		st(d, 200, 'descargar (regresion de #82 si da 500)');
		ok(d.buf.equals(PNG) && /attachment/i.test(d.headers.get('content-disposition') ?? ''), 'baja los mismos bytes como adjunto', `content-disposition: ${d.headers.get('content-disposition')}`);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(tiene(au, 'ADJUNTO_UPLOADED') && tiene(au, 'ADJUNTO_DOWNLOADED'), 'ADJUNTO_UPLOADED y ADJUNTO_DOWNLOADED', au);
	});
	await paso('PER-05', 'Financiador y plan', async () => {
		const d0 = maxAudit();
		const s = await S('admin');
		C.financiador = V.financiador = must(await api('POST', '/api/v1/financiadores', { s, json: { codigo: `OSQA${suf}`.toUpperCase(), nombre: 'Obra Social QA', tipo: 'OBRA_SOCIAL' } }), 201, 'financiador').id;
		const plan = { codigo: 'PLAN-QA', nombre: 'Plan QA', vigenciaDesde: '2026-01-01', requiereAutorizacion: true };
		C.plan = V.plan = must(await api('POST', `/api/v1/financiadores/${C.financiador}/planes`, { s, json: plan }), 201, 'plan').id;
		st(await api('POST', `/api/v1/financiadores/${C.financiador}/planes`, { s, json: { ...plan, codigo: 'PLAN-PESOS', moneda: 'PESOS', copago: 100 } }), 400, 'una moneda que no es ISO 4217');
		const f = q('SELECT id, codigo, vigencia_desde, requiere_autorizacion, moneda FROM plan_cobertura WHERE id = @plan');
		ok(f[0]?.requiere_autorizacion === '1', 'plan que requiere autorizacion', f);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(tiene(au, 'FINANCIADOR_CREATED') && tiene(au, 'PLAN_COBERTURA_CREATED'), 'FINANCIADOR_CREATED y PLAN_COBERTURA_CREATED', au);
	});
	await paso('PER-06', 'Convenio y arancel por oferta', async () => {
		need(C.plan, C.oferta, C.practica);
		const d0 = maxAudit();
		const s = await S('admin');
		C.convenio = V.convenio = must(await api('POST', `/api/v1/consultorios/${C.sede_a}/convenios`, { s, json: { codigo: 'CONV-QA', nombre: 'Convenio QA', financiadorId: C.financiador, planId: C.plan, modalidad: 'POR_PRESTACION', moneda: 'ARS', vigenciaDesde: '2026-01-01', requiereAutorizacion: true } }), 201, 'convenio').id;
		const ar = { practicaId: C.practica, ofertaId: C.oferta, importeTotal: 8000, importeFinanciador: 6000, coseguro: 2000, vigenciaDesde: '2026-01-01' };
		must(await api('POST', `/api/v1/consultorios/${C.sede_a}/convenios/${C.convenio}/aranceles`, { s, json: ar }), 201, 'arancel 8000/6000/2000');
		st(await api('POST', `/api/v1/consultorios/${C.sede_a}/convenios/${C.convenio}/aranceles`, { s, json: ar }), 409, 'un segundo arancel solapado');
		const ef = await api('GET', `/api/v1/consultorios/${C.sede_a}/aranceles/efectivo?financiadorId=${C.financiador}&planId=${C.plan}&practicaId=${C.practica}&ofertaId=${C.oferta}&fecha=${hoy()}`, { s });
		ok(ef.status === 200 && Number(ef.cuerpo.importeTotal) === 8000, 'el arancel efectivo de hoy', ef);
		const f = q('SELECT practica_id, oferta_id, importe_total, importe_financiador, coseguro, active FROM convenio_arancel WHERE convenio_id = @convenio');
		ok(f.length === 1 && Number(f[0].importe_total) === Number(f[0].importe_financiador) + Number(f[0].coseguro), 'una fila y total = financiador + coseguro', f);
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(tiene(au, 'CONVENIO_CREATED') && tiene(au, 'ARANCEL_CREATED'), 'CONVENIO_CREATED y ARANCEL_CREATED', au);
	});
	await paso('PER-06b', 'Importar aranceles con vista previa (B-7)', async () => {
		need(C.convenio);
		const s = await S('admin');
		const url = `/api/v1/consultorios/${C.sede_a}/convenios/${C.convenio}/aranceles/importacion`;
		const general = { codigoPractica: C.codigoPractica, importeTotal: 9000, importeFinanciador: 7000, coseguro: 2000, vigenciaDesde: '2026-01-01' };
		const porOferta = { codigoPractica: C.codigoPractica, ofertaId: C.oferta, importeTotal: 8000, importeFinanciador: 6000, coseguro: 2000, vigenciaDesde: '2026-01-01' };
		const filas = () => n(q1('SELECT COUNT(*) n FROM convenio_arancel WHERE convenio_id = @convenio').n);
		const antes = filas();
		const pv = must(await api('POST', url, { s, json: { modo: 'PREVIEW', filas: [general, porOferta] } }), 200, 'vista previa');
		const [f1, f2] = pv.filas ?? [];
		ok(JSON.stringify(f1).includes('ALTA') && JSON.stringify(f2).includes('RECHAZADA') && JSON.stringify(f2).includes('arancel-solapado'), 'ALTA y RECHAZADA con arancel-solapado', JSON.stringify(pv.filas));
		eq(filas(), antes, 'la vista previa no escribe');
		const d0 = maxAudit();
		const c1 = await api('POST', url, { s, json: { modo: 'CONFIRMAR', filas: [general, porOferta] } });
		tipo(c1, 'importacion-aranceles-rechazada', 'confirmar con una fila rechazada');
		eq(filas(), antes, 'todo o nada: ninguna fila escrita');
		must(await api('POST', url, { s, json: { modo: 'CONFIRMAR', filas: [general] } }), 200, 'confirmar solo la general');
		eq(filas(), antes + 1, 'una fila nueva');
		const g = q('SELECT oferta_id, importe_total, importe_financiador, coseguro FROM convenio_arancel WHERE convenio_id = @convenio ORDER BY id DESC LIMIT 1');
		ok(g[0].oferta_id === null && Number(g[0].importe_total) === 9000, 'la nueva sin oferta', g);
		st(await api('POST', url, { s, json: { modo: 'CONFIRMAR', filas: [general] } }), 409, 'confirmar otra vez el mismo lote');
		st(await api('POST', url, { s, json: { modo: 'CONFIRMAR', filas: [] } }), 400, 'lote vacio');
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		const imp = au.filter((x) => x.event_type === 'ARANCELES_IMPORTADOS');
		ok(imp.length === 1 && imp[0].entity_type === 'Convenio' && det(imp[0]).cantidad !== undefined && det(imp[0]).practicas !== undefined && det(imp[0]).rango !== undefined, 'un ARANCELES_IMPORTADOS con cantidad, practicas y rango', au);
		eq(cuenta(au, 'ARANCEL_CREATED'), 0, 'ningun ARANCEL_CREATED por fila');
	});
	await paso('PER-07', 'Cobertura del paciente', async () => {
		need(C.plan, C.persona);
		const d0 = maxAudit();
		const s = await S('admin');
		C.cobertura = V.cobertura = must(await api('POST', per(C.persona, '/coberturas'), { s, json: { tipo: 'FINANCIADA', planId: C.plan, numeroAfiliado: 'AF-QA-001', vigenciaDesde: '2026-01-01', principal: true, credencialVigenciaHasta: '2026-12-31' } }), 201, 'cobertura').id;
		const fin = must(await api('GET', `/api/v1/financiadores/${C.financiador}`, { s }), 200, 'leer financiador');
		must(await api('PUT', `/api/v1/financiadores/${C.financiador}`, { s, json: { nombre: 'Obra Social QA renombrada', tipo: 'OBRA_SOCIAL', expectedVersion: fin.version } }), 200, 'renombrar el financiador');
		const f = q('SELECT id, tipo, financiador_nombre, plan_nombre, numero_afiliado, principal, requeria_autorizacion, referencia_capturada_el FROM cobertura_paciente WHERE id = @cobertura');
		ok(f[0]?.financiador_nombre === 'Obra Social QA' && f[0].plan_nombre === 'Plan QA' && f[0].referencia_capturada_el, 'la fila guarda la copia y no cambia al renombrar el financiador', f);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'COBERTURA_CREATED'), 'COBERTURA_CREATED');
		C.cob_part = must(await api('POST', per(C.persona_part, '/coberturas'), { s, json: { tipo: 'FINANCIADA', planId: C.plan, numeroAfiliado: 'AF-QA-002', vigenciaDesde: '2026-01-01', principal: true, credencialVigenciaHasta: '2026-12-31' } }), 201, 'la persona particular tambien tiene obra social (para probar DP-17)').id;
	});
	await paso('PER-08', 'Cobertura aplicable a la oferta (B-3)', async () => {
		need(C.cobertura, C.oferta);
		const s = await S('admin');
		const r = must(await api('GET', per(C.persona, `/cobertura-aplicable?ofertaId=${C.oferta}`), { s }), 200, 'cobertura aplicable');
		const ap = (r.aplicables ?? []).find((x) => x.coberturaId === C.cobertura);
		ok(ap && Number(ap.arancel?.coseguro) === 2000, 'aplica la cobertura con el arancel por oferta (coseguro 2000)', JSON.stringify(r).slice(0, 600));
		const res = await api('GET', per(C.persona, '/resumen'), { s });
		ok(res.status === 200 && !JSON.stringify(res.cuerpo).includes('AF-QA-001'), 'el numero de afiliado no viaja completo en el 360', res);
	});
	await paso('PER-09', 'Orden medica', async () => {
		need(C.cobertura);
		const d0 = maxAudit();
		C.orden = V.orden = must(await api('POST', per(C.persona, '/ordenes'), { s: await S('admin'), json: { coberturaId: C.cobertura, fechaEmision: hoy(), profesionalEmisor: 'Dra. Sintética', sesionesPrescriptas: 10 } }), 201, 'orden').id;
		const f = q('SELECT cobertura_id, profesional_emisor, sesiones_prescriptas, active FROM orden_medica WHERE id = @orden');
		ok(f[0]?.sesiones_prescriptas === '10' && n(f[0].cobertura_id) === C.cobertura, 'la orden', f);
		ok(tiene(auditDesde(d0, 'AND organization_id = @org_a'), 'ORDEN_MEDICA_CREATED'), 'ORDEN_MEDICA_CREATED');
	});
	await paso('PER-10', 'Autorizacion pendiente -> observada -> aprobada, con historial propio (DP-23)', async () => {
		need(C.orden, C.practica);
		const d0 = maxAudit();
		const s = await S('admin');
		let a = must(await api('POST', per(C.persona, '/autorizaciones'), { s, json: { coberturaId: C.cobertura, ordenMedicaId: C.orden, practicaId: C.practica, numero: 'AUT-QA-001', cantidadAutorizada: 10, estadoInicial: 'PENDIENTE', vigenciaDesde: '2026-01-01', vigenciaHasta: '2026-12-31' } }), 201, 'autorizacion pendiente');
		C.autorizacion = V.autorizacion = a.id;
		const est = (b) => api('POST', per(C.persona, `/autorizaciones/${a.id}/estado`), { s, json: b });
		st(await est({ accion: 'OBSERVAR', expectedVersion: a.version }), 400, 'observar sin motivo');
		a = must(await est({ accion: 'OBSERVAR', motivo: 'Falta sello', expectedVersion: a.version }), 200, 'observar');
		a = must(await est({ accion: 'APROBAR', expectedVersion: a.version }), 200, 'aprobar');
		const f = q('SELECT estado, numero, cantidad_autorizada, cantidad_consumida, convenio_id, requeria_autorizacion FROM autorizacion WHERE id = @autorizacion');
		eq(f[0]?.estado, 'APROBADA', 'APROBADA', f);
		const ev = q('SELECT tipo, estado_anterior, estado_nuevo, motivo FROM autorizacion_evento WHERE autorizacion_id = @autorizacion ORDER BY id');
		ok(ev.length === 3 && ev[0].tipo === 'ALTA' && ev[0].estado_nuevo === 'PENDIENTE' && ev[1].tipo === 'OBSERVACION' && ev[1].estado_nuevo === 'OBSERVADA' && ev[1].motivo && ev[2].tipo === 'APROBACION' && ev[2].estado_anterior === 'OBSERVADA', 'ALTA, OBSERVACION (con motivo), APROBACION en orden', ev);
		const h = must(await api('GET', per(C.persona, `/autorizaciones/${a.id}/historial`), { s }), 200, 'historial');
		eq((h.content ?? []).length, 3, 'el historial de la pantalla tiene los tres eventos');
		const au = auditDesde(d0, 'AND organization_id = @org_a');
		ok(tiene(au, 'AUTORIZACION_CREATED') && cuenta(au, 'AUTORIZACION_RESUELTA') === 2, 'AUTORIZACION_CREATED y dos AUTORIZACION_RESUELTA', au);
	});
}
