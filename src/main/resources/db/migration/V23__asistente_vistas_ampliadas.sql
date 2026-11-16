-- Mas vistas de solo lectura para las consultas libres del asistente de datos (sigue a V21).
--
-- Mismas reglas que V21: cada vista se recorta a la organizacion (asistente_sql.org()) y, si el
-- dato es de una sede, a la sede activa (asistente_sql.sede()). Sin parametros, no devuelven filas.
-- No exponen numeros de documento, de afiliado, de orden ni de autorizacion, correos, telefonos,
-- direcciones, matriculas, referencias de pago, claves de idempotencia ni adjuntos.
--
-- Las vistas cuyo nombre empieza con "clinico_" SI exponen texto clinico: el backend las habilita
-- solo con el permiso hc:read y audita cada consulta que las lee. La historia clinica es de la
-- organizacion, asi que casos, antecedentes y planes no se recortan a la sede; lo que cuelga de una
-- sesion (sesiones, tratamientos, mediciones) y las derivaciones, si.

-- ============================================================================================
-- Negocio
-- ============================================================================================

-- Sedes (consultorios) de la organizacion. No se recorta a la sede activa.
CREATE VIEW asistente_sql.sedes AS
SELECT c.id AS sede_id,
	c.name AS nombre,
	c.alias,
	c.ciudad,
	c.provincia,
	c.timezone AS zona_horaria,
	c.agenda_habilitada,
	c.active AS activa
FROM consultorio c
WHERE c.organization_id = asistente_sql.org() AND c.deleted_at IS NULL;

-- Espacios (boxes, gimnasio, salas) de la sede.
CREATE VIEW asistente_sql.espacios AS
SELECT e.id AS espacio_id,
	e.name AS nombre,
	e.tipo,
	e.capacidad,
	e.valid_from AS vigente_desde,
	e.valid_until AS vigente_hasta,
	e.active AS activo
FROM espacio e
WHERE e.organization_id = asistente_sql.org() AND e.consultorio_id = asistente_sql.sede()
	AND e.deleted_at IS NULL;

-- Jornadas de caja de la sede: una por dia de negocio y moneda. Responde "esta abierta la caja?".
CREATE VIEW asistente_sql.jornadas_caja AS
SELECT j.id AS jornada_id,
	j.fecha_negocio AS fecha,
	j.moneda,
	j.estado,
	j.saldo_inicial,
	j.saldo_arqueo,
	j.saldo_teorico_cierre,
	j.saldo_declarado,
	j.diferencia,
	j.motivo_diferencia,
	j.abierta_en,
	j.cerrada_en
FROM jornada_caja j
WHERE j.organization_id = asistente_sql.org() AND j.consultorio_id = asistente_sql.sede();

-- Presentaciones (lotes facturables) a financiadores de la sede. Incluye anuladas (estado).
CREATE VIEW asistente_sql.presentaciones AS
SELECT pr.id AS presentacion_id,
	pr.financiador_id,
	f.nombre AS financiador,
	pr.numero,
	pr.periodo_desde,
	pr.periodo_hasta,
	pr.estado,
	pr.moneda,
	pr.total_presentado,
	pr.total_debitado,
	pr.total_cobrado,
	pr.saldo,
	pr.creada_en,
	pr.confirmada_en AS presentada_en,
	pr.factura_fecha AS facturada_el,
	pr.conciliada_en,
	pr.anulada_en
FROM presentacion pr
LEFT JOIN financiador f ON f.id = pr.financiador_id
WHERE pr.organization_id = asistente_sql.org() AND pr.consultorio_id = asistente_sql.sede()
	AND pr.deleted_at IS NULL;

-- Items de cada presentacion: una deuda del financiador incluida en el lote.
CREATE VIEW asistente_sql.presentacion_items AS
SELECT it.id AS item_id,
	it.presentacion_id,
	it.obligacion_id AS deuda_id,
	it.snapshot_persona_id AS persona_id,
	it.snapshot_sesion_id AS sesion_id,
	it.snapshot_fecha_prestacion AS fecha_prestacion,
	it.snapshot_concepto AS concepto,
	it.estado,
	it.importe_presentado,
	it.importe_debitado,
	it.motivo_debito,
	it.incluido_en
FROM presentacion_item it
JOIN presentacion pr ON pr.id = it.presentacion_id
WHERE it.organization_id = asistente_sql.org() AND pr.consultorio_id = asistente_sql.sede()
	AND pr.deleted_at IS NULL;

-- Pagos recibidos de financiadores en la sede, sin la referencia del pago.
CREATE VIEW asistente_sql.pagos_financiador AS
SELECT fp.id AS pago_id,
	fp.financiador_id,
	f.nombre AS financiador,
	fp.presentacion_id,
	fp.importe,
	fp.moneda,
	fp.medio,
	fp.fecha_pago AS fecha,
	fp.registrado_en
FROM financiador_pago fp
LEFT JOIN financiador f ON f.id = fp.financiador_id
WHERE fp.organization_id = asistente_sql.org() AND fp.consultorio_id = asistente_sql.sede();

-- Pagos de egresos de la sede. "anulado" marca los anulados (siguen en la vista).
CREATE VIEW asistente_sql.pagos_egreso AS
SELECT pe.id AS pago_id,
	pe.egreso_id,
	pe.fecha_negocio AS fecha,
	pe.importe,
	pe.moneda,
	pe.medio,
	pe.estado,
	(pe.estado = 'ANULADO') AS anulado,
	pe.pagado_en,
	pe.anulado_en
FROM pago_egreso pe
WHERE pe.organization_id = asistente_sql.org() AND pe.consultorio_id = asistente_sql.sede();

-- Liquidaciones de honorarios de profesionales de la sede.
CREATE VIEW asistente_sql.liquidaciones_honorarios AS
SELECT l.id AS liquidacion_id,
	l.colaborador_membership_id AS profesional_id,
	l.egreso_id,
	l.periodo_desde,
	l.periodo_hasta,
	l.importe_total,
	l.moneda,
	l.estado,
	l.confirmada_en,
	l.liberada_en
FROM liquidacion_honorario l
WHERE l.organization_id = asistente_sql.org() AND l.consultorio_id = asistente_sql.sede();

-- Detalle por clase de cada liquidacion de honorarios (clases grupales).
CREATE VIEW asistente_sql.liquidaciones_honorarios_clase AS
SELECT lc.id AS detalle_id,
	lc.liquidacion_id,
	lc.clase_id,
	lc.colaborador_membership_id AS profesional_id,
	lc.clase_fecha AS fecha,
	lc.clase_titulo AS titulo,
	lc.oferta_id AS servicio_id,
	lc.oferta_nombre AS servicio,
	lc.presentes,
	lc.instructores,
	lc.importe_por_participante,
	lc.importe_clase,
	lc.importe_colaborador,
	lc.moneda
FROM liquidacion_honorario_clase lc
JOIN liquidacion_honorario l ON l.id = lc.liquidacion_id
WHERE lc.organization_id = asistente_sql.org() AND l.consultorio_id = asistente_sql.sede();

-- Practicas (nomenclador): las globales de la plataforma y las propias de la organizacion.
CREATE VIEW asistente_sql.practicas AS
SELECT pa.id AS practica_id,
	pa.codigo,
	pa.name AS nombre,
	(pa.organization_id IS NULL) AS global,
	pa.active AS activa
FROM practica pa
WHERE (pa.organization_id IS NULL OR pa.organization_id = asistente_sql.org())
	AND asistente_sql.org() IS NOT NULL AND pa.deleted_at IS NULL;

-- Convenios con financiadores de la sede.
CREATE VIEW asistente_sql.convenios AS
SELECT cv.id AS convenio_id,
	cv.financiador_id,
	f.nombre AS financiador,
	pc.nombre AS plan,
	cv.codigo,
	cv.nombre,
	cv.modalidad,
	cv.moneda,
	cv.vigencia_desde,
	cv.vigencia_hasta,
	cv.requiere_orden,
	cv.requiere_autorizacion,
	cv.requiere_credencial,
	cv.limite_sesiones_mensual,
	cv.active AS activo
FROM convenio cv
LEFT JOIN financiador f ON f.id = cv.financiador_id
LEFT JOIN plan_cobertura pc ON pc.id = cv.plan_id
WHERE cv.organization_id = asistente_sql.org() AND cv.consultorio_id = asistente_sql.sede()
	AND cv.deleted_at IS NULL;

-- Aranceles de cada convenio: lo que paga el financiador y el coseguro del paciente por practica.
CREATE VIEW asistente_sql.aranceles AS
SELECT ar.id AS arancel_id,
	ar.convenio_id,
	cv.nombre AS convenio,
	f.nombre AS financiador,
	pc.nombre AS plan,
	ar.practica_id,
	pa.codigo AS practica_codigo,
	pa.name AS practica,
	ar.oferta_id AS servicio_id,
	ar.importe_total,
	ar.importe_financiador,
	ar.coseguro,
	ar.moneda,
	ar.vigencia_desde,
	ar.vigencia_hasta,
	ar.active AS activo
FROM convenio_arancel ar
JOIN convenio cv ON cv.id = ar.convenio_id
LEFT JOIN financiador f ON f.id = cv.financiador_id
LEFT JOIN plan_cobertura pc ON pc.id = cv.plan_id
LEFT JOIN practica pa ON pa.id = ar.practica_id
WHERE ar.organization_id = asistente_sql.org() AND ar.consultorio_id = asistente_sql.sede()
	AND ar.deleted_at IS NULL;

-- Ordenes medicas de cada persona, sin numero, matricula ni indicacion clinica.
CREATE VIEW asistente_sql.ordenes_medicas AS
SELECT om.id AS orden_id,
	om.persona_id,
	om.cobertura_id,
	cp.financiador_nombre AS financiador,
	om.profesional_emisor AS medico,
	om.fecha_emision,
	om.sesiones_prescriptas,
	om.vigencia_desde,
	om.vigencia_hasta,
	om.active AS activa
FROM orden_medica om
LEFT JOIN cobertura_paciente cp ON cp.id = om.cobertura_id
WHERE om.organization_id = asistente_sql.org() AND om.deleted_at IS NULL
	AND (om.consultorio_id IS NULL OR om.consultorio_id = asistente_sql.sede());

-- Autorizaciones de financiadores por persona y practica, sin numero. saldo = autorizadas - consumidas.
CREATE VIEW asistente_sql.autorizaciones AS
SELECT au.id AS autorizacion_id,
	au.persona_id,
	au.orden_medica_id AS orden_id,
	cp.financiador_nombre AS financiador,
	au.practica_id,
	pa.name AS practica,
	au.convenio_nombre AS convenio,
	au.estado,
	au.cantidad_autorizada,
	au.cantidad_consumida,
	(au.cantidad_autorizada - au.cantidad_consumida) AS saldo,
	au.vigencia_desde,
	au.vigencia_hasta,
	au.active AS activa
FROM autorizacion au
LEFT JOIN cobertura_paciente cp ON cp.id = au.cobertura_id
LEFT JOIN practica pa ON pa.id = au.practica_id
WHERE au.organization_id = asistente_sql.org() AND au.deleted_at IS NULL
	AND (au.consultorio_id IS NULL OR au.consultorio_id = asistente_sql.sede());

-- Lista de espera de turnos de la sede.
CREATE VIEW asistente_sql.lista_espera AS
SELECT le.id AS espera_id,
	le.persona_id,
	le.oferta_id AS servicio_id,
	le.profesional_membership_id AS profesional_id,
	le.caso_id,
	le.fecha_desde,
	le.fecha_hasta,
	le.dias_semana,
	le.hora_desde,
	le.hora_hasta,
	le.prioridad,
	le.estado,
	le.turno_id,
	le.creada_en,
	le.cerrada_en
FROM lista_espera_turno le
WHERE le.organization_id = asistente_sql.org() AND le.consultorio_id = asistente_sql.sede();

-- Recepciones (check-in) de la sede: una por llegada a un turno.
CREATE VIEW asistente_sql.recepciones AS
SELECT r.id AS recepcion_id,
	r.turno_id,
	t.persona_id,
	r.estado,
	r.modalidad,
	r.practica_id,
	r.llegada_en,
	(r.llegada_en AT TIME ZONE c.timezone)::date AS fecha,
	r.validada_en,
	r.en_espera_desde,
	r.llamada_en,
	r.cerrada_en
FROM recepcion r
JOIN consultorio c ON c.id = r.consultorio_id
LEFT JOIN turno t ON t.id = r.turno_id
WHERE r.organization_id = asistente_sql.org() AND r.consultorio_id = asistente_sql.sede();

-- Productos que vende la sede: packs de sesiones y abonos.
CREATE VIEW asistente_sql.productos AS
SELECT ps.id AS producto_id,
	ps.nombre,
	ps.tipo,
	ps.oferta_id AS servicio_id,
	ps.creditos,
	ps.precio,
	ps.moneda,
	ps.vigencia_dias,
	ps.vigencia_desde,
	ps.vigencia_hasta,
	ps.active AS activo
FROM producto_servicio ps
WHERE ps.organization_id = asistente_sql.org() AND ps.consultorio_id = asistente_sql.sede()
	AND ps.deleted_at IS NULL;

-- Pases vendidos (packs y abonos) de la sede, con sus creditos.
CREATE VIEW asistente_sql.pases AS
SELECT pa.id AS pase_id,
	pa.persona_id,
	pa.producto_id,
	pa.snapshot_nombre AS producto,
	pa.tipo,
	pa.snapshot_oferta_id AS servicio_id,
	pa.snapshot_precio AS precio,
	pa.snapshot_moneda AS moneda,
	pa.creditos_iniciales,
	(pa.creditos_iniciales - pa.creditos_disponibles) AS creditos_usados,
	pa.creditos_disponibles,
	pa.vigencia_desde,
	pa.vigencia_hasta,
	pa.estado,
	pa.renovacion_automatica,
	pa.vendido_en,
	pa.anulado_en
FROM pase_servicio pa
WHERE pa.organization_id = asistente_sql.org() AND pa.consultorio_id = asistente_sql.sede();

-- Feriados del pais del calendario de la sede (AR si la sede no lo configuro).
CREATE VIEW asistente_sql.feriados AS
SELECT fe.id AS feriado_id,
	fe.fecha,
	fe.nombre,
	fe.tipo,
	coalesce(cc.cierra_por_feriado, true) AS cierra_la_sede
FROM feriado fe
LEFT JOIN consultorio_calendario cc
	ON cc.organization_id = asistente_sql.org() AND cc.consultorio_id = asistente_sql.sede()
WHERE asistente_sql.org() IS NOT NULL AND asistente_sql.sede() IS NOT NULL
	AND fe.pais = coalesce(cc.pais, 'AR');

-- Disponibilidad semanal de los profesionales en la sede. dia_semana: 1 = lunes ... 7 = domingo.
CREATE VIEW asistente_sql.disponibilidad AS
SELECT d.id AS disponibilidad_id,
	d.membership_id AS profesional_id,
	d.dia_semana,
	d.hora_desde,
	d.hora_hasta,
	d.vigencia_desde,
	d.vigencia_hasta,
	d.active AS activa
FROM profesional_disponibilidad d
WHERE d.organization_id = asistente_sql.org() AND d.consultorio_id = asistente_sql.sede()
	AND d.deleted_at IS NULL;

-- ============================================================================================
-- Clinicas (prefijo clinico_: requieren hc:read y se auditan)
-- ============================================================================================

-- Casos clinicos de la organizacion, con diagnostico y objetivo.
CREATE VIEW asistente_sql.clinico_casos AS
SELECT ca.id AS caso_id,
	hc.persona_id,
	ca.numero_caso,
	ca.oferta_id AS servicio_id,
	ca.diagnostico_presuntivo AS diagnostico,
	ca.objetivo_terapeutico AS objetivo,
	ca.estado,
	ca.abierto_en,
	ca.cerrado_en,
	ca.motivo_cierre
FROM caso_clinico ca
JOIN historia_clinica hc ON hc.id = ca.historia_clinica_id
WHERE ca.organization_id = asistente_sql.org() AND hc.deleted_at IS NULL;

-- Sesiones de la sede con su contenido clinico.
CREATE VIEW asistente_sql.clinico_sesiones AS
SELECT se.id AS sesion_id,
	hc.persona_id,
	se.caso_id,
	se.turno_id,
	se.oferta_id AS servicio_id,
	se.profesional_membership_id AS profesional_id,
	se.estado,
	se.numero_sesion,
	se.numero_en_caso,
	(coalesce(se.cerrada_en, se.iniciada_en) AT TIME ZONE c.timezone)::date AS fecha,
	se.motivo_clinico AS motivo,
	se.dolor_eva,
	se.dolor_zona,
	se.dolor_lateralidad,
	se.evolucion,
	se.objetivo_sesion,
	se.limitacion_funcional,
	se.respuesta_tratamiento,
	se.tolerancia,
	se.indicaciones,
	se.proxima_conducta,
	se.nota_de_cierre
FROM sesion se
JOIN consultorio c ON c.id = se.consultorio_id
LEFT JOIN historia_clinica hc ON hc.id = se.historia_clinica_id
WHERE se.organization_id = asistente_sql.org() AND se.consultorio_id = asistente_sql.sede()
	AND se.deleted_at IS NULL;

-- Tratamientos realizados en cada sesion de la sede (magnetoterapia, ultrasonido, etc.).
CREATE VIEW asistente_sql.clinico_tratamientos AS
SELECT tr.id AS tratamiento_id,
	tr.sesion_id,
	hc.persona_id,
	coalesce(tr.profesional_membership_id, se.profesional_membership_id) AS profesional_id,
	(coalesce(se.cerrada_en, se.iniciada_en, tr.registrado_en) AT TIME ZONE c.timezone)::date AS fecha,
	tr.practica_id,
	tr.practica_codigo,
	tr.practica_nombre AS practica,
	tr.tecnica,
	tr.zona,
	tr.lateralidad,
	tr.duracion_minutos,
	tr.espacio_nombre AS espacio,
	tr.observacion,
	tr.registrado_en
FROM tratamiento_realizado tr
JOIN sesion se ON se.id = tr.sesion_id
JOIN consultorio c ON c.id = tr.consultorio_id
LEFT JOIN historia_clinica hc ON hc.id = se.historia_clinica_id
WHERE tr.organization_id = asistente_sql.org() AND tr.consultorio_id = asistente_sql.sede()
	AND tr.deleted_at IS NULL AND se.deleted_at IS NULL;

-- Parametros de cada tratamiento (intensidad, frecuencia, tiempo...). Un valor por tipo.
CREATE VIEW asistente_sql.clinico_tratamiento_parametros AS
SELECT tp.id AS parametro_id,
	tp.tratamiento_realizado_id AS tratamiento_id,
	tp.clave AS parametro,
	tp.tipo_dato,
	tp.valor_numerico,
	tp.valor_texto,
	tp.valor_booleano,
	tp.unidad,
	tp.orden
FROM tratamiento_parametro tp
JOIN tratamiento_realizado tr ON tr.id = tp.tratamiento_realizado_id
WHERE tp.organization_id = asistente_sql.org() AND tr.consultorio_id = asistente_sql.sede()
	AND tr.deleted_at IS NULL;

-- Mediciones registradas en las sesiones de la sede (goniometria, fuerza, escalas...).
CREATE VIEW asistente_sql.clinico_mediciones AS
SELECT sm.id AS medicion_id,
	sm.sesion_id,
	hc.persona_id,
	(coalesce(se.cerrada_en, se.iniciada_en, sm.registrada_en) AT TIME ZONE c.timezone)::date AS fecha,
	sm.definicion_id,
	sm.definicion_codigo AS codigo,
	sm.definicion_nombre AS medicion,
	sm.definicion_tipo AS tipo,
	sm.definicion_unidad AS unidad,
	md.mejora_cuando,
	sm.lateralidad,
	sm.valor_numerico,
	sm.valor_texto,
	sm.valor_booleano,
	sm.nota,
	sm.registrada_en
FROM sesion_medicion sm
JOIN sesion se ON se.id = sm.sesion_id
JOIN consultorio c ON c.id = se.consultorio_id
LEFT JOIN historia_clinica hc ON hc.id = se.historia_clinica_id
LEFT JOIN medicion_definicion md ON md.id = sm.definicion_id
WHERE sm.organization_id = asistente_sql.org() AND se.consultorio_id = asistente_sql.sede()
	AND se.deleted_at IS NULL;

-- Antecedentes de la historia clinica de cada persona (organizacion).
CREATE VIEW asistente_sql.clinico_antecedentes AS
SELECT an.id AS antecedente_id,
	hc.persona_id,
	an.tipo,
	an.descripcion,
	an.origen,
	an.registrado_en,
	an.revisado_en
FROM historia_clinica_antecedente an
JOIN historia_clinica hc ON hc.id = an.historia_clinica_id
WHERE an.organization_id = asistente_sql.org() AND an.deleted_at IS NULL AND hc.deleted_at IS NULL;

-- Planes de tratamiento con su version vigente y el resumen de items (organizacion).
CREATE VIEW asistente_sql.clinico_planes AS
SELECT pt.id AS plan_id,
	pt.caso_clinico_id AS caso_id,
	hc.persona_id,
	pt.numero_plan,
	pt.estado,
	pv.numero_version AS version_vigente,
	pv.objetivos,
	pv.indicaciones,
	pv.frecuencia_semanal,
	pv.duracion_semanas,
	(SELECT string_agg(pi.oferta_nombre || ' x' || pi.cantidad_planificada, ', ' ORDER BY pi.id)
		FROM plan_item pi WHERE pi.plan_tratamiento_version_id = pv.id) AS items,
	(SELECT sum(pi.cantidad_planificada)
		FROM plan_item pi WHERE pi.plan_tratamiento_version_id = pv.id) AS sesiones_planificadas,
	pt.creado_en,
	pt.activado_en,
	pt.suspendido_en,
	pt.motivo_suspension,
	pt.finalizado_en,
	pt.motivo_finalizacion
FROM plan_tratamiento pt
JOIN caso_clinico ca ON ca.id = pt.caso_clinico_id
JOIN historia_clinica hc ON hc.id = ca.historia_clinica_id
LEFT JOIN plan_tratamiento_version pv
	ON pv.plan_tratamiento_id = pt.id AND pv.numero_version = pt.ultimo_numero_version
WHERE pt.organization_id = asistente_sql.org() AND hc.deleted_at IS NULL;

-- Derivaciones de una clase grupal a un caso clinico, de la sede.
CREATE VIEW asistente_sql.clinico_derivaciones AS
SELECT dc.id AS derivacion_id,
	dc.persona_id,
	dc.caso_clinico_id AS caso_id,
	dc.plan_tratamiento_id AS plan_id,
	dc.oferta_id AS servicio_id,
	dc.origen,
	dc.estado,
	dc.motivo,
	dc.derivada_en,
	dc.revertida_en,
	dc.motivo_reversion
FROM derivacion_clinica dc
WHERE dc.organization_id = asistente_sql.org() AND dc.consultorio_id = asistente_sql.sede();
