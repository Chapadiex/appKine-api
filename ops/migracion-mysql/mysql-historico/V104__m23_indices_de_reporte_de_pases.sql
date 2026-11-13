-- =================================================================================================
-- AKINE-09.01 — Indices para los reportes de pases y abonos (RF-M23-010)
-- =================================================================================================
--
-- Solo indices. Ninguna tabla nueva, ninguna columna, ningun dato: el reporte de pases se calcula
-- al leer, igual que todo el reporting (V59), y no materializa nada.
--
-- POR QUE ESTOS Y NO OTROS
--
-- El reporte de CLASES no necesita ninguno nuevo: recorta por clase_programada.inicio, que ya cubre
-- ix_clase_sede_inicio (V58), y llega a inscripciones y asistencias por clase_id, que cubren
-- ix_inscripcion_cola (V60) e ix_asistencia_clase (V61).
--
-- El de PASES recorta pase_servicio por sede y por tres columnas distintas, y ninguna tenia indice
-- con la sede adelante: ix_pase_persona y ix_pase_producto empiezan por la persona y el producto,
-- e ix_pase_vencimiento (V101) empieza por el estado sin tenant, porque lo usa el job global.
--
--   vendido_en      ventas y renovaciones del periodo
--   anulado_en      anulaciones del periodo
--   vigencia_hasta  vencidos del periodo y vigentes de hoy
--
-- Y movimiento_pase solo tenia indices por pase (ix_movimiento_pase_orden) y por asistencia: los
-- movimientos de un periodo recorrian el ledger entero del tenant. El join con pase_servicio para
-- saber la sede va por la PK.
--
-- Todos empiezan por organization_id, como toda tabla de negocio (ADR-0004).
-- =================================================================================================

CREATE INDEX ix_pase_sede_venta
    ON pase_servicio (organization_id, consultorio_id, vendido_en);

CREATE INDEX ix_pase_sede_anulacion
    ON pase_servicio (organization_id, consultorio_id, anulado_en);

CREATE INDEX ix_pase_sede_vigencia
    ON pase_servicio (organization_id, consultorio_id, vigencia_hasta);

CREATE INDEX ix_movimiento_pase_ocurrido
    ON movimiento_pase (organization_id, ocurrido_en);
