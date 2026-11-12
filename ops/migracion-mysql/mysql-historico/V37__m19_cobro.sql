-- =====================================================================================
-- AKINE-07.02 — Cobros, medios, imputaciones y comprobantes (M19).
--
-- RF-M19-001..007; RN-M19-001..004.
--
-- ---------------------------------------------------------------------------------------
-- RECABLEADO DE DP-10: QUE ENTRA Y QUE NO
--
-- La etapa pide ademas anticipos —dinero recibido antes de que exista la deuda— y
-- anulacion con reintegro. Los dos quedan FUERA, y no por tamano sino porque sin Caja
-- son medias funcionalidades:
--
--   * DP-06 dice que el anticipo se registra "mediante un ledger trazable y MOVIMIENTO
--     REAL DE CAJA". La Caja es AKINE-07.03 y el Paquete B la dejo afuera. Un anticipo sin
--     caja es plata que entro y que ningun arqueo puede encontrar.
--   * Un reintegro SACA dinero de la caja. Sin caja no hay de donde sacarlo, y registrar la
--     devolucion sin el movimiento dejaria el saldo del dia mintiendo.
--
-- Lo que SI entra es todo el circuito que la demo necesita: recibir dinero por uno o
-- varios medios, imputarlo a deudas existentes y emitir el comprobante.
--
-- ---------------------------------------------------------------------------------------
-- LAS CINCO COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. TRES TABLAS Y NO UNA. El cobro es el hecho, los medios son COMO entro la plata y las
--    imputaciones son CONTRA QUE se aplica. Aplanarlas obligaria a una fila por
--    combinacion de medio e imputacion —pagar dos deudas con efectivo y tarjeta serian
--    cuatro filas— y ninguna de las dos sumas se podria verificar.
--
-- 2. LAS DOS SUMAS SON INVARIANTES Y LAS VERIFICA LA APLICACION, NO LA BASE. `suma(medios)
--    = total` y `suma(imputaciones) = total`. MySQL no admite subconsultas en un CHECK, asi
--    que no hay forma de expresarlas en el esquema; se validan al confirmar, dentro de la
--    misma transaccion. Quien toque este archivo buscando "el CHECK que falta" tiene que
--    leer esto antes.
--
-- 3. EL SALDO DE LA OBLIGACION SE DESCUENTA CON UN UPDATE CONDICIONAL, NO CON UN LOCK.
--    `UPDATE obligacion SET saldo = saldo - :importe WHERE id = ? AND saldo >= :importe`.
--    Si afecta cero filas, otro cobro se llevo la plata primero. Es atomico, no necesita
--    leer antes —que es donde se cuela la ventana entre lectura y escritura— y no puede
--    dejar el saldo en negativo aunque dos cobros lleguen juntos.
--
-- 4. EL COMPROBANTE ES CORRELATIVO POR SEDE Y SE ASIGNA UNA SOLA VEZ. Mismo numerador que
--    las sesiones en V35, y por la misma razon: `SELECT MAX + 1` deja una ventana y dos
--    cobros concurrentes se llevan el mismo numero. Un comprobante repetido es un problema
--    fiscal, no un detalle.
--
-- 5. UN COBRO CONFIRMADO NO SE EDITA. RN-M19: es un hecho economico consumado con
--    comprobante emitido. Corregirlo es anular y volver a cobrar, y anular es 07.03 porque
--    necesita el reintegro. Hasta entonces esto es fail-closed a proposito.
-- =====================================================================================

CREATE TABLE cobro
(
    id                    BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id       BIGINT         NOT NULL,
    consultorio_id        BIGINT         NOT NULL COMMENT 'Donde se cobro. El comprobante es correlativo por sede.',
    persona_id            BIGINT         NOT NULL COMMENT 'Quien pago.',

    total                 DECIMAL(12, 2) NOT NULL,
    moneda                CHAR(3)        NOT NULL,

    comprobante_numero    INT            NOT NULL COMMENT 'Correlativo por sede. Ver el punto 4.',

    cobrado_en            DATETIME(6)    NOT NULL,
    cobrado_por_cuenta_id BIGINT         NOT NULL,

    idempotency_key       VARCHAR(80)    NULL,
    request_hash          CHAR(64)       NULL COMMENT 'SHA-256 del pedido, igual que en turno (V30).',

    created_at            DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- Baja LOGICA aunque hoy nadie la use: la anulacion llega en 07.03 con su reintegro.
    -- La columna existe para que esa etapa no tenga que migrar una tabla con datos fiscales.
    deleted_at            DATETIME(6)    NULL,
    deleted_key           DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL,

    version               BIGINT         NOT NULL DEFAULT 0,

    -- Un comprobante por sede, sin repetir. Punto 4.
    CONSTRAINT uk_cobro_comprobante
        UNIQUE (organization_id, consultorio_id, comprobante_numero),

    -- Misma clave, mismo tenant, un solo cobro. Sin esto, un doble click cobra dos veces.
    CONSTRAINT uk_cobro_idempotencia
        UNIQUE (organization_id, idempotency_key),

    CONSTRAINT fk_cobro_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_cobro_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_cobro_persona
        FOREIGN KEY (persona_id) REFERENCES persona (id),

    CONSTRAINT ck_cobro_total_positivo
        CHECK (total > 0),

    CONSTRAINT ck_cobro_idempotencia_completa
        CHECK ((idempotency_key IS NULL AND request_hash IS NULL)
            OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Dinero recibido. No es la deuda (M18) ni la caja (M20).';


-- -------------------------------------------------------------------------------------
-- cobro_medio — COMO entro la plata. Punto 1.
-- -------------------------------------------------------------------------------------
CREATE TABLE cobro_medio
(
    id         BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,

    -- Desnormalizado igual que en `oferta_profesional_habilitado`: ADR-0004 exige
    -- organization_id en TODA tabla de negocio y una tabla hija no es una excepcion. Se
    -- podria llegar al tenant por cobro_id, y precisamente por eso la regla existe: una
    -- consulta que se olvide del JOIN cruzaria tenants sin fallar.
    organization_id BIGINT      NOT NULL,

    cobro_id   BIGINT         NOT NULL,
    medio      VARCHAR(24)    NOT NULL,
    importe    DECIMAL(12, 2) NOT NULL,

    -- Numero de operacion, ultimos digitos de la tarjeta, lo que el operador quiera anotar.
    -- Texto libre: cada medio tiene su propia referencia y darle estructura obligaria a una
    -- tabla por medio de pago.
    referencia VARCHAR(120)   NULL,

    created_at DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_cobro_medio_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_cobro_medio_cobro
        FOREIGN KEY (cobro_id) REFERENCES cobro (id),

    CONSTRAINT ck_cobro_medio_importe_positivo
        CHECK (importe > 0),

    CONSTRAINT ck_cobro_medio_tipo
        CHECK (medio IN ('EFECTIVO', 'TRANSFERENCIA', 'TARJETA_DEBITO', 'TARJETA_CREDITO', 'OTRO'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Un cobro puede entrar por varios medios. suma(importe) = cobro.total, verificado en aplicacion.';

CREATE INDEX ix_cobro_medio_cobro ON cobro_medio (cobro_id);


-- -------------------------------------------------------------------------------------
-- cobro_imputacion — CONTRA QUE se aplica. Punto 1.
-- -------------------------------------------------------------------------------------
CREATE TABLE cobro_imputacion
(
    id            BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,

    -- Ver cobro_medio: ADR-0004 no exceptua a las tablas hijas.
    organization_id BIGINT      NOT NULL,

    cobro_id      BIGINT         NOT NULL,
    obligacion_id BIGINT         NOT NULL,
    importe       DECIMAL(12, 2) NOT NULL,

    created_at    DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- Un cobro no imputa dos veces a la misma deuda: seria una fila que no agrega nada y
    -- que hace que la suma sea mas dificil de leer que de calcular.
    CONSTRAINT uk_cobro_imputacion
        UNIQUE (cobro_id, obligacion_id),

    CONSTRAINT fk_cobro_imputacion_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_cobro_imputacion_cobro
        FOREIGN KEY (cobro_id) REFERENCES cobro (id),

    CONSTRAINT fk_cobro_imputacion_obligacion
        FOREIGN KEY (obligacion_id) REFERENCES obligacion (id),

    CONSTRAINT ck_cobro_imputacion_importe_positivo
        CHECK (importe > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Que parte del cobro paga que deuda. suma(importe) = cobro.total mientras no existan anticipos.';

CREATE INDEX ix_cobro_imputacion_obligacion ON cobro_imputacion (obligacion_id);


-- -------------------------------------------------------------------------------------
-- comprobante_numerador — correlativo por sede. Mismo patron que V35.
-- -------------------------------------------------------------------------------------
CREATE TABLE comprobante_numerador
(
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id BIGINT      NOT NULL,
    consultorio_id  BIGINT      NOT NULL,

    ultimo_numero   INT         NOT NULL DEFAULT 0
        COMMENT 'Se incrementa con UPDATE, nunca con SELECT MAX + 1: ver el punto 4.',

    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_comprobante_numerador
        UNIQUE (organization_id, consultorio_id),

    CONSTRAINT fk_comprobante_numerador_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_comprobante_numerador_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Secuencia de comprobantes por sede. Un numero repetido es un problema fiscal.';


-- Los cobros de un paciente, para su cuenta corriente.
CREATE INDEX ix_cobro_persona ON cobro (organization_id, persona_id, cobrado_en);

-- Los cobros del dia de una sede: es lo que AKINE-07.03 va a arquear.
CREATE INDEX ix_cobro_sede_fecha ON cobro (organization_id, consultorio_id, cobrado_en);
