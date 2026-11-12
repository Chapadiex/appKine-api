-- AKINE-M12, lista de espera de turnos (TURNO.md §20, TURNO - Tecnico §31 y §32).
--
-- Entidad propia y no un turno sin fecha: un turno ocupa un lugar y esto no ocupa nada. Es la de
-- las clases grupales (M28) en otra forma, y no se comparte con ella porque aquella cuelga de una
-- clase concreta y esta de un criterio de busqueda.
--
-- La fila no se borra: ACTIVA pasa a ASIGNADA o DESCARTADA y queda como historia (regla maestra 10).

CREATE TABLE lista_espera_turno (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id           BIGINT       NOT NULL,
    consultorio_id            BIGINT       NOT NULL,
    persona_id                BIGINT       NOT NULL COMMENT 'Persona del padron con perfil de paciente vigente al anotarse',
    oferta_id                 BIGINT       NOT NULL COMMENT 'Prestacion que espera',
    profesional_membership_id BIGINT       NULL COMMENT 'Profesional preferido. NULL = cualquiera habilitado',
    caso_id                   BIGINT       NULL COMMENT 'Caso clinico asociado, sin FK: es de clinical',
    plan_id                   BIGINT       NULL COMMENT 'Plan asociado, sin FK: es de clinical',
    fecha_desde               DATE         NOT NULL COMMENT 'Primer dia que le sirve, en la zona de la sede',
    fecha_hasta               DATE         NOT NULL COMMENT 'Ultimo dia que le sirve, INCLUSIVO',
    dias_semana               VARCHAR(13)  NULL COMMENT 'Dias posibles, ISO separados por coma (1,3,5). NULL = cualquiera',
    hora_desde                TIME         NULL COMMENT 'Franja horaria local. NULL = cualquiera',
    hora_hasta                TIME         NULL COMMENT 'Fin de la franja, EXCLUSIVO',
    prioridad                 VARCHAR(8)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL, ALTA o URGENTE',
    notas                     VARCHAR(300) NULL COMMENT 'Preferencias operativas. NUNCA informacion clinica',
    estado                    VARCHAR(12)  NOT NULL DEFAULT 'ACTIVA' COMMENT 'ACTIVA, ASIGNADA o DESCARTADA',
    turno_id                  BIGINT       NULL COMMENT 'Turno que se le asigno',
    motivo_cierre             VARCHAR(300) NULL COMMENT 'Por que se descarto',
    creada_por_cuenta_id      BIGINT       NOT NULL,
    creada_en                 DATETIME(6)  NOT NULL,
    cerrada_por_cuenta_id     BIGINT       NULL,
    cerrada_en                DATETIME(6)  NULL,
    version                   BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_lista_espera_turno_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_lista_espera_turno_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_lista_espera_turno_turno FOREIGN KEY (turno_id) REFERENCES turno (id),
    CONSTRAINT ck_lista_espera_turno_fechas CHECK (fecha_hasta >= fecha_desde),
    CONSTRAINT ck_lista_espera_turno_franja
        CHECK ((hora_desde IS NULL AND hora_hasta IS NULL)
            OR (hora_desde IS NOT NULL AND hora_hasta IS NOT NULL AND hora_hasta > hora_desde)),
    CONSTRAINT ck_lista_espera_turno_prioridad CHECK (prioridad IN ('NORMAL', 'ALTA', 'URGENTE')),
    CONSTRAINT ck_lista_espera_turno_estado CHECK (estado IN ('ACTIVA', 'ASIGNADA', 'DESCARTADA')),
    CONSTRAINT ck_lista_espera_turno_asignada
        CHECK (estado <> 'ASIGNADA' OR turno_id IS NOT NULL),
    INDEX ix_lista_espera_turno_sede (organization_id, consultorio_id, estado, oferta_id),
    INDEX ix_lista_espera_turno_persona (organization_id, persona_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Pacientes que esperan un lugar de una prestacion. Propiedad de scheduling';
