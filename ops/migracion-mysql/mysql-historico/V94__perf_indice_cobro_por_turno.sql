-- =====================================================================================
-- AKINE — Indice de cobros por turno (V94) (docs/carga.md §9).
--
-- La recepcion del dia y la agenda leen el prepago de cada turno con
--   SELECT ... FROM cobro WHERE organization_id = ? AND turno_id IN (...) AND deleted_at IS NULL
-- y ningun indice empieza por (organization_id, turno_id): `uk_cobro_prepago_turno_vigente`
-- indexa la columna generada `turno_prepago_vigente`, no `turno_id`. MySQL recorria todos los
-- cobros de la organizacion en cada pedido —1 400 filas por consulta con 2 700 cobros en la
-- base de carga, 5,9 millones de filas examinadas en una corrida— y crece con cada cobro.
--
-- Solo agrega un indice: no cambia datos ni restricciones. Lleva organization_id adelante,
-- como todo indice de una tabla de tenant.
-- =====================================================================================

CREATE INDEX ix_cobro_turno ON cobro (organization_id, turno_id);
