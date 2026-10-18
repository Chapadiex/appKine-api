-- =====================================================================================
-- AKINE-08.06/08.07 — Consumo de credito de un pase por asistencia (RF-M29-003, RN-M29-005).
--
-- V64 creo producto_servicio, pase_servicio y movimiento_pase con el vocabulario completo
-- de RN-M29-004. Esta migracion SOLO SUMA: una columna anulable, su foreign key y un
-- indice. Ningun DROP CHECK, ningun ALTER de una constraint existente (la leccion de V57).
--
-- POR QUE UNA COLUMNA Y NO SOLO origen_id
--
-- La idempotencia del ledger vive en uk_movimiento_origen (organization_id, pase_id,
-- origen_tipo, origen_id). Para el consumo por asistencia el origen es el EVENTO de
-- asistencia que lo produjo (registro o correccion) y no la asistencia misma: una asistencia
-- corregida PRESENTE -> AUSENTE -> PRESENTE consume, devuelve y vuelve a consumir, y con
-- origen_id = asistencia_id el tercer movimiento chocaria contra el primero en el unique.
--
-- Pero entonces "cuanto credito tiene tomado esta asistencia, y de que pase" exige saber que
-- eventos son de que asistencia. asistencia_id lo responde con una suma sobre un indice, sin
-- join, y la foreign key impide que un movimiento apunte a una asistencia que no existe.
-- NULL en COMPRA y en todo movimiento que no nace de una asistencia.
--
-- Propietario: modulo activity (las dos tablas son suyas).
-- =====================================================================================

ALTER TABLE movimiento_pase
    ADD COLUMN asistencia_id BIGINT NULL
        COMMENT 'La asistencia cuyo registro o correccion produjo este movimiento (CONSUMO o DEVOLUCION por correccion). NULL en COMPRA. El neto de una asistencia es SUM(cantidad) WHERE asistencia_id = X, y vale 0 o -1: nunca consume dos veces.'
        AFTER origen_id;

ALTER TABLE movimiento_pase
    ADD CONSTRAINT fk_movimiento_pase_asistencia
        FOREIGN KEY (asistencia_id) REFERENCES asistencia_actividad (id);

CREATE INDEX ix_movimiento_pase_asistencia
    ON movimiento_pase (organization_id, asistencia_id);
