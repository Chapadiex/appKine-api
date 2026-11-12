-- AKINE-M12, flujo unico de turnos (TURNO.md, TURNO - Tecnico.md).
--
-- El turno pasa a saber para que se reservo: caso y plan, ambos opcionales porque una primera
-- consulta no tiene ninguno de los dos todavia (TURNO.md §11). Son ids sin FK, igual que
-- cobertura_id (V93): caso y plan son tablas de `clinical`, y `scheduling` las consulta por spi.
--
-- Sobreturno: el unico camino para ocupar un horario tomado. Los tres campos viajan juntos, y el
-- CHECK es lo que impide un sobreturno sin motivo o sin quien lo autorizo aunque alguien inserte
-- por fuera de JPA.
--
-- origen_cancelacion y ausencia_consume_sesion son ATRIBUTOS y no estados: TURNO - Tecnico §27 pide
-- no crear veinte estados, y DP-05 ya separa reserva, recepcion y atencion en tres maquinas.

ALTER TABLE turno
    ADD COLUMN caso_id BIGINT NULL COMMENT 'Caso clinico para el que se reservo. NULL en primera consulta o turno suelto' AFTER cobertura_id,
    ADD COLUMN plan_id BIGINT NULL COMMENT 'Plan de tratamiento para el que se reservo. NULL si no esta asociado a un plan' AFTER caso_id,
    ADD COLUMN origen VARCHAR(16) NULL COMMENT 'Desde donde se inicio la reserva. Solo trazabilidad: no cambia ninguna regla' AFTER plan_id,
    ADD COLUMN sobreturno TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1 si se reservo sobre un horario ocupado, con autorizacion' AFTER origen,
    ADD COLUMN sobreturno_motivo VARCHAR(300) NULL COMMENT 'Por que se autorizo. Obligatorio si sobreturno = 1' AFTER sobreturno,
    ADD COLUMN sobreturno_autorizado_por_cuenta_id BIGINT NULL COMMENT 'Cuenta que autorizo el sobreturno' AFTER sobreturno_motivo,
    ADD COLUMN origen_cancelacion VARCHAR(16) NULL COMMENT 'Quien cancelo: PACIENTE, PROFESIONAL o INSTITUCION. NULL en cancelaciones anteriores a V125' AFTER cancelado_por_cuenta_id,
    ADD COLUMN ausencia_consume_sesion TINYINT(1) NULL COMMENT 'Si la ausencia consume una sesion del plan. NULL si no se decidio o el turno no tiene plan' AFTER ausente_en;

ALTER TABLE turno
    ADD CONSTRAINT ck_turno_sobreturno_completo
        CHECK ((sobreturno = 0 AND sobreturno_motivo IS NULL AND sobreturno_autorizado_por_cuenta_id IS NULL)
            OR (sobreturno = 1 AND sobreturno_motivo IS NOT NULL AND sobreturno_autorizado_por_cuenta_id IS NOT NULL)),
    ADD CONSTRAINT ck_turno_origen_cancelacion
        CHECK (origen_cancelacion IS NULL OR origen_cancelacion IN ('PACIENTE', 'PROFESIONAL', 'INSTITUCION'));

CREATE INDEX ix_turno_plan ON turno (organization_id, plan_id);
