-- V43 — El precio de la obra social vive en el plan (22/11/2026).
--
-- Hasta aca el convenio era de una SEDE (RN-M16-001): para que una obra social pagara algo habia
-- que cargar el financiador, el plan y despues firmar un convenio por sede con sus aranceles. El
-- usuario lo pidio unificado: se carga el plan y ahi mismo cuanto paga por practica, para todas
-- las sedes.
--
-- Se hace sin tabla nueva: el "convenio del plan" es un convenio con consultorio_id NULL. Asi todo
-- lo que ya guarda una referencia a un convenio (snapshots de obligaciones y autorizaciones,
-- presentaciones) sigue valiendo, y la resolucion del arancel no cambia de forma: busca primero un
-- convenio de la sede y, si no hay uno vigente, el del plan.
--
-- Las FK de consultorio_id (V11) admiten NULL. Las escrituras de un convenio del plan no toman la
-- fila de convenio_lock, que es por sede y tiene FK a consultorio: toman la fila del plan
-- (SELECT ... FOR UPDATE sobre plan_cobertura), que siempre existe.

ALTER TABLE convenio ALTER COLUMN consultorio_id DROP NOT NULL;
ALTER TABLE convenio_arancel ALTER COLUMN consultorio_id DROP NOT NULL;

-- Como mucho un convenio del plan ACTIVO por plan. Es lo que hace idempotente su alta: dos altas
-- simultaneas no pueden dejar dos, y la que pierde relee la de la otra.
CREATE UNIQUE INDEX uk_convenio_del_plan_activo
    ON convenio (organization_id, plan_id)
    WHERE consultorio_id IS NULL AND active = true;

COMMENT ON COLUMN convenio.consultorio_id IS 'Sede del convenio. NULL = convenio del plan: vale para todas las sedes de la organizacion (V43). Un convenio de una sede manda sobre el del plan en esa sede';
COMMENT ON COLUMN convenio_arancel.consultorio_id IS 'COPIA de convenio.consultorio_id. NULL cuando el arancel es del convenio del plan (V43)';
