-- =====================================================================================
-- AKINE cierre de F5 — Cobertura elegida al reservar el turno (AKINE-05.02, M12).
--
-- Trazabilidad: plan 05.02 "Validaciones: ... cobertura elegida"; RF-M12-002 (crear turno,
-- CA-M12-002-03: una referencia de otro tenant no puede usarse); RF-M13-003 (la recepcion
-- valida cobertura). Ficha: docs/fases/F5-agenda-y-recepcion.md, registro de cierre.
--
-- QUE ES. La cobertura del paciente con la que se PIENSA atender el turno, elegida al reservar.
-- Es una preferencia, no un veredicto: la recepcion (E-4) la usa como cobertura por defecto al
-- validar, y si ese dia ya no aplica la recepcion queda OBSERVADA —nunca un 4xx—. Particular es
-- NULL: ausencia de cobertura elegida.
--
-- SIN FK, igual que `recepcion.cobertura_id` (V78): `cobertura_paciente` es de `person` y la
-- pertenencia al tenant y a la persona la valida el servicio por `person.spi`. Inmutable desde
-- el INSERT: reprogramar mueve el mismo turno y la conserva.
--
-- Aditiva: columna NULL, sin backfill. Los turnos existentes quedan sin cobertura elegida y la
-- recepcion se comporta como hasta hoy (la primera que aplique).
-- =====================================================================================

ALTER TABLE turno
    ADD COLUMN cobertura_id BIGINT NULL
        COMMENT 'Cobertura del paciente elegida al reservar. NULL = sin elegir (la recepcion usa la primera que aplique).'
        AFTER serie_id;
