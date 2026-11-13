-- AKINE-M12, bloqueos de agenda (TURNO - Tecnico §34): la capacitacion es un motivo propio.
--
-- Reemplazar un CHECK no es aditivo: hay que repetir TODOS los valores vigentes, o se borra lo que
-- otra migracion agrego. Es la lista de V23 mas CAPACITACION.

ALTER TABLE disponibilidad_excepcion
    DROP CHECK ck_disponibilidad_excepcion_motivo;

ALTER TABLE disponibilidad_excepcion
    ADD CONSTRAINT ck_disponibilidad_excepcion_motivo
        CHECK (motivo IN ('AUSENCIA', 'LICENCIA', 'CAPACITACION', 'FERIADO', 'BLOQUEO', 'AMPLIACION', 'OTRO'));
