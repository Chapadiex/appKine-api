-- =====================================================================================
-- AKINE F-3 — Anticipos, imputacion posterior, anulacion y reintegro de cobros (M19).
--
-- Plan §"Etapa AKINE-07.02" (lo que 07.02 dejo afuera por falta de Caja); DP-06/ADR-0013;
-- RF-M19-003, RF-M19-007, RN-M19-003, RN-M19-004. Diseno: docs/diseno/AKINE-F-3-anticipos.md.
--
-- V37 dejo los anticipos y la anulacion FUERA porque sin caja eran medias funcionalidades.
-- La caja existe desde V54, asi que entran ahora, sobre las mismas tablas:
--
-- 1. EL ANTICIPO NO ES UNA TABLA. Es un cobro con saldo_a_favor > 0: plata recibida que
--    todavia no se aplico a ninguna deuda. Entra a la caja UNA vez, al cobrar. La invariante
--    pasa a ser `total = suma(imputaciones) + saldo_a_favor + suma(reintegros)` y, como las
--    de V37, la verifica la aplicacion: MySQL no admite subconsultas en un CHECK. Lo que el
--    CHECK si sostiene es el rango, igual que el saldo de la obligacion en V36.
--
-- 2. LA IMPUTACION POSTERIOR ES UNA FILA MAS DE cobro_imputacion. Lleva cuando y quien, y su
--    propia clave de idempotencia. NO mueve caja: la plata ya entro.
--
-- 3. ANULAR NO BORRA. Usa el deleted_at que V37 reservo "para que esa etapa no tenga que
--    migrar una tabla con datos fiscales", mas actor y motivo. Las imputaciones devuelven su
--    saldo a la obligacion y los movimientos de caja se REVIERTEN (filas nuevas, V54).
--
-- 4. EL REINTEGRO SI ES UNA TABLA, append-only: devuelve saldo a favor en dinero, puede ser
--    parcial, por otro medio y repetirse. Su movimiento de caja es un EGRESO con
--    tipo_origen = 'REINTEGRO' y no una reversion, porque no compensa un movimiento entero.
--
-- Nombres de constraint con la tabla adelante: en MySQL son unicos POR ESQUEMA (V64).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- cobro — saldo a favor y anulacion. Puntos 1 y 3.
-- -------------------------------------------------------------------------------------
ALTER TABLE cobro
    ADD COLUMN saldo_a_favor DECIMAL(12, 2) NOT NULL DEFAULT 0.00
        COMMENT 'Lo recibido que todavia no se imputo ni se reintegro. Anticipo cuando es > 0.'
        AFTER moneda,
    ADD COLUMN anulado_por_cuenta_id BIGINT NULL
        COMMENT 'Quien anulo. La fecha es deleted_at (V37 la reservo para esto).'
        AFTER deleted_at,
    ADD COLUMN motivo_anulacion VARCHAR(280) NULL
        AFTER anulado_por_cuenta_id;

-- Los cobros existentes imputaron todo su total (V37 lo exigia): su saldo a favor es cero,
-- que es el DEFAULT. No hace falta backfill.

ALTER TABLE cobro
    ADD CONSTRAINT ck_cobro_saldo_a_favor
        CHECK (saldo_a_favor >= 0 AND saldo_a_favor <= total),

    -- Anulado tiene instante, actor y motivo: los tres o ninguno. Mismo par que
    -- ck_obligacion_anulacion_completa (V36).
    ADD CONSTRAINT ck_cobro_anulacion_completa
        CHECK ((deleted_at IS NULL AND anulado_por_cuenta_id IS NULL AND motivo_anulacion IS NULL)
            OR (deleted_at IS NOT NULL AND anulado_por_cuenta_id IS NOT NULL
                AND motivo_anulacion IS NOT NULL)),

    -- Un cobro anulado no tiene plata disponible: imputarla o reintegrarla seria usar dinero
    -- que la anulacion ya devolvio.
    ADD CONSTRAINT ck_cobro_anulado_sin_saldo
        CHECK (deleted_at IS NULL OR saldo_a_favor = 0);


-- -------------------------------------------------------------------------------------
-- cobro_imputacion — imputacion posterior. Punto 2.
-- -------------------------------------------------------------------------------------
ALTER TABLE cobro_imputacion
    ADD COLUMN imputada_en DATETIME(6) NULL AFTER importe,
    ADD COLUMN imputada_por_cuenta_id BIGINT NULL AFTER imputada_en,
    ADD COLUMN idempotency_key VARCHAR(80) NULL AFTER imputada_por_cuenta_id,
    ADD COLUMN request_hash CHAR(64) NULL AFTER idempotency_key;

-- Las imputaciones existentes nacieron con su cobro: su instante y su actor son los del cobro.
UPDATE cobro_imputacion i
    JOIN cobro c ON c.id = i.cobro_id
   SET i.imputada_en = c.cobrado_en,
       i.imputada_por_cuenta_id = c.cobrado_por_cuenta_id
 WHERE i.imputada_en IS NULL;

ALTER TABLE cobro_imputacion
    MODIFY COLUMN imputada_en DATETIME(6) NOT NULL,
    MODIFY COLUMN imputada_por_cuenta_id BIGINT NOT NULL,

    -- Un reintento de la imputacion posterior no imputa dos veces. Las imputaciones que nacen
    -- con el cobro tienen la clave en NULL —las cubre la del cobro— y varios NULL no colisionan.
    ADD CONSTRAINT uk_cobro_imputacion_idempotencia
        UNIQUE (organization_id, idempotency_key),

    ADD CONSTRAINT ck_cobro_imputacion_idempotencia_completa
        CHECK ((idempotency_key IS NULL AND request_hash IS NULL)
            OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL));


-- -------------------------------------------------------------------------------------
-- cobro_reintegro — devolucion en dinero del saldo a favor. Punto 4.
-- -------------------------------------------------------------------------------------
CREATE TABLE cobro_reintegro
(
    id                        BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id           BIGINT         NOT NULL,
    consultorio_id            BIGINT         NOT NULL COMMENT 'La sede del cobro: la plata sale de su caja.',
    cobro_id                  BIGINT         NOT NULL,
    persona_id                BIGINT         NOT NULL COMMENT 'A quien se le devuelve. Es la del cobro.',

    importe                   DECIMAL(12, 2) NOT NULL,
    moneda                    CHAR(3)        NOT NULL,
    medio                     VARCHAR(24)    NOT NULL COMMENT 'Mismo catalogo que cobro_medio. Solo EFECTIVO afecta el arqueo.',
    referencia                VARCHAR(120)   NULL,
    motivo                    VARCHAR(280)   NOT NULL COMMENT 'Plata que sale sin explicacion es lo que una auditoria busca.',

    reintegrado_en            DATETIME(6)    NOT NULL,
    reintegrado_por_cuenta_id BIGINT         NOT NULL,

    idempotency_key           VARCHAR(80)    NULL,
    request_hash              CHAR(64)       NULL,

    created_at                DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- Un doble click no devuelve la plata dos veces.
    CONSTRAINT uk_cobro_reintegro_idempotencia
        UNIQUE (organization_id, idempotency_key),

    CONSTRAINT fk_cobro_reintegro_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_cobro_reintegro_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_cobro_reintegro_cobro
        FOREIGN KEY (cobro_id) REFERENCES cobro (id),

    CONSTRAINT fk_cobro_reintegro_persona
        FOREIGN KEY (persona_id) REFERENCES persona (id),

    CONSTRAINT ck_cobro_reintegro_importe_positivo
        CHECK (importe > 0),

    CONSTRAINT ck_cobro_reintegro_medio
        CHECK (medio IN ('EFECTIVO', 'TRANSFERENCIA', 'TARJETA_DEBITO', 'TARJETA_CREDITO', 'OTRO')),

    CONSTRAINT ck_cobro_reintegro_idempotencia_completa
        CHECK ((idempotency_key IS NULL AND request_hash IS NULL)
            OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Devolucion en dinero de un saldo a favor. Append-only: no se edita ni se borra.';

CREATE INDEX ix_cobro_reintegro_cobro ON cobro_reintegro (organization_id, cobro_id);


-- -------------------------------------------------------------------------------------
-- movimiento_caja — el reintegro sale de la caja con su propio origen. Punto 4.
--
-- Mismo patron que V56 y V57. NO se reusa REVERSION: una reversion compensa un movimiento
-- entero y exige movimiento_origen_id (ck_movimiento_caja_reversion_completa); un reintegro
-- es parcial, puede ir por otro medio y repetirse. Y no se reusa MANUAL: el unique
-- (organization_id, tipo, tipo_origen, referencia_origen, medio) con referencia_origen =
-- cobro_reintegro.id es lo que hace que un reintegro produzca a lo sumo un movimiento.
-- -------------------------------------------------------------------------------------
ALTER TABLE movimiento_caja
    DROP CHECK ck_movimiento_caja_tipo_origen;

ALTER TABLE movimiento_caja
    ADD CONSTRAINT ck_movimiento_caja_tipo_origen
        CHECK (tipo_origen IN ('COBRO', 'MANUAL', 'REVERSION', 'PAGO_FINANCIADOR', 'PAGO_EGRESO',
                               'REINTEGRO'));
