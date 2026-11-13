-- =====================================================================================
-- AKINE-07.01 — Obligaciones economicas, snapshots y responsables (M18).
--
-- RF-M18-001..007; RN-M18-001..005.
--
-- ---------------------------------------------------------------------------------------
-- RECABLEADO DE DP-10
--
-- La etapa depende de AKINE-03.05 (convenios y aranceles) y AKINE-04.05 (consumo de
-- autorizaciones), las dos fuera del Paquete B, y el Paquete B ademas fija cobertura
-- PARTICULAR unicamente. En consecuencia:
--
--   * Hay UN solo responsable posible hoy: el paciente. La columna `responsable` existe
--     igual con FINANCIADOR entre sus valores, porque DP-10 manda cortar alcance y no
--     modelo: cuando 03.03 y 03.05 lleguen, se agregan filas, no columnas.
--   * El importe sale del `precio_base` de la Oferta. Las columnas de snapshot de convenio
--     y arancel estan reservadas y en NULL: es donde 03.05 se enchufa.
--   * No hay obligacion mixta —paciente mas financiador por la misma prestacion— porque no
--     hay financiador. El unique de abajo la admite estructuralmente.
--
-- ---------------------------------------------------------------------------------------
-- LAS CINCO COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. DECIMAL, NUNCA FLOAT. `DECIMAL(12,2)` en la base y `BigDecimal` en Java, y la API no
--    expone un solo numero de punto flotante. Un `DOUBLE` de 0.1 + 0.2 no da 0.3, y sobre
--    una cuenta corriente eso son centavos que no cuadran y que nadie puede explicar seis
--    meses despues.
--
-- 2. EL UNIQUE `(sesion_id, responsable)` ES LA IDEMPOTENCIA POR PRESTACION. RN-M18-001:
--    una prestacion genera su deuda una sola vez. Cerrar una sesion dos veces —que es
--    idempotente por RN-M14-005— no puede producir dos deudas. Y "una sesion puede generar
--    varias obligaciones" sigue siendo cierto: una por responsable, que es exactamente lo
--    que este unique permite.
--
-- 3. EL SNAPSHOT SE CONGELA Y NO SE RECALCULA. `snapshot_precio` y `snapshot_nombre` se
--    copian de la Oferta al momento de devengar. Editar el precio de una oferta manana no
--    puede cambiar lo que se le debe a alguien por una sesion de hoy: eso es reescribir una
--    cuenta corriente. Es el mismo criterio que el turno usa con su intervalo.
--
-- 4. EL SALDO SE MATERIALIZA, Y EL CHECK ES LO QUE LO SOSTIENE. Es una derivacion
--    —importe menos lo imputado— pero se guarda, porque calcularlo al leer obligaria a
--    AKINE-07.02 a sumar todos los cobros DENTRO del lock que imputa, y a nadie a
--    garantizar que dos imputaciones concurrentes no dejen el saldo en negativo. Con la
--    columna, la imputacion es un UPDATE condicional y el CHECK impide el estado imposible.
--
-- 5. LA DEUDA NO ES EL COBRO NI ES LA CAJA. Esta tabla no tiene medio de pago, ni
--    comprobante, ni movimiento. RN maestra de M18/M19/M20: son tres cosas distintas y
--    confundirlas es el error del UML de 2019, que acoplaba Turno, Atencion y Cobro. Los
--    cobros llegan en 07.02 y la caja en 07.03, que quedo fuera del Paquete B.
-- =====================================================================================

CREATE TABLE obligacion
(
    id                  BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id     BIGINT         NOT NULL,
    consultorio_id      BIGINT         NOT NULL COMMENT 'Sede donde se presto. La deuda se cobra donde se atendio.',

    -- El origen. Es lo que hace la deuda trazable hasta el hecho clinico que la genero.
    sesion_id           BIGINT         NOT NULL,
    persona_id          BIGINT         NOT NULL COMMENT 'A quien se le presto. Desnormalizado para poder listar la deuda de un paciente sin pasar por sesion.',

    responsable         VARCHAR(16)    NOT NULL
        COMMENT 'PACIENTE o FINANCIADOR. Hoy solo PACIENTE: ver el recableado de DP-10.',

    -- Punto 1.
    importe_original    DECIMAL(12, 2) NOT NULL,
    saldo               DECIMAL(12, 2) NOT NULL COMMENT 'Punto 4: derivado pero materializado.',
    moneda              CHAR(3)        NOT NULL,

    estado              VARCHAR(16)    NOT NULL
        COMMENT 'PENDIENTE, PARCIAL, PAGADA o ANULADA. Se deriva del saldo salvo ANULADA.',

    -- Punto 3: el snapshot congelado.
    oferta_id           BIGINT         NOT NULL,
    snapshot_nombre     VARCHAR(160)   NOT NULL COMMENT 'Nombre comercial de la oferta al devengar.',
    snapshot_precio     DECIMAL(12, 2) NOT NULL COMMENT 'Precio de la oferta al devengar. No se recalcula nunca.',

    -- Reservado para AKINE-03.05. NULL mientras no existan convenios ni aranceles: es donde
    -- se enchufa, y esta declarado para que agregarlo sea sumar datos y no migrar la tabla.
    snapshot_convenio_id BIGINT        NULL COMMENT 'Reservado: AKINE-03.05, fuera del Paquete B.',
    snapshot_arancel_id  BIGINT        NULL COMMENT 'Reservado: AKINE-03.05, fuera del Paquete B.',

    devengada_en        DATETIME(6)    NOT NULL,

    anulada_en          DATETIME(6)    NULL,
    anulada_por_cuenta_id BIGINT       NULL,
    motivo_anulacion    VARCHAR(280)   NULL,

    created_at          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- Baja LOGICA: una obligacion anulada conserva su fila. ADR-0011 y la regla maestra 10:
    -- una deuda que desaparece de la base es una cuenta corriente que no cuadra.
    deleted_at          DATETIME(6)    NULL,
    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL,

    version             BIGINT         NOT NULL DEFAULT 0,

    -- Punto 2.
    CONSTRAINT uk_obligacion_prestacion
        UNIQUE (sesion_id, responsable, deleted_key),

    CONSTRAINT fk_obligacion_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_obligacion_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_obligacion_sesion
        FOREIGN KEY (sesion_id) REFERENCES sesion (id),

    CONSTRAINT fk_obligacion_persona
        FOREIGN KEY (persona_id) REFERENCES persona (id),

    CONSTRAINT fk_obligacion_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),

    CONSTRAINT ck_obligacion_responsable
        CHECK (responsable IN ('PACIENTE', 'FINANCIADOR')),

    CONSTRAINT ck_obligacion_estado
        CHECK (estado IN ('PENDIENTE', 'PARCIAL', 'PAGADA', 'ANULADA')),

    -- Punto 4: el saldo nunca sale del rango. Sin esto, dos imputaciones concurrentes
    -- pueden dejarlo en negativo y la cuenta corriente muestra que el centro le debe plata
    -- al paciente.
    CONSTRAINT ck_obligacion_saldo
        CHECK (saldo >= 0 AND saldo <= importe_original),

    -- No se devengan obligaciones de cero. "No cobrar saldo cero" es una regla de la etapa,
    -- y una deuda de cero solo ensucia la cuenta corriente con filas que nadie va a pagar.
    CONSTRAINT ck_obligacion_importe_positivo
        CHECK (importe_original > 0),

    -- Anulada tiene instante, actor y motivo: los tres o ninguno. Sin esto una fila a medio
    -- anular pasaria por vigente para unas consultas y por anulada para otras.
    CONSTRAINT ck_obligacion_anulacion_completa
        CHECK ((anulada_en IS NULL AND anulada_por_cuenta_id IS NULL AND motivo_anulacion IS NULL)
            OR (anulada_en IS NOT NULL AND anulada_por_cuenta_id IS NOT NULL
                AND motivo_anulacion IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Deuda derivada de una prestacion. No es el cobro (M19) ni la caja (M20).';


-- La cuenta corriente de un paciente: lo que debe, en orden. Es la lectura principal de la
-- etapa y la que AKINE-07.02 usa para saber contra que imputar.
CREATE INDEX ix_obligacion_persona
    ON obligacion (organization_id, persona_id, estado, devengada_en);

-- La deuda pendiente de una sede, para el cierre de caja de 07.03.
CREATE INDEX ix_obligacion_sede_estado
    ON obligacion (organization_id, consultorio_id, estado, devengada_en);
