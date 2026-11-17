-- Vistas de solo lectura para las consultas libres del asistente de datos (modulo assistant).
--
-- El modelo de lenguaje escribe un SELECT sobre ESTE esquema y nada mas: el validador rechaza
-- cualquier otra relacion, funcion fuera de la lista o mas de una sentencia, y el ejecutor corre
-- en una transaccion READ ONLY con tiempo maximo. Cada vista se recorta a la organizacion y, si el
-- dato es de una sede, a la sede activa, leyendo dos parametros que el ejecutor fija con
-- set_config(..., true) dentro de la misma transaccion. Sin parametros, no devuelven filas.
--
-- No exponen texto clinico (evolucion, diagnostico, indicaciones, notas), documentos, correos ni
-- telefonos. Los nombres de columna estan en castellano para que el modelo los use sin traducir.

CREATE SCHEMA IF NOT EXISTS asistente_sql;

-- Organizacion y sede del pedido. NULL si no se fijaron: las vistas quedan vacias.
CREATE OR REPLACE FUNCTION asistente_sql.org() RETURNS bigint
	LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('akine.asistente.organizacion', true), '')::bigint $$;

CREATE OR REPLACE FUNCTION asistente_sql.sede() RETURNS bigint
	LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('akine.asistente.sede', true), '')::bigint $$;

-- Personas del padron de la organizacion, con su perfil de paciente si lo tienen.
CREATE VIEW asistente_sql.personas AS
SELECT p.id AS persona_id,
	p.apellido,
	p.nombre,
	p.fecha_nacimiento,
	date_part('year', age(p.fecha_nacimiento))::int AS edad,
	p.active AS activa,
	(pp.id IS NOT NULL AND pp.active) AS es_paciente,
	pp.activado_en AS paciente_desde,
	p.created_at AS alta_en
FROM persona p
LEFT JOIN perfil_paciente pp ON pp.persona_id = p.id AND pp.deleted_at IS NULL
WHERE p.organization_id = asistente_sql.org() AND p.deleted_at IS NULL;

-- Coberturas (obra social / prepaga) de cada persona. Sin numero de afiliado.
CREATE VIEW asistente_sql.coberturas AS
SELECT c.id AS cobertura_id,
	c.persona_id,
	c.tipo,
	c.financiador_nombre AS financiador,
	c.financiador_tipo,
	c.plan_nombre AS plan,
	c.principal,
	c.vigencia_desde,
	c.vigencia_hasta,
	c.active AS activa
FROM cobertura_paciente c
WHERE c.organization_id = asistente_sql.org() AND c.deleted_at IS NULL;

-- Profesionales y demas miembros del equipo.
CREATE VIEW asistente_sql.profesionales AS
SELECT m.id AS profesional_id,
	cu.apellido,
	cu.nombre,
	m.role_code AS rol,
	m.consultorio_id AS sede_id,
	(m.active AND m.deleted_at IS NULL) AS activo
FROM membership m
JOIN cuenta cu ON cu.id = m.account_id
WHERE m.organization_id = asistente_sql.org();

-- Servicios que ofrece la sede.
CREATE VIEW asistente_sql.servicios AS
SELECT o.id AS servicio_id,
	coalesce(o.nombre_comercial, s.nombre) AS nombre,
	o.modalidad,
	o.duracion_minutos,
	o.capacidad,
	o.precio_base,
	o.moneda,
	o.admite_obra_social,
	o.active AS activo
FROM oferta_servicio_consultorio o
JOIN servicio s ON s.id = o.servicio_id
WHERE o.organization_id = asistente_sql.org() AND o.consultorio_id = asistente_sql.sede()
	AND o.deleted_at IS NULL;

-- Turnos de la sede. "fecha" es el dia en la zona horaria de la sede. Incluye cancelados.
CREATE VIEW asistente_sql.turnos AS
SELECT t.id AS turno_id,
	t.persona_id,
	t.oferta_id AS servicio_id,
	t.profesional_membership_id AS profesional_id,
	t.inicio,
	t.fin,
	(t.inicio AT TIME ZONE c.timezone)::date AS fecha,
	t.estado,
	t.sobreturno,
	t.origen,
	t.cancelado_en,
	t.llegada_en
FROM turno t
JOIN consultorio c ON c.id = t.consultorio_id
WHERE t.organization_id = asistente_sql.org() AND t.consultorio_id = asistente_sql.sede();

-- Sesiones (atenciones) de la sede, sin contenido clinico.
CREATE VIEW asistente_sql.sesiones AS
SELECT se.id AS sesion_id,
	hc.persona_id,
	se.turno_id,
	se.caso_id,
	se.oferta_id AS servicio_id,
	se.profesional_membership_id AS profesional_id,
	se.estado,
	se.modo,
	se.asistencia,
	se.numero_sesion,
	se.iniciada_en,
	se.cerrada_en,
	(coalesce(se.cerrada_en, se.iniciada_en) AT TIME ZONE c.timezone)::date AS fecha
FROM sesion se
JOIN consultorio c ON c.id = se.consultorio_id
LEFT JOIN historia_clinica hc ON hc.id = se.historia_clinica_id
WHERE se.organization_id = asistente_sql.org() AND se.consultorio_id = asistente_sql.sede()
	AND se.deleted_at IS NULL;

-- Casos clinicos de la organizacion, sin diagnostico ni objetivo.
CREATE VIEW asistente_sql.casos AS
SELECT ca.id AS caso_id,
	hc.persona_id,
	ca.numero_caso,
	ca.oferta_id AS servicio_id,
	ca.estado,
	ca.abierto_en,
	ca.cerrado_en
FROM caso_clinico ca
JOIN historia_clinica hc ON hc.id = ca.historia_clinica_id
WHERE ca.organization_id = asistente_sql.org();

-- Clases grupales de la sede.
CREATE VIEW asistente_sql.clases AS
SELECT cl.id AS clase_id,
	cl.titulo,
	cl.oferta_id AS servicio_id,
	cl.profesional_membership_id AS profesional_id,
	cl.inicio,
	cl.fin,
	(cl.inicio AT TIME ZONE c.timezone)::date AS fecha,
	cl.capacidad,
	cl.cupo_ocupado,
	cl.estado
FROM clase_programada cl
JOIN consultorio c ON c.id = cl.consultorio_id
WHERE cl.organization_id = asistente_sql.org() AND cl.consultorio_id = asistente_sql.sede()
	AND cl.deleted_at IS NULL;

CREATE VIEW asistente_sql.inscripciones AS
SELECT i.id AS inscripcion_id,
	i.clase_id,
	i.persona_id,
	i.estado,
	i.inscripto_en,
	i.cancelada_en
FROM inscripcion_clase i
WHERE i.organization_id = asistente_sql.org() AND i.consultorio_id = asistente_sql.sede()
	AND i.deleted_at IS NULL;

CREATE VIEW asistente_sql.asistencias_clase AS
SELECT a.id AS asistencia_id,
	a.clase_id,
	a.persona_id,
	a.resultado,
	a.registrada_en
FROM asistencia_actividad a
WHERE a.organization_id = asistente_sql.org() AND a.consultorio_id = asistente_sql.sede();

-- Deudas (obligaciones) de la sede: de pacientes o de financiadores.
CREATE VIEW asistente_sql.deudas AS
SELECT ob.id AS deuda_id,
	ob.persona_id,
	ob.responsable,
	f.nombre AS financiador,
	coalesce(ob.snapshot_nombre, ob.concepto) AS concepto,
	ob.importe_original,
	ob.saldo,
	ob.moneda,
	ob.estado,
	ob.devengada_en,
	(ob.devengada_en AT TIME ZONE c.timezone)::date AS fecha
FROM obligacion ob
JOIN consultorio c ON c.id = ob.consultorio_id
LEFT JOIN financiador f ON f.id = ob.financiador_id
WHERE ob.organization_id = asistente_sql.org() AND ob.consultorio_id = asistente_sql.sede()
	AND ob.deleted_at IS NULL;

-- Cobros de la sede. "anulado" marca los anulados (siguen en la vista).
CREATE VIEW asistente_sql.cobros AS
SELECT co.id AS cobro_id,
	co.persona_id,
	co.total,
	co.moneda,
	co.saldo_a_favor,
	co.cobrado_en,
	(co.cobrado_en AT TIME ZONE c.timezone)::date AS fecha,
	(co.deleted_at IS NOT NULL) AS anulado
FROM cobro co
JOIN consultorio c ON c.id = co.consultorio_id
WHERE co.organization_id = asistente_sql.org() AND co.consultorio_id = asistente_sql.sede();

-- Movimientos de caja de la sede.
CREATE VIEW asistente_sql.movimientos_caja AS
SELECT mc.id AS movimiento_id,
	mc.fecha_negocio AS fecha,
	mc.tipo,
	mc.medio,
	mc.importe,
	mc.moneda,
	mc.concepto,
	mc.registrado_en
FROM movimiento_caja mc
WHERE mc.organization_id = asistente_sql.org() AND mc.consultorio_id = asistente_sql.sede();

-- Egresos (gastos y pagos a profesionales) de la sede, sin documento del beneficiario.
CREATE VIEW asistente_sql.egresos AS
SELECT e.id AS egreso_id,
	e.categoria,
	e.beneficiario_nombre AS beneficiario,
	e.concepto,
	e.importe_total,
	e.saldo_pendiente,
	e.moneda,
	e.estado,
	e.registrado_en,
	(e.registrado_en AT TIME ZONE c.timezone)::date AS fecha
FROM egreso e
JOIN consultorio c ON c.id = e.consultorio_id
WHERE e.organization_id = asistente_sql.org() AND e.consultorio_id = asistente_sql.sede();

-- Financiadores (obras sociales y prepagas) de la organizacion.
CREATE VIEW asistente_sql.financiadores AS
SELECT f.id AS financiador_id,
	f.nombre,
	f.tipo,
	f.active AS activo
FROM financiador f
WHERE f.organization_id = asistente_sql.org() AND f.deleted_at IS NULL;
