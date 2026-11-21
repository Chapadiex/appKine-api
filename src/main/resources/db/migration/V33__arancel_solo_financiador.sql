-- Obras sociales simples, paso 2 (docs/diseno/AKINE-obras-sociales-simple.md §5).
--
-- El arancel del convenio guarda sólo lo que paga el financiador (B). El coseguro deja de cargarse:
-- se calcula al cerrar la sesión como max(A - B, 0), donde A es el precio de la oferta ese día.
-- importe_total y coseguro quedan como históricos de los aranceles cargados antes de este cambio;
-- nadie los usa para calcular.

ALTER TABLE convenio_arancel DROP CONSTRAINT ck_arancel_partes_suman_total;
ALTER TABLE convenio_arancel ALTER COLUMN importe_total DROP NOT NULL;
ALTER TABLE convenio_arancel ALTER COLUMN coseguro DROP NOT NULL;

COMMENT ON COLUMN convenio_arancel.importe_total IS 'Histórico (antes de V33). No se usa: el total sale de A y B al cerrar la sesión.';
COMMENT ON COLUMN convenio_arancel.coseguro IS 'Histórico (antes de V33). No se usa: el coseguro es max(A - B, 0) al cerrar la sesión.';
COMMENT ON COLUMN convenio_arancel.importe_financiador IS 'B: lo que paga el financiador por la práctica.';
