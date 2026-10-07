-- =====================================================================================
-- AKINE E-4 — Recepcion con maquina de estados propia (M13, DP-16)
--
-- DP-05 pedia tres maquinas independientes —Turno, Check-in/Recepcion y Sesion— y 05.04
-- "reducida" (V39) resolvio el check-in como un estado del turno: EN_ESPERA, con la hora de
-- llegada en la fila de `turno`. DP-16 (07/10/2026) lo corrige:
--
-- 1. NACE LA RECEPCION. Estados propios —LLEGO, VALIDADA u OBSERVADA, EN_ESPERA, LLAMADA, y
--    los dos terminales ANULADA (check-in por error) y CERRADA (el turno se cancelo con la
--    persona presente)— y un historial append-only en `recepcion_evento`.
--
-- 2. EL TURNO VUELVE A SER SOLO LA RESERVA. EN_ESPERA sale de sus estados. Los turnos que
--    estaban en EN_ESPERA vuelven al estado del que vinieron y su llegada pasa a `recepcion`.
--
-- 3. NO SE BORRA NINGUNA COLUMNA (ADR-0007: nunca DROP en la misma release que introduce el
--    reemplazo). `turno.llegada_en`, `llegada_por_cuenta_id` y `estado_antes_de_espera`
--    conservan su valor historico y el codigo deja de mapearlas. Contraerlas es otra
--    migracion. Los eventos LLEGADA / LLEGADA_DESHECHA de `turno_evento` tampoco se tocan:
--    esa tabla es append-only.
--
-- 4. LOS TURNOS EN ESPERA DE DIAS PASADOS SE MIGRAN IGUAL, A EN_ESPERA. El dato dice que la
--    persona llego y quedo esperando, y eso es lo que se conserva: inventar si la atendieron
--    seria peor que no saberlo. Como su recepcion queda abierta, el turno no se puede marcar
--    ausente ni cancelar, que es exactamente lo que admitia como EN_ESPERA.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- recepcion
-- -------------------------------------------------------------------------------------
CREATE TABLE recepcion
(
    id                       BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id          BIGINT       NOT NULL,
    consultorio_id           BIGINT       NOT NULL,
    turno_id                 BIGINT       NOT NULL,

    estado                   VARCHAR(16)  NOT NULL,

    llegada_en               DATETIME(6)  NOT NULL COMMENT 'Hora real de llegada, puesta por el servidor.',
    llegada_por_cuenta_id    BIGINT       NOT NULL COMMENT 'Quien registro la llegada: siempre hay un responsable.',

    -- Snapshot administrativo preliminar (RF-M13-003/004). NULL mientras no se valido.
    modalidad                VARCHAR(16)  NULL COMMENT 'COBERTURA o PARTICULAR. NULL = sin resolver.',
    practica_id              BIGINT       NULL COMMENT 'Practica con la que se valido (principal de la oferta, DP-11).',
    cobertura_id             BIGINT       NULL COMMENT 'Cobertura del paciente con la que se valido.',
    convenio_id              BIGINT       NULL COMMENT 'Convenio vigente que fijo los requisitos.',
    observacion              VARCHAR(1000) NULL COMMENT 'Lo que la validacion observo. Advierte, no bloquea (RN-M13-003).',
    motivo_particular        VARCHAR(300) NULL COMMENT 'Por que el operador decidio atender como Particular (RF-M13-005).',
    validada_en              DATETIME(6)  NULL,
    validada_por_cuenta_id   BIGINT       NULL,

    en_espera_desde          DATETIME(6)  NULL,
    llamada_en               DATETIME(6)  NULL,
    llamada_por_cuenta_id    BIGINT       NULL,

    cerrada_en               DATETIME(6)  NULL COMMENT 'Hora de ANULADA o CERRADA.',
    cerrada_por_cuenta_id    BIGINT       NULL,
    motivo_cierre            VARCHAR(300) NULL,

    -- Una sola recepcion VIGENTE por turno. Las anuladas quedan en NULL y no colisionan entre
    -- si: varios NULL no violan un UNIQUE en MySQL. Mismo patron que `principal_key` de V75.
    turno_vigente_id         BIGINT AS (CASE WHEN estado <> 'ANULADA' THEN turno_id END) STORED,

    created_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                  BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_recepcion_turno_vigente
        UNIQUE (organization_id, turno_vigente_id),

    CONSTRAINT fk_recepcion_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_recepcion_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_recepcion_turno
        FOREIGN KEY (turno_id) REFERENCES turno (id),

    CONSTRAINT ck_recepcion_estado
        CHECK (estado IN ('LLEGO', 'VALIDADA', 'OBSERVADA', 'EN_ESPERA', 'LLAMADA',
                          'ANULADA', 'CERRADA')),

    CONSTRAINT ck_recepcion_modalidad
        CHECK (modalidad IS NULL OR modalidad IN ('COBERTURA', 'PARTICULAR')),

    -- Validada es "se sabe como se atiende": con cobertura o como particular.
    CONSTRAINT ck_recepcion_validada_con_modalidad
        CHECK (estado <> 'VALIDADA' OR modalidad IS NOT NULL),

    -- Observada sin decir que se observo no le sirve a nadie en el mostrador.
    CONSTRAINT ck_recepcion_observada_con_observacion
        CHECK (estado <> 'OBSERVADA' OR observacion IS NOT NULL),

    -- RF-M13-005: Particular es una decision explicita y con motivo.
    CONSTRAINT ck_recepcion_particular_con_motivo
        CHECK (modalidad IS NULL OR modalidad <> 'PARTICULAR' OR motivo_particular IS NOT NULL),

    CONSTRAINT ck_recepcion_cierre
        CHECK ((estado IN ('ANULADA', 'CERRADA')) = (cerrada_en IS NOT NULL)),

    CONSTRAINT ck_recepcion_llamada
        CHECK (estado <> 'LLAMADA' OR llamada_en IS NOT NULL)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Recepcion de un turno (M13, DP-16): llegada, validacion administrativa, espera y llamado. No prueba que una prestacion ocurrio (DP-05).';

-- La agenda del dia trae la recepcion de cada turno de un lote.
CREATE INDEX ix_recepcion_turno ON recepcion (organization_id, turno_id);

-- -------------------------------------------------------------------------------------
-- recepcion_evento — append-only
-- -------------------------------------------------------------------------------------
CREATE TABLE recepcion_evento
(
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id  BIGINT       NOT NULL,
    consultorio_id   BIGINT       NOT NULL,
    recepcion_id     BIGINT       NOT NULL,
    turno_id         BIGINT       NOT NULL,

    tipo             VARCHAR(24)  NOT NULL,
    estado_anterior  VARCHAR(16)  NULL COMMENT 'NULL solo en la LLEGADA: antes no habia recepcion.',
    estado_nuevo     VARCHAR(16)  NOT NULL,
    motivo           VARCHAR(1000) NULL,
    actor_cuenta_id  BIGINT       NULL COMMENT 'NULL solo si lo hizo el sistema.',
    ocurrido_en      DATETIME(6)  NOT NULL,

    CONSTRAINT fk_recepcion_evento_recepcion
        FOREIGN KEY (recepcion_id) REFERENCES recepcion (id),

    CONSTRAINT fk_recepcion_evento_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT ck_recepcion_evento_tipo
        CHECK (tipo IN ('LLEGADA', 'VALIDACION', 'PARTICULAR', 'ESPERA', 'LLAMADO',
                        'ANULACION', 'CIERRE_POR_CANCELACION'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Historial append-only de la recepcion: actor, hora, estado anterior, estado nuevo y motivo (DP-05).';

CREATE INDEX ix_recepcion_evento_turno ON recepcion_evento (organization_id, turno_id, ocurrido_en);

-- -------------------------------------------------------------------------------------
-- Backfill: toda llegada registrada en `turno` pasa a una recepcion
-- -------------------------------------------------------------------------------------
-- EN_ESPERA -> recepcion EN_ESPERA. CANCELADO con llegada (se cancelo con la persona en la
-- sala, lo que 05.04 permitia) -> recepcion CERRADA, conservando la llegada. No hay otro
-- estado posible con llegada: ausencia y reprogramacion estaban vedadas desde EN_ESPERA, y
-- deshacer el check-in limpiaba la hora.
INSERT INTO recepcion (organization_id, consultorio_id, turno_id, estado,
                       llegada_en, llegada_por_cuenta_id, en_espera_desde,
                       cerrada_en, cerrada_por_cuenta_id, motivo_cierre, version)
SELECT t.organization_id,
       t.consultorio_id,
       t.id,
       CASE WHEN t.estado = 'CANCELADO' THEN 'CERRADA' ELSE 'EN_ESPERA' END,
       t.llegada_en,
       t.llegada_por_cuenta_id,
       t.llegada_en,
       CASE WHEN t.estado = 'CANCELADO' THEN COALESCE(t.cancelado_en, t.llegada_en) END,
       CASE WHEN t.estado = 'CANCELADO' THEN t.cancelado_por_cuenta_id END,
       CASE WHEN t.estado = 'CANCELADO' THEN t.motivo_cancelacion END,
       0
  FROM turno t
 WHERE t.llegada_en IS NOT NULL;

-- El evento de llegada, en la hora REAL de llegada y con su responsable.
INSERT INTO recepcion_evento (organization_id, consultorio_id, recepcion_id, turno_id, tipo,
                              estado_anterior, estado_nuevo, motivo, actor_cuenta_id, ocurrido_en)
SELECT r.organization_id, r.consultorio_id, r.id, r.turno_id, 'LLEGADA',
       NULL, 'EN_ESPERA',
       'Migrada por V78 desde el turno EN_ESPERA: la validacion administrativa no existia.',
       r.llegada_por_cuenta_id, r.llegada_en
  FROM recepcion r;

-- Y el cierre de las que se cancelaron con la persona presente.
INSERT INTO recepcion_evento (organization_id, consultorio_id, recepcion_id, turno_id, tipo,
                              estado_anterior, estado_nuevo, motivo, actor_cuenta_id, ocurrido_en)
SELECT r.organization_id, r.consultorio_id, r.id, r.turno_id, 'CIERRE_POR_CANCELACION',
       'EN_ESPERA', 'CERRADA', r.motivo_cierre, r.cerrada_por_cuenta_id, r.cerrada_en
  FROM recepcion r
 WHERE r.estado = 'CERRADA';

-- El turno vuelve al estado de la reserva del que vino. `estado_antes_de_espera` existe
-- justamente para esto (V39, punto 3); RESERVADO si faltara, que es con el que nace todo turno.
-- La version avanza: el estado que un cliente tenia leido ya no es el de la fila.
UPDATE turno
   SET estado  = COALESCE(estado_antes_de_espera, 'RESERVADO'),
       version = version + 1
 WHERE estado = 'EN_ESPERA';

-- -------------------------------------------------------------------------------------
-- turno — el CHECK de estado que nunca tuvo
-- -------------------------------------------------------------------------------------
-- La invariante "en espera => hay llegada" ya no tiene objeto: no hay espera en el turno.
ALTER TABLE turno DROP CHECK ck_turno_espera_tiene_llegada;

ALTER TABLE turno
    ADD CONSTRAINT ck_turno_estado
        CHECK (estado IN ('RESERVADO', 'CONFIRMADO', 'CANCELADO', 'AUSENTE'));
