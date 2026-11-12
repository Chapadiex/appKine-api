-- =====================================================================================
-- AKINE — datos iniciales: planes, cuenta de plataforma, catalogos globales y feriados
--
-- Baseline PostgreSQL consolidado (11/11/2026). Reemplaza las 145 migraciones de MySQL, que quedan
-- como archivo historico en ops/migracion-mysql/mysql-historico. Generado por
-- ops/migracion-mysql/generar-baseline.mjs desde el esquema final de MySQL y revisado a mano.
--
-- Convenciones que vienen de MySQL y se conservan:
--   * Textos con COLLATE akine_ci: comparacion sin mayusculas ni tildes, como utf8mb4_0900_ai_ci.
--   * Instantes en timestamptz (UTC); fechas en date; horas de agenda en time.
--   * Las claves *_key generadas sostienen los UNIQUE de la baja logica (centinela 1970-01-01).
--   * Las claves foraneas se crean todas juntas en V11, despues de todas las tablas.
-- =====================================================================================
--
-- Son las filas que sembraban las migraciones de MySQL (V5, V10, V15, V18, V19, V21 y siguientes),
-- con sus mismos identificadores. Las fechas de alta toman el instante de la migracion, como alla.

INSERT INTO platform_schema_info (id, baseline_stage, application_version, contract_version, applied_at) VALUES
    (1, 'AKINE-00.01', '0.0.1-SNAPSHOT', '0.1.0', CURRENT_TIMESTAMP);
SELECT setval(pg_get_serial_sequence('platform_schema_info', 'id'), (SELECT MAX(id) FROM platform_schema_info));

INSERT INTO plan (id, code, name, active, deleted_at, version, created_at, updated_at) VALUES
    (1, 'BASICO', 'Plan Basico', true, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 'PROFESIONAL', 'Plan Profesional', true, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
SELECT setval(pg_get_serial_sequence('plan', 'id'), (SELECT MAX(id) FROM plan));

INSERT INTO plan_feature (id, plan_id, feature_code, active, deleted_at, created_at, updated_at) VALUES
    (1, 2, 'REPORTES_AVANZADOS', true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 2, 'NOTIFICACIONES_PACIENTE', true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
SELECT setval(pg_get_serial_sequence('plan_feature', 'id'), (SELECT MAX(id) FROM plan_feature));

INSERT INTO plan_limit (id, plan_id, limit_code, limit_value, active, deleted_at, created_at, updated_at) VALUES
    (1, 1, 'MAX_CONSULTORIOS', 1, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 1, 'MAX_MIEMBROS_ACTIVOS', 5, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, 2, 'MAX_CONSULTORIOS', NULL, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, 2, 'MAX_MIEMBROS_ACTIVOS', NULL, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
SELECT setval(pg_get_serial_sequence('plan_limit', 'id'), (SELECT MAX(id) FROM plan_limit));

INSERT INTO cuenta (id, email, email_normalizado, nombre, apellido, password_hash, estado, intentos_fallidos, ultimo_login_en, bloqueada_en, bloqueada_motivo, active, deleted_at, version, created_at, updated_at) VALUES
    (1, 'plataforma@akine.app', 'plataforma@akine.app', 'Administracion', 'de Plataforma', NULL, 'ACTIVA', 0, NULL, NULL, NULL, true, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
SELECT setval(pg_get_serial_sequence('cuenta', 'id'), (SELECT MAX(id) FROM cuenta));

INSERT INTO platform_role (id, account_id, role_code, granted_by_account_id, reason, valid_from, valid_until, revoked_by_account_id, revoked_reason, active, deleted_at, version, created_at, updated_at) VALUES
    (1, 1, 'PLATFORM_ADMIN', NULL, 'Bootstrap de plataforma — AKINE-01.03, ADR-0020, decision D-2/D-14 del 23/08/2026', CURRENT_TIMESTAMP, NULL, NULL, NULL, true, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
SELECT setval(pg_get_serial_sequence('platform_role', 'id'), (SELECT MAX(id) FROM platform_role));

INSERT INTO especialidad (id, organization_id, codigo, name, descripcion, valid_from, valid_until, active, deleted_at, deactivation_reason, version, created_at, updated_at) VALUES
    (1, NULL, 'KINESIOLOGIA', 'Kinesiologia', 'Rehabilitacion del movimiento y tratamiento de la funcion motora.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, NULL, 'FISIOTERAPIA', 'Fisioterapia', 'Agentes fisicos aplicados al tratamiento del dolor y la inflamacion.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, NULL, 'TERAPIA_OCUPACIONAL', 'Terapia ocupacional', 'Recuperacion de la autonomia en las actividades de la vida diaria.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, NULL, 'FONOAUDIOLOGIA', 'Fonoaudiologia', 'Evaluacion y tratamiento del habla, la voz, la audicion y la deglucion.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (5, NULL, 'NUTRICION', 'Nutricion', 'Valoracion nutricional y plan alimentario.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (6, NULL, 'PSICOLOGIA', 'Psicologia', 'Atencion psicologica ambulatoria.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
SELECT setval(pg_get_serial_sequence('especialidad', 'id'), (SELECT MAX(id) FROM especialidad));

INSERT INTO practica (id, organization_id, especialidad_id, codigo, name, descripcion, valid_from, valid_until, active, deleted_at, deactivation_reason, version, created_at, updated_at) VALUES
    (1, NULL, 1, 'KIN_EVAL', 'Evaluacion kinesica', 'Primera consulta: anamnesis, examen funcional y plan.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, NULL, 1, 'KIN_SESION', 'Sesion de kinesiologia', 'Sesion de tratamiento kinesico individual.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, NULL, 1, 'KIN_RESPIRAT', 'Kinesiologia respiratoria', 'Tecnicas de higiene bronquial y reeducacion respiratoria.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, NULL, 1, 'KIN_DOMICILIO', 'Kinesiologia a domicilio', 'Sesion de tratamiento kinesico en el domicilio del paciente.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (8, NULL, 2, 'FIS_MAGNETO', 'Magnetoterapia', 'Campos magneticos de baja frecuencia.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (9, NULL, 2, 'FIS_ULTRASONIDO', 'Ultrasonido terapeutico', 'Ultrasonido aplicado a tejidos blandos.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (10, NULL, 2, 'FIS_ELECTRO', 'Electroterapia', 'Corrientes analgesicas y de estimulacion muscular.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (11, NULL, 2, 'FIS_LASER', 'Laserterapia', 'Laser de baja potencia con fin analgesico y antiinflamatorio.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (15, NULL, 3, 'TO_EVAL', 'Evaluacion de terapia ocupacional', 'Valoracion funcional de las actividades de la vida diaria.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (16, NULL, 3, 'TO_SESION', 'Sesion de terapia ocupacional', 'Sesion individual de reeducacion funcional.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (18, NULL, 4, 'FON_EVAL', 'Evaluacion fonoaudiologica', 'Valoracion del habla, la voz y la deglucion.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (19, NULL, 4, 'FON_SESION', 'Sesion de fonoaudiologia', 'Sesion individual de tratamiento fonoaudiologico.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (21, NULL, 5, 'NUT_PRIMERA', 'Consulta nutricional inicial', 'Valoracion antropometrica y plan alimentario.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (22, NULL, 5, 'NUT_CONTROL', 'Control nutricional', 'Seguimiento de la evolucion y ajuste del plan.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (24, NULL, 6, 'PSI_PRIMERA', 'Primera entrevista psicologica', 'Entrevista de admision y encuadre.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (25, NULL, 6, 'PSI_SESION', 'Sesion de psicoterapia', 'Sesion individual de psicoterapia.', CURRENT_TIMESTAMP, NULL, true, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
SELECT setval(pg_get_serial_sequence('practica', 'id'), (SELECT MAX(id) FROM practica));

INSERT INTO feriado (id, pais, fecha, nombre, tipo, created_at, updated_at) VALUES
    (1, 'AR', '2026-01-01', 'Año Nuevo', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 'AR', '2026-03-24', 'Día Nacional de la Memoria por la Verdad y la Justicia', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, 'AR', '2026-04-02', 'Día del Veterano y de los Caídos en la Guerra de Malvinas', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, 'AR', '2026-05-01', 'Día del Trabajador', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (5, 'AR', '2026-05-25', 'Día de la Revolución de Mayo', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (6, 'AR', '2026-06-20', 'Paso a la Inmortalidad del General Manuel Belgrano', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (7, 'AR', '2026-07-09', 'Día de la Independencia', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (8, 'AR', '2026-08-17', 'Paso a la Inmortalidad del General José de San Martín', 'TRASLADABLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (9, 'AR', '2026-10-12', 'Día del Respeto a la Diversidad Cultural', 'TRASLADABLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (10, 'AR', '2026-11-23', 'Día de la Soberanía Nacional', 'TRASLADABLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (11, 'AR', '2026-12-08', 'Inmaculada Concepción de María', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (12, 'AR', '2026-12-25', 'Navidad', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (13, 'AR', '2027-01-01', 'Año Nuevo', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (14, 'AR', '2027-03-24', 'Día Nacional de la Memoria por la Verdad y la Justicia', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (15, 'AR', '2027-04-02', 'Día del Veterano y de los Caídos en la Guerra de Malvinas', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (16, 'AR', '2027-05-01', 'Día del Trabajador', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (17, 'AR', '2027-05-25', 'Día de la Revolución de Mayo', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (18, 'AR', '2027-06-20', 'Paso a la Inmortalidad del General Manuel Belgrano', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (19, 'AR', '2027-07-09', 'Día de la Independencia', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (20, 'AR', '2027-12-08', 'Inmaculada Concepción de María', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (21, 'AR', '2027-12-25', 'Navidad', 'INAMOVIBLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
SELECT setval(pg_get_serial_sequence('feriado', 'id'), (SELECT MAX(id) FROM feriado));
