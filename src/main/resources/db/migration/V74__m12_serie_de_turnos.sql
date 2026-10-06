-- =====================================================================================
-- AKINE E-3 — Series de turnos (DP-04, ADR-0011)
--
-- 1. turno_serie describe la REGLA con la que se generaron los turnos. No es duena de su ciclo
--    de vida: cada ocurrencia es un turno pleno, con estado, version e historial propios.
-- 2. La serie no tiene estado ni baja: "la serie esta cancelada" se lee en sus turnos. Ninguna
--    operacion la borra ni la cierra, asi que no lleva el cuarteto de baja logica.
-- 3. turno.serie_id es NULLABLE y aditivo: los turnos sueltos siguen siendo lo que eran y no se
--    rellena nada.
-- 4. Ningun unique expresa el solapamiento de una ocurrencia con otra reserva: lo hace cumplir
--    RevalidadorDeSlot bajo el lock de agenda_sede, igual que en 05.02.
-- =====================================================================================

CREATE TABLE turno_serie
(
    id                        BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id           BIGINT       NOT NULL,
    consultorio_id            BIGINT       NOT NULL,
    oferta_id                 BIGINT       NOT NULL,
    persona_id                BIGINT       NOT NULL,
    profesional_membership_id BIGINT       NULL COMMENT 'Nulo si la oferta no requiere profesional.',

    frecuencia                VARCHAR(16)  NOT NULL COMMENT 'Solo SEMANAL. La columna existe para que otra frecuencia sea aditiva.',
    dias_semana               VARCHAR(16)  NOT NULL COMMENT 'Dias ISO separados por coma, 1 = lunes. Ej: 1,4',
    hora_local                TIME         NOT NULL COMMENT 'Hora de inicio en la zona de la sede.',
    fecha_desde               DATE         NOT NULL,
    fecha_hasta               DATE         NULL COMMENT 'Fin por fecha. Excluyente con cantidad.',
    cantidad                  INT          NULL COMMENT 'Fin por cantidad de ocurrencias. Excluyente con fecha_hasta.',
    timezone                  VARCHAR(64)  NOT NULL COMMENT 'Zona de la sede al crear la serie: la regla es en hora local.',
    cantidad_generada         INT          NOT NULL COMMENT 'Turnos creados por el alta (todo o nada).',

    idempotency_key           VARCHAR(80)  NULL,
    request_hash              CHAR(64)     NULL,

    creada_por_cuenta_id      BIGINT       NOT NULL,
    creada_en                 DATETIME(6)  NOT NULL,

    created_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                   BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_turno_serie_idempotencia
        UNIQUE (organization_id, idempotency_key),

    CONSTRAINT fk_turno_serie_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_turno_serie_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_turno_serie_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),

    CONSTRAINT fk_turno_serie_persona
        FOREIGN KEY (persona_id) REFERENCES persona (id),

    CONSTRAINT ck_turno_serie_frecuencia
        CHECK (frecuencia = 'SEMANAL'),

    CONSTRAINT ck_turno_serie_fin_unico
        CHECK ((cantidad IS NULL AND fecha_hasta IS NOT NULL)
            OR (cantidad IS NOT NULL AND fecha_hasta IS NULL)),

    CONSTRAINT ck_turno_serie_cantidad
        CHECK (cantidad IS NULL OR cantidad BETWEEN 1 AND 52),

    CONSTRAINT ck_turno_serie_cantidad_generada
        CHECK (cantidad_generada BETWEEN 1 AND 52),

    CONSTRAINT ck_turno_serie_rango
        CHECK (fecha_hasta IS NULL OR fecha_hasta >= fecha_desde),

    CONSTRAINT ck_turno_serie_idempotencia_completa
        CHECK ((idempotency_key IS NULL AND request_hash IS NULL)
            OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Regla de recurrencia con la que se generaron turnos. Cada turno conserva identidad y estado propios (DP-04).';


ALTER TABLE turno
    ADD COLUMN serie_id BIGINT NULL
        COMMENT 'Serie que genero este turno (E-3). NULL en un turno suelto. No cambia despues del alta.'
        AFTER persona_id;

ALTER TABLE turno
    ADD CONSTRAINT fk_turno_turno_serie
        FOREIGN KEY (serie_id) REFERENCES turno_serie (id);

CREATE INDEX ix_turno_serie_inicio
    ON turno (organization_id, serie_id, inicio);
