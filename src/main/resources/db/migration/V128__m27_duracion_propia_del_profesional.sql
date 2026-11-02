-- =====================================================================================
-- Duracion propia de un profesional en una oferta (TURNO - Tecnico §47).
--
-- Kinesiologia dura 45 minutos, pero Carla la hace en 30. Sin fila aca vale la de la oferta; con
-- fila, los horarios y los turnos de ese profesional en esa oferta duran esto.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE TABLA PROPIA Y NO UNA COLUMNA EN oferta_profesional_habilitado
--
-- Porque en esa tabla LISTA VACIA SIGNIFICA "TODOS" (V28). Una oferta sin restringir no tiene filas
-- de habilitacion, y para guardarle la duracion a Carla habria que crearle una: eso restringiria
-- la oferta a Carla y dejaria sin agenda a todos los demas, sin que nadie lo haya pedido. Quien
-- puede prestar y cuanto tarda son dos preguntas distintas y viven en dos tablas.
--
-- EL TURNO NO DEPENDE DE ESTA FILA. turno.fin se copia al reservar, asi que cambiar la duracion no
-- mueve lo ya dado; vale para lo que se ofrece y se reserva de ahi en mas.
--
-- Baja logica como en toda tabla de configuracion de la oferta: quitar la duracion cierra la fila
-- con su motivo, y el centinela de deleted_key sostiene el unique de la fila vigente.
--
-- Datos: sinteticos. Ningun dato personal.
-- =====================================================================================

CREATE TABLE oferta_duracion_profesional
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT      NOT NULL COMMENT 'Tenant propietario (ADR-0004). Todo unique e indice de esta tabla empieza por el',
    consultorio_id      BIGINT      NOT NULL COMMENT 'Sede de la oferta. Redundante a proposito, como en oferta_profesional_habilitado: la coherencia la sostiene la aplicacion, que la copia de la oferta',
    oferta_id           BIGINT      NOT NULL COMMENT 'Oferta a la que aplica',
    membership_id       BIGINT      NOT NULL COMMENT 'Vinculo del profesional con la organizacion. NO es account_id: ver V28',

    duracion_minutos    INT         NOT NULL COMMENT 'Cuanto dura un turno de esta oferta con este profesional. Manda sobre oferta_servicio_consultorio.duracion_minutos',

    active              TINYINT(1)  NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida. 0 tras la baja logica',
    deleted_at          DATETIME(6) NULL COMMENT 'Instante UTC de la baja logica. NULL mientras este vigente',
    deactivation_reason VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja',

    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique: en MySQL varios NULL no colisionan, asi que sin el centinela un profesional podria tener dos duraciones vigentes para la misma oferta',

    version             BIGINT      NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista de la fila. Lo que serializa a dos administradores es la version de la OFERTA',
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,

    CONSTRAINT pk_oferta_duracion_profesional PRIMARY KEY (id),

    CONSTRAINT uk_oferta_duracion_profesional_vigente
        UNIQUE (organization_id, oferta_id, membership_id, deleted_key),

    CONSTRAINT fk_odp_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_odp_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_odp_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),
    CONSTRAINT fk_odp_membership FOREIGN KEY (membership_id) REFERENCES membership (id),

    CONSTRAINT ck_odp_duracion_positiva CHECK (duracion_minutos > 0),
    CONSTRAINT ck_odp_baja_coherente CHECK (
        (active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
        OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    INDEX ix_odp_oferta (organization_id, oferta_id, active),
    INDEX ix_odp_profesional (organization_id, membership_id, active),
    INDEX ix_odp_sede (organization_id, consultorio_id, active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT 'Duracion propia de un profesional en una oferta (TURNO - Tecnico §47). Sin fila vale la de la oferta. Propietario: modulo offering';
