-- =================================================================================================
-- DP-39 (U-50, RF-M23-009) — El egreso se puede imputar a una oferta
-- =================================================================================================
--
-- Una columna opcional y su FK. Es el cimiento que 09.01b declaro faltante para la rentabilidad
-- por servicio: hasta aca ningun egreso decia de que servicio era costo.
--
--   * NULL = egreso no imputado (alquiler, impuestos, sueldos de administracion...). Es el valor
--     de todas las filas existentes: ninguna se reinterpreta, y el reporte las muestra como
--     "sin imputar", no como costo de nadie.
--   * La oferta tiene que ser de la MISMA organizacion y de la MISMA sede que el egreso. La FK
--     simple no lo puede expresar (oferta_servicio_consultorio no tiene un unique por
--     (id, organization_id, consultorio_id) contra el cual apuntar), asi que lo valida
--     EgresoService contra offering.spi.OfertaDirectory#find, que filtra por las dos. Mismo
--     criterio que el resto de las FK a oferta (V36, V58, V64).
--   * Se edita mientras el egreso sea BORRADOR, como el resto de sus datos (RF-M22-001).
--
-- Sin indice nuevo: la rentabilidad recorta egreso por ix_egreso_sede_estado
-- (organization_id, consultorio_id, estado, registrado_en), el mismo criterio que el indicador
-- egresos-confirmados del reporte economico, y agrupa por oferta_id sobre ese recorte. La FK crea
-- su propio indice sobre oferta_id.
-- =================================================================================================

ALTER TABLE egreso
    ADD COLUMN oferta_id BIGINT NULL
        COMMENT 'Oferta de la misma sede a la que se imputa el costo (DP-39). NULL = sin imputar.'
        AFTER consultorio_id,
    ADD CONSTRAINT fk_egreso_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id);
