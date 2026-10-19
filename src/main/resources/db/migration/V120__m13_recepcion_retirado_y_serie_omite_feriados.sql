-- =====================================================================================
-- AKINE DP-43 — Recepcion RETIRADO y series que omiten feriados (M13, M12)
--
-- Decisiones del usuario del 19/10/2026 (U-21 y U-22, ficha F5 n.º 5 y 6).
--
-- 1. U-21: NUEVA SALIDA DE LA RECEPCION, RETIRADO ("se retiro sin ser atendido"), desde LLEGO o
--    EN_ESPERA, con motivo obligatorio. Es terminal como CERRADA y, como ella, la llegada SI
--    vale: la fila sigue siendo la recepcion vigente del turno (`turno_vigente_id` no cambia) y
--    usa las mismas columnas de cierre (`cerrada_en`, `cerrada_por_cuenta_id`, `motivo_cierre`).
--    El turno no se toca (Recepcion != Turno != Sesion, DP-05).
--
--    Los CHECK de estado y de tipo de evento se REEMPLAZAN, y reemplazar un CHECK no es
--    aditivo: la lista nueva repite TODOS los valores que V78 admitia. Un valor que se caiga aca
--    deja sin poder escribirse a toda fila que lo use, y ningun gate lo ve hasta que alguien
--    intenta esa transicion.
--
-- 2. U-22: EL ALTA DE UNA SERIE OMITE LAS OCURRENCIAS QUE CAEN EN FERIADO en vez de fallar
--    entera. Las omitidas se guardan en `turno_serie_omision`, append-only, para que la serie
--    pueda contar siempre que fechas salto y por que —tambien al releerla o en el reintento
--    idempotente del alta—.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- recepcion
-- -------------------------------------------------------------------------------------
ALTER TABLE recepcion DROP CHECK ck_recepcion_estado;

ALTER TABLE recepcion
    ADD CONSTRAINT ck_recepcion_estado
        CHECK (estado IN ('LLEGO', 'VALIDADA', 'OBSERVADA', 'EN_ESPERA', 'LLAMADA',
                          'ANULADA', 'CERRADA', 'RETIRADO'));

ALTER TABLE recepcion DROP CHECK ck_recepcion_cierre;

ALTER TABLE recepcion
    ADD CONSTRAINT ck_recepcion_cierre
        CHECK ((estado IN ('ANULADA', 'CERRADA', 'RETIRADO')) = (cerrada_en IS NOT NULL));

-- U-21: el motivo es obligatorio. Un "se fue" sin explicacion no le dice nada a quien revisa
-- por que el turno quedo sin atender.
ALTER TABLE recepcion
    ADD CONSTRAINT ck_recepcion_retirado_con_motivo
        CHECK (estado <> 'RETIRADO' OR motivo_cierre IS NOT NULL);

-- -------------------------------------------------------------------------------------
-- recepcion_evento
-- -------------------------------------------------------------------------------------
ALTER TABLE recepcion_evento DROP CHECK ck_recepcion_evento_tipo;

ALTER TABLE recepcion_evento
    ADD CONSTRAINT ck_recepcion_evento_tipo
        CHECK (tipo IN ('LLEGADA', 'VALIDACION', 'PARTICULAR', 'ESPERA', 'LLAMADO',
                        'ANULACION', 'CIERRE_POR_CANCELACION', 'RETIRO'));

-- -------------------------------------------------------------------------------------
-- turno_serie_omision — append-only
-- -------------------------------------------------------------------------------------
CREATE TABLE turno_serie_omision
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id    BIGINT       NOT NULL,
    consultorio_id     BIGINT       NOT NULL,
    serie_id           BIGINT       NOT NULL,

    ocurrencia_inicio  DATETIME(6)  NOT NULL COMMENT 'Instante UTC que la regla producia y no se reservo.',
    fecha              DATE         NOT NULL COMMENT 'La fecha local de esa ocurrencia, en la zona congelada de la serie.',
    motivo             VARCHAR(24)  NOT NULL COMMENT 'Por que se omitio. Hoy solo FERIADO (U-22).',

    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_turno_serie_omision
        UNIQUE (organization_id, serie_id, ocurrencia_inicio),

    CONSTRAINT fk_turno_serie_omision_serie
        FOREIGN KEY (serie_id) REFERENCES turno_serie (id),

    CONSTRAINT fk_turno_serie_omision_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_turno_serie_omision_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT ck_turno_serie_omision_motivo
        CHECK (motivo IN ('FERIADO'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Ocurrencias de una serie que el alta no reservo, con su motivo (U-22). Append-only.';
