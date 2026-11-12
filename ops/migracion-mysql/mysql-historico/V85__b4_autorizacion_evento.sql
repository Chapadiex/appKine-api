-- =====================================================================================
-- AKINE B-4 — Historial de estados de una autorizacion (DP-23, M17).
--
-- Trazabilidad: DP-23 (resuelve DU-8: el historial vive en una tabla propia, append-only,
-- como `turno_evento` y `recepcion_evento`; NO se arma leyendo `audit_event`), RF-M17-001
-- (estado de la autorizacion), RF-M17-004/005 (consumo y reversion), RNF-M17-002 (proteger
-- relaciones historicas). Diseno: docs/diseno/AKINE-B-4-historial-autorizacion.md.
--
-- POR QUE UNA TABLA Y NO LA AUDITORIA. `audit_event` es de `organization`, se lee con
-- `auditoria:read` —que el mostrador no tiene— y mezcla eventos de todas las entidades. El
-- historial es un dato del negocio: lo lee quien lee la autorizacion, con el mismo permiso.
--
-- APPEND-ONLY. Ninguna columna se actualiza y ningun camino del codigo borra una fila. La
-- autorizacion guarda el estado actual; esto es lo unico que dice como se llego ahi.
--
-- SE ESCRIBE EN LA TRANSACCION DE LA MUTACION. Alta, resolucion, edicion, documento, baja,
-- consumo y reversion insertan su fila en la misma transaccion que mueve la autorizacion o
-- el ledger: si la mutacion hace rollback, el evento tambien.
--
-- EL VENCIMIENTO NO ES UN EVENTO. Es funcion del reloj (EstadoAutorizacion: VENCIDA se
-- calcula, no se guarda). Ninguna lectura escribe y no hay job: el historial lo informa
-- calculado en la respuesta, no como fila.
--
-- UN EVENTO POR MOVIMIENTO DEL LEDGER. `uk_autorizacion_evento_movimiento` hace que un
-- consumo o una reversion no pueda dejar dos eventos aunque dos transacciones lo intenten;
-- las filas sin movimiento llevan NULL, y varios NULL no colisionan en MySQL.
--
-- Constraints con la tabla adelante: en MySQL los nombres de FK y CHECK son unicos POR
-- ESQUEMA (leccion del 29/09, V64 contra V50). Reservada como V68, que quedo por debajo de
-- V83; Flyway corre sin outOfOrder, asi que V68 queda vacia y no se reusa.
-- =====================================================================================

CREATE TABLE autorizacion_evento
(
    id                BIGINT        NOT NULL AUTO_INCREMENT,

    organization_id   BIGINT        NOT NULL COMMENT 'Tenant propietario. Encabeza el unique y los indices (ADR-0004)',
    autorizacion_id   BIGINT        NOT NULL COMMENT 'Autorizacion cuyo historial es',
    persona_id        BIGINT        NOT NULL COMMENT 'Paciente. Redundante con la autorizacion, como en autorizacion_alerta',
    consultorio_id    BIGINT        NULL COMMENT 'Sede desde la que se hizo. NULL si el hecho no la declara',

    tipo              VARCHAR(24)   NOT NULL COMMENT 'Que paso. Ver ck_autorizacion_evento_tipo',
    estado_anterior   VARCHAR(16)   NULL COMMENT 'Estado de la autorizacion antes. NULL solo en el ALTA',
    estado_nuevo      VARCHAR(16)   NOT NULL COMMENT 'Estado de la autorizacion despues',
    activa            TINYINT(1)    NOT NULL COMMENT 'Ciclo de vida despues del hecho: 0 a partir de la ANULACION',

    cantidad          INT           NULL COMMENT 'Unidades consumidas o devueltas. Solo CONSUMO y REVERSION_DE_CONSUMO',
    movimiento_id     BIGINT        NULL COMMENT 'Movimiento del ledger que lo produjo. Solo CONSUMO y REVERSION_DE_CONSUMO',
    detalle           VARCHAR(500)  NULL COMMENT 'Que cambio, en terminos administrativos: campos, cantidades, fechas. Sin datos clinicos',
    motivo            VARCHAR(1000) NULL COMMENT 'Motivo declarado: observar, rechazar, anular, revertir',

    actor_cuenta_id   BIGINT        NULL COMMENT 'Cuenta que lo hizo. NULL si lo hizo el sistema',
    ocurrido_en       DATETIME(6)   NOT NULL COMMENT 'Instante UTC del hecho',

    CONSTRAINT pk_autorizacion_evento PRIMARY KEY (id),

    CONSTRAINT uk_autorizacion_evento_movimiento
        UNIQUE (organization_id, movimiento_id),

    CONSTRAINT fk_autorizacion_evento_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_autorizacion_evento_autorizacion FOREIGN KEY (autorizacion_id) REFERENCES autorizacion (id),
    CONSTRAINT fk_autorizacion_evento_persona      FOREIGN KEY (persona_id) REFERENCES persona (id),
    CONSTRAINT fk_autorizacion_evento_movimiento   FOREIGN KEY (movimiento_id) REFERENCES autorizacion_movimiento (id),

    CONSTRAINT ck_autorizacion_evento_tipo
        CHECK (tipo IN ('ALTA', 'APROBACION', 'OBSERVACION', 'RECHAZO', 'MODIFICACION',
                        'DOCUMENTO', 'CONSUMO', 'REVERSION_DE_CONSUMO', 'ANULACION')),

    CONSTRAINT ck_autorizacion_evento_estado_nuevo
        CHECK (estado_nuevo IN ('PENDIENTE', 'APROBADA', 'OBSERVADA', 'RECHAZADA')),

    CONSTRAINT ck_autorizacion_evento_estado_anterior
        CHECK (estado_anterior IS NULL
            OR estado_anterior IN ('PENDIENTE', 'APROBADA', 'OBSERVADA', 'RECHAZADA')),

    -- Solo el alta no tiene estado anterior.
    CONSTRAINT ck_autorizacion_evento_alta_sin_anterior
        CHECK ((tipo = 'ALTA') = (estado_anterior IS NULL)),

    -- El movimiento y la cantidad van juntos, y solo en los hechos del ledger.
    CONSTRAINT ck_autorizacion_evento_movimiento_coherente
        CHECK ((tipo IN ('CONSUMO', 'REVERSION_DE_CONSUMO')
                    AND movimiento_id IS NOT NULL AND cantidad IS NOT NULL AND cantidad > 0)
            OR (tipo NOT IN ('CONSUMO', 'REVERSION_DE_CONSUMO')
                    AND movimiento_id IS NULL AND cantidad IS NULL)),

    INDEX ix_autorizacion_evento_autorizacion (organization_id, autorizacion_id, ocurrido_en, id)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Historial append-only de una autorizacion (DP-23): alta, resolucion, edicion, documento, consumo, reversion y anulacion, con estado anterior y nuevo, actor y momento. El vencimiento se calcula al leer. Propietario: modulo person';

-- Las autorizaciones que existian antes de esta migracion no tienen historial previo: la
-- auditoria no es fuente de este dato (DP-23). Se siembra UN evento ALTA por autorizacion
-- con el estado ACTUAL, declarado como reconstruido en `detalle`, para que ningun historial
-- quede vacio. Lo anterior a V85 no se inventa.
INSERT INTO autorizacion_evento (organization_id, autorizacion_id, persona_id, consultorio_id,
                                 tipo, estado_anterior, estado_nuevo, activa, detalle,
                                 actor_cuenta_id, ocurrido_en)
SELECT a.organization_id, a.id, a.persona_id, a.consultorio_id,
       'ALTA', NULL, a.estado, a.active,
       'Reconstruido al crear el historial (V85): estado vigente a esa fecha, sin transiciones previas',
       NULL, a.created_at
  FROM autorizacion a;
