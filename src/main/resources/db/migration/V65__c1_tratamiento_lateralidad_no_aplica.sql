-- =====================================================================================
-- V65 (C-1, G2) — ck_tratamiento_lateralidad admite NO_APLICA.
--
-- V55 declaro el CHECK con IZQUIERDA / DERECHA / BILATERAL, pero el enum
-- encounter.domain.Lateralidad, los DTOs y el comentario de la propia columna incluyen
-- NO_APLICA: registrar un tratamiento en una zona central (lumbar, cervical) con NO_APLICA
-- violaba el CHECK. V55 no se edita (una migracion aplicada es historia): se recrea el
-- constraint con el mismo nombre y el vocabulario completo del enum.
--
-- NULL sigue siendo legitimo ("no lo cargue"). ck_tratamiento_lateralidad_con_zona no cambia:
-- NO_APLICA tambien exige zona.
-- =====================================================================================

ALTER TABLE tratamiento_realizado
    DROP CHECK ck_tratamiento_lateralidad;

ALTER TABLE tratamiento_realizado
    ADD CONSTRAINT ck_tratamiento_lateralidad
        CHECK (lateralidad IS NULL
            OR lateralidad IN ('IZQUIERDA', 'DERECHA', 'BILATERAL', 'NO_APLICA'));
