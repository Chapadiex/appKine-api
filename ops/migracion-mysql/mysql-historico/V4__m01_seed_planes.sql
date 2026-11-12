-- =====================================================================================
-- AKINE-01.01 — Seed minimo del catalogo de planes.
--
-- Por que existe: el onboarding necesita al menos un plan el dia uno (ADR-0008). Sin esto,
-- la primera alta de organizacion no puede asociar suscripcion y RF-M01-001 no se cumple.
--
-- La spec no enumera planes concretos: los valores comerciales de aca son DECISION de
-- diseno y son ajustables por el admin de plataforma via API en etapas futuras. Una vez
-- aplicada, esta migracion NO se edita jamas (ADR-0003): los cambios de catalogo van por
-- la API, no por una migracion nueva a mano.
--
-- Datos: sinteticos y de configuracion, sin ningun dato personal.
-- =====================================================================================

INSERT INTO plan (code, name, active, version, created_at, updated_at) VALUES
    ('BASICO',      'Plan Basico',      1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('PROFESIONAL', 'Plan Profesional', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- BASICO: un consultorio y hasta cinco miembros activos.
INSERT INTO plan_limit (plan_id, limit_code, limit_value, active, created_at, updated_at)
SELECT id, 'MAX_CONSULTORIOS', 1, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM plan WHERE code = 'BASICO';

INSERT INTO plan_limit (plan_id, limit_code, limit_value, active, created_at, updated_at)
SELECT id, 'MAX_MIEMBROS_ACTIVOS', 5, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM plan WHERE code = 'BASICO';

-- PROFESIONAL: limit_value NULL = ilimitado. No es lo mismo que ausencia de fila:
-- la fila declara explicitamente que el limite fue evaluado y no tiene tope.
INSERT INTO plan_limit (plan_id, limit_code, limit_value, active, created_at, updated_at)
SELECT id, 'MAX_CONSULTORIOS', NULL, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM plan WHERE code = 'PROFESIONAL';

INSERT INTO plan_limit (plan_id, limit_code, limit_value, active, created_at, updated_at)
SELECT id, 'MAX_MIEMBROS_ACTIVOS', NULL, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM plan WHERE code = 'PROFESIONAL';

-- Feature = presencia de fila activa. BASICO no lleva ninguna: la ausencia es la negativa.
INSERT INTO plan_feature (plan_id, feature_code, active, created_at, updated_at)
SELECT id, 'REPORTES_AVANZADOS', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM plan WHERE code = 'PROFESIONAL';

INSERT INTO plan_feature (plan_id, feature_code, active, created_at, updated_at)
SELECT id, 'NOTIFICACIONES_PACIENTE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM plan WHERE code = 'PROFESIONAL';
