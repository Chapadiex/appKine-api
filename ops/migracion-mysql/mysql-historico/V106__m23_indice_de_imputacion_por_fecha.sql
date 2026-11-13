-- =================================================================================================
-- AKINE-09.01b — Indice para el reporte de ingresos por servicio (RF-M23-009)
-- =================================================================================================
--
-- Solo un indice. Ninguna tabla, columna ni dato: el reporte se calcula al leer (V59, V104).
--
-- El cobrado por servicio recorta cobro_imputacion por imputada_en —cuando la plata se aplico a la
-- deuda—, y la tabla solo tenia indices por obligacion (ix_cobro_imputacion_obligacion, V37) y los
-- uniques por cobro y por clave de idempotencia (V37, V69): un periodo recorria todas las
-- imputaciones del tenant. El join con cobro y con obligacion va por sus PK.
--
-- El resto de 09.01b no necesita indice nuevo:
--   altas del periodo            sesion por ix_sesion_sede_cierre (V59)
--   sesiones despues del alta    sesion por (organization_id, historia_clinica_id, cerrada_en)
--   asistencias despues del alta inscripcion_clase por ix_inscripcion_persona (V60)
--   devengado por oferta         obligacion por ix_obligacion_sede_estado (V36)
--   creditos por servicio        movimiento_pase por ix_movimiento_pase_ocurrido (V104)
--
-- Empieza por organization_id, como todo indice de negocio (ADR-0004).
-- =================================================================================================

CREATE INDEX ix_cobro_imputacion_fecha
    ON cobro_imputacion (organization_id, imputada_en);
