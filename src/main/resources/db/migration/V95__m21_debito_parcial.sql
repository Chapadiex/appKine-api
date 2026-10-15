-- =====================================================================================
-- V95 — AKINE-07.04b: el debito parcial de una presentacion (decision U-11, PROPUESTA)
-- =====================================================================================
--
-- Diseno: docs/diseno/AKINE-07.04b-reparos-y-debito-parcial.md §3.
--
-- EL DEFECTO. Debitar 1.000 de un item de 6.000 lo dejaba DEBITADO —que libera la
-- obligacion— y la conciliacion salteaba todo item que no fuera INCLUIDO. El lote cuadraba
-- (6.000 - 1.000 - 5.000 = 0) y la obligacion del financiador seguia PENDIENTE por 6.000:
-- los 5.000 cobrados no saldaban nada, y la obligacion quedaba libre por 6.000 para que otro
-- lote la reclamara entera antes de conciliar este.
--
-- EL MODELO MINIMO. Ninguna tabla ni columna nueva: `importe_debitado` ya existe (V56) y la
-- obligacion ya sabe tener saldo parcial (`saldo` + estado PARCIAL, V36). Lo que cambia es el
-- ESTADO del item, porque de el depende la columna generada que hace cumplir RN-M21-003:
--
--   DEBITADO_PARCIAL  debito menor que lo presentado, lote en curso.     OCUPA
--   ACEPTADO_PARCIAL  conciliado: se saldo presentado - debitado.        LIBERA
--
-- DEBITADO_PARCIAL ocupa porque lo NO debitado todavia se cobra en este lote. ACEPTADO_PARCIAL
-- libera porque lo que queda pendiente en la obligacion es solo lo debitado, y un lote nuevo
-- lo incluye por `obligacion.saldo`, que es exactamente eso.
--
-- SIN MIGRACION DE DATOS, a proposito. Un DEBITADO parcial anterior a V95 sigue con la regla
-- vieja. Reclasificarlo a DEBITADO_PARCIAL lo haria ocupar una obligacion que en el medio pudo
-- entrar a otro lote (el unique lo rechazaria y la migracion no correria), y tocar lotes ya
-- conciliados moveria saldos de cuentas corrientes cerradas. Para encontrarlos:
--   SELECT * FROM presentacion_item
--    WHERE estado = 'DEBITADO' AND importe_debitado < importe_presentado;
--
-- `estado` sigue en VARCHAR(16): 'DEBITADO_PARCIAL' y 'ACEPTADO_PARCIAL' miden exactamente 16.
-- =====================================================================================

-- 1. El CHECK de estados se REEMPLAZA por la UNION. Un CHECK que se reemplaza tiene que listar
--    todo lo anterior: V57 borro asi un valor que V56 habia agregado y ningun gate lo vio.
ALTER TABLE presentacion_item DROP CHECK ck_presentacion_item_estado;

ALTER TABLE presentacion_item
    ADD CONSTRAINT ck_presentacion_item_estado
        CHECK (estado IN ('INCLUIDO', 'ACEPTADO', 'DEBITADO', 'ANULADO',
                          'DEBITADO_PARCIAL', 'ACEPTADO_PARCIAL'));

-- 2. La marca generada: DEBITADO_PARCIAL tambien ocupa. El unique uk_presentacion_item_ocupa
--    (organization_id, obligacion_id, ocupa_marca) no se toca; se recalcula con la columna.
ALTER TABLE presentacion_item
    MODIFY COLUMN ocupa_marca TINYINT
        AS (IF(estado IN ('INCLUIDO', 'ACEPTADO', 'DEBITADO_PARCIAL'), 1, NULL)) STORED;

-- 3. Un debito sin motivo, instante y actor es una prestacion que desaparecio del reclamo sin
--    explicacion. Vale tambien para los dos estados parciales.
ALTER TABLE presentacion_item DROP CHECK ck_presentacion_item_debito_completo;

ALTER TABLE presentacion_item
    ADD CONSTRAINT ck_presentacion_item_debito_completo
        CHECK (estado NOT IN ('DEBITADO', 'DEBITADO_PARCIAL', 'ACEPTADO_PARCIAL')
            OR (motivo_debito IS NOT NULL AND debitado_en IS NOT NULL
                AND debitado_por_cuenta_id IS NOT NULL AND importe_debitado > 0));

-- 4. Parcial quiere decir parcial: un "parcial" por el total es un DEBITADO que dejaria la
--    obligacion ocupada sin nada que cobrar en este lote.
ALTER TABLE presentacion_item
    ADD CONSTRAINT ck_presentacion_item_debito_parcial
        CHECK (estado NOT IN ('DEBITADO_PARCIAL', 'ACEPTADO_PARCIAL')
            OR importe_debitado < importe_presentado);
