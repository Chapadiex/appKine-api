-- =====================================================================================
-- AKINE — El catalogo queda con tres planes (19/11/2026).
--
-- V13 sembro BASICO y PROFESIONAL; V28 los dejo sin ofrecer y agrego BASE, CRECIMIENTO y RED.
-- Desde aca los dos viejos dejan de existir: quien los tenia pasa al plan nuevo que le da lo
-- mismo, asi nadie pierde capacidad.
--
--   BASICO      -> BASE  (1 sede, 5 miembros activos, sin features: identico)
--   PROFESIONAL -> RED   (sin tope de sedes ni de miembros, las dos features: identico)
--
-- Tablas que guardan un plan del catalogo (se revisaron V1..V28):
--   subscription.plan_id                       FK a plan
--   subscription_transition.from/to_plan_id    referencia logica, sin FK
--   plan_limit.plan_id, plan_feature.plan_id   FK a plan
-- Ninguna otra tabla guarda el plan ni su codigo: organization_onboarding, onboarding_registro
-- y solicitud_de_prueba no lo tienen, y los plan_id / plan_codigo de cobertura_paciente,
-- convenio, turno, lista_espera_turno y obligacion son de plan_cobertura o plan_tratamiento.
-- audit_event.details conserva el codigo viejo como texto historico y no se toca.
-- =====================================================================================

-- 1. Suscripciones.
UPDATE subscription s
   SET plan_id = nuevo.id, updated_at = CURRENT_TIMESTAMP
  FROM plan viejo
  JOIN plan nuevo ON nuevo.code = CASE viejo.code WHEN 'BASICO' THEN 'BASE' ELSE 'RED' END
 WHERE s.plan_id = viejo.id
   AND viejo.code IN ('BASICO', 'PROFESIONAL');

-- 2. Historial de la suscripcion. Es append-only para la aplicacion, pero el servicio resuelve
-- el codigo de cada fila contra el catalogo (requireById): dejar los ids viejos haria fallar la
-- lectura del historial de cualquier centro que nacio con BASICO. Se reapuntan los ids; el resto
-- de la fila (estados, motivo, actor, instante) queda como estaba.
UPDATE subscription_transition t
   SET from_plan_id = nuevo.id
  FROM plan viejo
  JOIN plan nuevo ON nuevo.code = CASE viejo.code WHEN 'BASICO' THEN 'BASE' ELSE 'RED' END
 WHERE t.from_plan_id = viejo.id
   AND viejo.code IN ('BASICO', 'PROFESIONAL');

UPDATE subscription_transition t
   SET to_plan_id = nuevo.id
  FROM plan viejo
  JOIN plan nuevo ON nuevo.code = CASE viejo.code WHEN 'BASICO' THEN 'BASE' ELSE 'RED' END
 WHERE t.to_plan_id = viejo.id
   AND viejo.code IN ('BASICO', 'PROFESIONAL');

-- 3. Los planes viejos, con sus limites y features.
DELETE FROM plan_limit WHERE plan_id IN (SELECT id FROM plan WHERE code IN ('BASICO', 'PROFESIONAL'));
DELETE FROM plan_feature WHERE plan_id IN (SELECT id FROM plan WHERE code IN ('BASICO', 'PROFESIONAL'));
DELETE FROM plan WHERE code IN ('BASICO', 'PROFESIONAL');

-- 4. Nombres visibles. El codigo no cambia.
UPDATE plan SET name = 'Plan Básico', updated_at = CURRENT_TIMESTAMP WHERE code = 'BASE';
UPDATE plan SET name = 'Plan Intermedio', updated_at = CURRENT_TIMESTAMP WHERE code = 'CRECIMIENTO';
UPDATE plan SET name = 'Plan Plus', updated_at = CURRENT_TIMESTAMP WHERE code = 'RED';

COMMENT ON COLUMN plan.ofrecido IS 'Si la plataforma lo ofrece al dar de alta o cambiar la contratacion de un centro. Desde V37 los tres planes del catalogo se ofrecen; un plan que se retire a futuro queda en false y sigue vigente para quien lo tiene';
