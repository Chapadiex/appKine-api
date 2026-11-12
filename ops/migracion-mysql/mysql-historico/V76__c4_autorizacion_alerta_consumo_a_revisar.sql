-- =====================================================================================
-- AKINE C-4 — Alerta "consumo a revisar" sobre una autorizacion (DP-13, RN-M17-003).
--
-- Trazabilidad: DP-13 (la reversion del consumo sigue manual; al anular la obligacion de
-- una sesion que consumio autorizacion se alerta sobre esa autorizacion, sin tocar el
-- saldo), RN-M17-003 (faltantes generan alertas), RF-M17-005 (revertir el consumo, que es
-- lo que resuelve la alerta). Diseno: docs/diseno/AKINE-C-4-consumo.md.
--
-- POR QUE UNA TABLA. La alerta nace de un HECHO de otro modulo —billing anula una deuda—
-- y no se puede recalcular al leer: el ledger no sabe nada de obligaciones, y una vez
-- anulada la deuda nada en `person` recuerda que hubo que mirar ese consumo. Las alertas de
-- RF-M17-006 (vencimiento, agote) SI se calculan al leer y por eso no viven aca.
--
-- UNA ALERTA POR CONSUMO, NO POR OBLIGACION. El unique es por el movimiento CONSUMO que
-- hay que revisar. F-4 va a devengar dos obligaciones por sesion (financiador y paciente):
-- anular las dos sigue siendo UN consumo a revisar. El servicio inserta con
-- INSERT ... ON DUPLICATE KEY UPDATE, asi que dos anulaciones concurrentes no chocan.
--
-- NO TOCA EL SALDO. Anular la deuda no prueba que la prestacion no ocurrio (DP-13): devolver
-- la unidad seria deshacer un hecho clinico por un motivo economico. Por eso no hay FK ni
-- columna que la ate a `cantidad_consumida`.
--
-- SE RESUELVE, NO SE BORRA. `resuelta_en` + `resolucion`. Hoy el unico camino es revertir
-- el consumo (RF-M17-005), que la marca REVERTIDO en la misma transaccion. Descartarla sin
-- revertir no tiene RF y queda como decision del usuario.
--
-- Sin FK a `sesion` ni a `obligacion`: son tablas de otros modulos y el ledger de V50 ya
-- guarda la sesion como referencia sin FK, por el mismo motivo (ADR-0001, ownership).
-- Constraints con la tabla adelante: en MySQL los nombres de FK y CHECK son unicos POR
-- ESQUEMA (leccion del 29/09, V64 contra V50).
-- =====================================================================================

CREATE TABLE autorizacion_alerta
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id   BIGINT       NOT NULL COMMENT 'Tenant propietario. Encabeza el unique y los indices (ADR-0004)',
    autorizacion_id   BIGINT       NOT NULL COMMENT 'Autorizacion sobre la que se alerta',
    persona_id        BIGINT       NOT NULL COMMENT 'Paciente. Redundante con la autorizacion: permite listar las alertas de un paciente sin pasar por cada autorizacion',
    movimiento_id     BIGINT       NOT NULL COMMENT 'El CONSUMO del ledger que hay que revisar. Mitad del unique: una alerta por consumo',

    tipo              VARCHAR(24)  NOT NULL COMMENT 'CONSUMO_A_REVISAR. Unico tipo hoy (DP-13)',

    sesion_id         BIGINT       NOT NULL COMMENT 'Sesion que consumio. Referencia sin FK: la tabla es de encounter',
    obligacion_id     BIGINT       NOT NULL COMMENT 'Primera obligacion cuya anulacion genero la alerta. Referencia sin FK: la tabla es de billing',
    motivo_origen     VARCHAR(280) NULL COMMENT 'Motivo con que se anulo la obligacion, copiado para que quien revise sepa por que',

    generada_en       DATETIME(6)  NOT NULL COMMENT 'Instante UTC de la anulacion que la genero',
    generada_por      BIGINT       NULL COMMENT 'Cuenta que anulo la obligacion',

    resuelta_en       DATETIME(6)  NULL COMMENT 'Instante UTC en que se resolvio. NULL = pendiente',
    resuelta_por      BIGINT       NULL COMMENT 'Cuenta que la resolvio',
    resolucion        VARCHAR(24)  NULL COMMENT 'REVERTIDO: se revirtio el consumo (RF-M17-005). NULL mientras este pendiente',

    created_at        DATETIME(6)  NOT NULL COMMENT 'Marca de insercion',

    CONSTRAINT pk_autorizacion_alerta PRIMARY KEY (id),

    CONSTRAINT uk_autorizacion_alerta_movimiento
        UNIQUE (organization_id, movimiento_id, tipo),

    CONSTRAINT fk_autorizacion_alerta_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_autorizacion_alerta_autorizacion FOREIGN KEY (autorizacion_id) REFERENCES autorizacion (id),
    CONSTRAINT fk_autorizacion_alerta_persona      FOREIGN KEY (persona_id) REFERENCES persona (id),
    CONSTRAINT fk_autorizacion_alerta_movimiento   FOREIGN KEY (movimiento_id) REFERENCES autorizacion_movimiento (id),

    CONSTRAINT ck_autorizacion_alerta_tipo
        CHECK (tipo IN ('CONSUMO_A_REVISAR')),

    CONSTRAINT ck_autorizacion_alerta_resolucion
        CHECK (resolucion IS NULL OR resolucion IN ('REVERTIDO')),

    -- Resuelta es las dos cosas juntas o ninguna: una alerta con fecha de resolucion y sin
    -- resolucion no dice que paso.
    CONSTRAINT ck_autorizacion_alerta_resuelta_coherente
        CHECK ((resuelta_en IS NULL AND resolucion IS NULL)
            OR (resuelta_en IS NOT NULL AND resolucion IS NOT NULL)),

    INDEX ix_autorizacion_alerta_autorizacion (organization_id, autorizacion_id, generada_en),
    INDEX ix_autorizacion_alerta_persona (organization_id, persona_id, generada_en)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Alertas sobre autorizaciones que nacen de hechos de otros modulos (DP-13, RN-M17-003): hoy, el consumo de una sesion cuya obligacion se anulo. No mueve el saldo. Se resuelve, no se borra. Propietario: modulo person';

-- Lo que una sesion dejo en el ledger, en TODAS las autorizaciones del paciente. Lo preguntan
-- el re-disparo del cierre (DP-12: no consumir otra autorizacion por la misma practica) y la
-- anulacion de la deuda (DP-13: que consumos alertar). El unique de V50 tiene estas columnas
-- detras de autorizacion_id y no sirve para esta consulta.
CREATE INDEX ix_movimiento_origen
    ON autorizacion_movimiento (organization_id, tipo_origen, referencia_origen);
