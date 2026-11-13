-- AKINE-M05, agenda operativa (docs/diseno/AKINE-agenda-operativa.md).
--
-- 1. Quien cargo cada bloque de horario y cada excepcion. Hasta aca el autor vivia solo en
--    audit_event; la pantalla de excepciones lo pide al lado de la fila. NULL en las filas
--    anteriores a esta migracion: el dato no existia y no se inventa (el backfill desde
--    audit_event no es confiable para las filas que nacieron por migraciones o seeds).
--
-- 2. VACACIONES como motivo propio de excepcion.
--
-- Reemplazar un CHECK NO es aditivo: hay que repetir TODOS los valores vigentes, o se borra lo que
-- otra migracion agrego (V57 borro el valor que V56 habia agregado). La lista es la de V127
-- (V23 + CAPACITACION) mas VACACIONES.

ALTER TABLE disponibilidad_excepcion
    ADD COLUMN created_by_cuenta_id BIGINT NULL COMMENT 'Cuenta que cargo la excepcion. NULL en las anteriores a V132' AFTER notes;

ALTER TABLE profesional_disponibilidad
    ADD COLUMN created_by_cuenta_id BIGINT NULL COMMENT 'Cuenta que cargo el bloque. NULL en los anteriores a V132';

ALTER TABLE disponibilidad_excepcion
    DROP CHECK ck_disponibilidad_excepcion_motivo;

ALTER TABLE disponibilidad_excepcion
    ADD CONSTRAINT ck_disponibilidad_excepcion_motivo
        CHECK (motivo IN ('AUSENCIA', 'LICENCIA', 'VACACIONES', 'CAPACITACION', 'FERIADO', 'BLOQUEO', 'AMPLIACION', 'OTRO'));
