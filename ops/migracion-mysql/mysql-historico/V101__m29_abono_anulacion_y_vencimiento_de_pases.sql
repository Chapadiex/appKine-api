-- =====================================================================================
-- AKINE-08.07 / 08.08 — Abonos, anulacion y vencimiento de pases (M29).
--
-- V101 la reservo el orquestador para esta rama (V102 tambien y queda sin usar).
--
-- RF-M29-004 (devolver credito por cancelacion), RF-M29-005 (ajuste manual), RF-M29-006 (vencer
-- PaseServicio), RF-M29-007 (vender AbonoServicio), RF-M29-008 (cobertura por abono),
-- RF-M18-010 (obligacion por abono periodico); RN-M29-002..009.
--
-- Propietario de todo lo que toca: modulo `activity`.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE NO HACE, A PROPOSITO
--
-- 1. NO REESCRIBE NINGUN CHECK. ck_movimiento_pase_tipo y ck_movimiento_pase_origen_tipo (V64) ya
--    tienen los cinco tipos y los cinco origenes que esta etapa emite: COMPRA, CONSUMO, AJUSTE,
--    DEVOLUCION, VENCIMIENTO; VENTA, ASISTENCIA, CANCELACION, AJUSTE_MANUAL, VENCIMIENTO. La
--    anulacion descarta el saldo con un AJUSTE de origen AJUSTE_MANUAL: es un acto manual con
--    motivo, y agregar un origen ANULACION obligaba a DROP CHECK + ADD, la operacion que V57
--    demostro que borra en silencio valores que otra migracion acababa de agregar.
--
-- 2. NO CREA UNA TABLA DE ABONOS. V64 ya penso el abono como una fila de pase_servicio: el
--    producto tiene tipo ABONO en su CHECK y la obligacion tiene origen VENTA_ABONO con pase_id
--    NOT NULL. Un abono es un pase cuya vigencia es un PERIODO elegido al vender (RF-M29-007
--    paso 5) y cuyos creditos son las asistencias que el periodo permite (CA-M29-007-06). Cada
--    periodo es una venta: una fila, una obligacion (uk_obligacion_venta), un ledger.
--
-- 3. NO RELAJA ck_pase_saldo_no_negativo. Un ajuste manual no puede dejar el saldo por encima
--    de los creditos iniciales: corrige lo que se compro, no regala lo que no se vendio.
-- ---------------------------------------------------------------------------------------

-- El tipo del pase, congelado al vender como el resto del snapshot. Se podria derivar del
-- producto con un join, pero el tipo decide el origen de la obligacion y la lectura del pase, y
-- el snapshot de V64 ya copia todo lo que define que se vendio. El DEFAULT cubre las filas
-- existentes: hasta hoy solo se vendian packs.
ALTER TABLE pase_servicio
    ADD COLUMN tipo VARCHAR(16) NOT NULL DEFAULT 'PACK'
        COMMENT 'PACK o ABONO, copiado del producto al vender. Un ABONO cubre el periodo vigencia_desde..vigencia_hasta elegido en la venta.'
        AFTER producto_id;

-- Nombre con la tabla adelante: en MySQL el nombre de un CHECK es unico por ESQUEMA, y
-- ck_producto_tipo (V64) ya existe.
ALTER TABLE pase_servicio
    ADD CONSTRAINT ck_pase_servicio_tipo
        CHECK (tipo IN ('PACK', 'ABONO'));

-- La anulacion. Tres columnas y no una tabla de eventos: un pase se anula una sola vez, y la
-- secuencia completa ya vive en movimiento_pase y en audit_event.
ALTER TABLE pase_servicio
    ADD COLUMN anulado_en DATETIME(6) NULL
        COMMENT 'Cuando se anulo. NULL si y solo si estado <> ANULADO.'
        AFTER vendido_en;

ALTER TABLE pase_servicio
    ADD COLUMN anulado_por_cuenta_id BIGINT NULL
        COMMENT 'Quien lo anulo.'
        AFTER anulado_en;

ALTER TABLE pase_servicio
    ADD COLUMN motivo_anulacion VARCHAR(280) NULL
        COMMENT 'Motivo declarado. Obligatorio: una venta que desaparece sin explicacion es lo que una auditoria busca.'
        AFTER anulado_por_cuenta_id;

-- La anulacion es un trio o no es nada, y solo en estado ANULADO. Ninguna fila existente esta
-- ANULADA (V64 no tenia escritor de ese estado), asi que el CHECK no rechaza datos previos.
ALTER TABLE pase_servicio
    ADD CONSTRAINT ck_pase_servicio_anulacion_completa
        CHECK ((estado = 'ANULADO' AND anulado_en IS NOT NULL AND anulado_por_cuenta_id IS NOT NULL
                    AND motivo_anulacion IS NOT NULL)
            OR (estado <> 'ANULADO' AND anulado_en IS NULL AND anulado_por_cuenta_id IS NULL
                    AND motivo_anulacion IS NULL));

-- El job de vencimiento (RF-M29-006) busca en TODOS los tenants los pases ACTIVOS cuya vigencia
-- termino. El tenant va al final y no adelante porque la consulta no lo filtra: es la unica que
-- corre sin contexto de organizacion, y cada pase se vence despues en su propia transaccion con
-- organization_id en el WHERE.
CREATE INDEX ix_pase_vencimiento
    ON pase_servicio (estado, vigencia_hasta, organization_id);
