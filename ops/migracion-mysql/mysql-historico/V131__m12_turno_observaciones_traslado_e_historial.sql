-- AKINE-M12, agenda operativa (docs/diseno/AKINE-agenda-operativa.md).
--
-- 1. observaciones: texto administrativo del turno ("trae estudios", "viene acompanado"). NUNCA
--    clinico: lo clinico vive en la Sesion y en la HC (DP-05). Nullable, sin default.
--
-- 2. turno_origen_id: el turno del que nace este por un CAMBIO DE SEDE. Reprogramar sigue moviendo
--    el MISMO turno (decision 05.03: la Sesion y la obligacion cuelgan de turno_id); lo unico que no
--    se puede mover es la sede (consultorio_id no es actualizable), y ese caso es cancelar el
--    original y reservar uno nuevo que apunta al original. NULL en todo turno que no es un traslado.
--
-- 3. turno_evento guarda profesional y box antes y despues. Hasta V130 una reprogramacion solo
--    guardaba el horario anterior: "lo atendia Gomez en el Box 2" se perdia en cuanto se movia.
--    Solo las reprogramaciones los llenan; el resto de los eventos los deja en NULL.
--
-- Todo aditivo: columnas nuevas nullable, sin tocar un CHECK existente.

ALTER TABLE turno
    ADD COLUMN observaciones VARCHAR(500) NULL COMMENT 'Observaciones administrativas del turno. Nunca informacion clinica' AFTER sobreturno_autorizado_por_cuenta_id,
    ADD COLUMN turno_origen_id BIGINT NULL COMMENT 'Turno original cuando este nace de un cambio de sede. NULL si no es un traslado' AFTER observaciones;

ALTER TABLE turno
    ADD CONSTRAINT fk_turno_turno_origen
        FOREIGN KEY (turno_origen_id) REFERENCES turno (id);

CREATE INDEX ix_turno_turno_origen ON turno (organization_id, turno_origen_id);

ALTER TABLE turno_evento
    ADD COLUMN profesional_anterior_id BIGINT NULL COMMENT 'Membership del profesional antes de la reprogramacion' AFTER fin_nuevo,
    ADD COLUMN profesional_nuevo_id BIGINT NULL COMMENT 'Membership del profesional despues de la reprogramacion' AFTER profesional_anterior_id,
    ADD COLUMN espacio_anterior_id BIGINT NULL COMMENT 'Box antes de la reprogramacion' AFTER profesional_nuevo_id,
    ADD COLUMN espacio_nuevo_id BIGINT NULL COMMENT 'Box despues de la reprogramacion' AFTER espacio_anterior_id;
