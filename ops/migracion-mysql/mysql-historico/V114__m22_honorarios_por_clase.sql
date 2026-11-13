-- =====================================================================================
-- V114 — AKINE-08.09b: honorarios por clase (DP-38, U-41; RF-M22-006, RF-M22-007)
-- =====================================================================================
--
-- Diseno: docs/diseno/AKINE-08.09b-honorarios.md.
--
-- Tres tablas, de tres modulos distintos; ninguno lee la tabla de otro:
--
--   oferta_honorario_clase        modulo `offering`   la tarifa por participante presente, con vigencia
--   clase_instructor              modulo `activity`   los instructores ADICIONALES de una clase
--   liquidacion_honorario(_clase) modulo `billing`    la liquidacion confirmada y su snapshot por clase
--
-- Todas nacen vacias: nada de lo existente cambia de significado. Una clase sin filas en
-- clase_instructor tiene un solo instructor (el principal de clase_programada), como hasta V113.
-- =====================================================================================


-- ---------------------------------------------------------------------------------------
-- oferta_honorario_clase — CUANTO SE PAGA POR CADA PARTICIPANTE PRESENTE (offering)
--
-- Copia deliberada de oferta_precio_particular (V80): misma vigencia inclusiva, misma baja
-- logica, mismo no-solapamiento hecho cumplir por el lock de la fila de la oferta y no por un
-- indice (dos periodos que se cruzan no comparten valor de columna). El importe NO se edita: se
-- cierra la vigencia y se carga otro, y lo ya liquidado copio su importe (CA-M22-007-06).
-- ---------------------------------------------------------------------------------------
CREATE TABLE oferta_honorario_clase
(
    id                       BIGINT         NOT NULL AUTO_INCREMENT,

    organization_id          BIGINT         NOT NULL COMMENT 'Tenant propietario (AGENT.md §5)',
    consultorio_id           BIGINT         NOT NULL COMMENT 'Sede de la oferta, para que toda lectura lleve sede en el WHERE',
    oferta_id                BIGINT         NOT NULL COMMENT 'Oferta cuya tarifa fija esta fila. No se muda',

    importe_por_participante DECIMAL(12, 2) NOT NULL COMMENT 'Honorario por cada participante PRESENTE o PRESENTE_TARDE. No se edita',
    moneda                   CHAR(3)        NOT NULL COMMENT 'ISO 4217',

    vigencia_desde           DATE           NOT NULL COMMENT 'Primer dia en que rige (dia local de la clase en la sede)',
    vigencia_hasta           DATE           NULL COMMENT 'ULTIMO dia en que rige, INCLUSIVO. NULL = sin fin previsto',

    active                   TINYINT(1)     NOT NULL DEFAULT 1,
    deleted_at               DATETIME(6)    NULL,
    deactivation_reason      VARCHAR(280)   NULL,

    version                  BIGINT         NOT NULL DEFAULT 0,
    created_at               DATETIME(6)    NOT NULL,
    updated_at               DATETIME(6)    NOT NULL,

    CONSTRAINT pk_oferta_honorario_clase PRIMARY KEY (id),

    CONSTRAINT fk_oferta_honorario_clase_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_oferta_honorario_clase_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_oferta_honorario_clase_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),

    CONSTRAINT ck_oferta_honorario_clase_vigencia
        CHECK (vigencia_hasta IS NULL OR vigencia_hasta >= vigencia_desde),

    CONSTRAINT ck_oferta_honorario_clase_importe
        CHECK (importe_por_participante > 0),

    CONSTRAINT ck_oferta_honorario_clase_baja
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    INDEX ix_oferta_honorario_clase_resolucion
        (organization_id, oferta_id, active, vigencia_desde)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------------------------
-- clase_instructor — LOS INSTRUCTORES ADICIONALES DE UNA CLASE (activity)
--
-- El principal sigue en clase_programada.profesional_membership_id y no se toca. Esta tabla
-- agrega los demas. Reemplazar la lista NO borra: marca removido_en (regla maestra 10).
--
-- El adicional NO ocupa agenda: la exclusion de turnos y clases sigue mirando solo al
-- principal (diseno §3.2, limite declarado).
--
-- Sin unique: un mismo instructor puede haberse quitado y vuelto a agregar. Que no haya dos
-- filas VIGENTES del mismo instructor lo hace cumplir el servicio bajo el FOR UPDATE de la clase.
-- ---------------------------------------------------------------------------------------
CREATE TABLE clase_instructor
(
    id                     BIGINT      NOT NULL AUTO_INCREMENT,

    organization_id        BIGINT      NOT NULL,
    consultorio_id         BIGINT      NOT NULL,
    clase_id               BIGINT      NOT NULL,
    membership_id          BIGINT      NOT NULL COMMENT 'Membership del instructor adicional',

    agregado_en            DATETIME(6) NOT NULL,
    agregado_por_cuenta_id BIGINT      NOT NULL,
    removido_en            DATETIME(6) NULL COMMENT 'NULL = vigente',
    removido_por_cuenta_id BIGINT      NULL,

    CONSTRAINT pk_clase_instructor PRIMARY KEY (id),

    CONSTRAINT fk_clase_instructor_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_clase_instructor_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_clase_instructor_clase
        FOREIGN KEY (clase_id) REFERENCES clase_programada (id),
    CONSTRAINT fk_clase_instructor_membership
        FOREIGN KEY (membership_id) REFERENCES membership (id),

    CONSTRAINT ck_clase_instructor_remocion
        CHECK ((removido_en IS NULL AND removido_por_cuenta_id IS NULL)
            OR (removido_en IS NOT NULL AND removido_por_cuenta_id IS NOT NULL)),

    INDEX ix_clase_instructor_clase (organization_id, clase_id, removido_en),
    INDEX ix_clase_instructor_membership (organization_id, membership_id, removido_en)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------------------------
-- liquidacion_honorario — LA LIQUIDACION CONFIRMADA (billing)
--
-- Una por egreso. Nace VIGENTE al confirmar el borrador y pasa a LIBERADA cuando se anula su
-- egreso: sus clases vuelven a poder liquidarse. La fila queda.
-- ---------------------------------------------------------------------------------------
CREATE TABLE liquidacion_honorario
(
    id                        BIGINT         NOT NULL AUTO_INCREMENT,

    organization_id           BIGINT         NOT NULL,
    consultorio_id            BIGINT         NOT NULL,
    egreso_id                 BIGINT         NOT NULL COMMENT 'El egreso de M22 que la confirmacion creo',
    colaborador_membership_id BIGINT         NOT NULL,

    periodo_desde             DATE           NOT NULL,
    periodo_hasta             DATE           NOT NULL,
    importe_total             DECIMAL(12, 2) NOT NULL COMMENT 'Suma exacta de importe_colaborador del detalle',
    moneda                    CHAR(3)        NOT NULL,

    estado                    VARCHAR(16)    NOT NULL COMMENT 'VIGENTE | LIBERADA',
    confirmada_en             DATETIME(6)    NOT NULL,
    confirmada_por_cuenta_id  BIGINT         NOT NULL,
    liberada_en               DATETIME(6)    NULL,

    idempotency_key           VARCHAR(80)    NULL,
    request_hash              CHAR(64)       NULL,

    version                   BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT pk_liquidacion_honorario PRIMARY KEY (id),

    CONSTRAINT uk_liquidacion_honorario_egreso UNIQUE (organization_id, egreso_id),
    CONSTRAINT uk_liquidacion_honorario_idempotencia UNIQUE (organization_id, idempotency_key),

    CONSTRAINT fk_liquidacion_honorario_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_liquidacion_honorario_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_liquidacion_honorario_egreso
        FOREIGN KEY (egreso_id) REFERENCES egreso (id),
    CONSTRAINT fk_liquidacion_honorario_membership
        FOREIGN KEY (colaborador_membership_id) REFERENCES membership (id),

    CONSTRAINT ck_liquidacion_honorario_estado
        CHECK ((estado = 'VIGENTE' AND liberada_en IS NULL)
            OR (estado = 'LIBERADA' AND liberada_en IS NOT NULL)),
    CONSTRAINT ck_liquidacion_honorario_periodo
        CHECK (periodo_hasta >= periodo_desde),
    CONSTRAINT ck_liquidacion_honorario_importe
        CHECK (importe_total > 0),
    CONSTRAINT ck_liquidacion_honorario_idempotencia
        CHECK ((idempotency_key IS NULL) = (request_hash IS NULL)),

    INDEX ix_liquidacion_honorario_colaborador (organization_id, colaborador_membership_id, periodo_desde)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------------------------
-- liquidacion_honorario_clase — EL SNAPSHOT, UNA FILA POR CLASE (billing)
--
-- Todo lo que explica el importe se copia al confirmar y no se vuelve a leer nunca: un cambio
-- futuro de tarifa, de nombre de la oferta o de asistencia no altera la liquidacion cerrada
-- (CA-M22-007-06, RF-M22-007 paso 6). La oferta va aca y no en el egreso: una liquidacion
-- puede cubrir clases de varias ofertas (RF-M22-007).
--
-- EL CANDADO CONTRA LA DOBLE LIQUIDACION es uk_liquidacion_honorario_clase_vigente, con
-- liberada_key = 0 mientras la liquidacion esta VIGENTE y = id al liberarse: varias filas
-- liberadas de la misma clase y el mismo colaborador conviven, una sola vigente. Es el patron
-- del deleted_key. El servicio lo hace cumplir antes bajo el FOR UPDATE de las clases; el unique
-- es el cinturon.
-- ---------------------------------------------------------------------------------------
CREATE TABLE liquidacion_honorario_clase
(
    id                        BIGINT         NOT NULL AUTO_INCREMENT,

    organization_id           BIGINT         NOT NULL,
    liquidacion_id            BIGINT         NOT NULL,
    clase_id                  BIGINT         NOT NULL,
    colaborador_membership_id BIGINT         NOT NULL COMMENT 'Redundante con la cabecera: lo necesita el unique',

    clase_inicio              DATETIME(6)    NOT NULL,
    clase_fecha               DATE           NOT NULL COMMENT 'Dia local de la clase en la sede: el que eligio la tarifa',
    clase_titulo              VARCHAR(120)   NULL,
    oferta_id                 BIGINT         NOT NULL,
    oferta_nombre             VARCHAR(160)   NOT NULL,

    presentes                 INT            NOT NULL,
    honorario_id              BIGINT         NOT NULL COMMENT 'La tarifa aplicada (oferta_honorario_clase)',
    importe_por_participante  DECIMAL(12, 2) NOT NULL,
    importe_clase             DECIMAL(12, 2) NOT NULL COMMENT 'importe_por_participante x presentes',
    instructores              INT            NOT NULL,
    posicion                  INT            NOT NULL COMMENT '1 = primero en el orden estable: se lleva el centavo sobrante',
    importe_colaborador       DECIMAL(12, 2) NOT NULL,
    moneda                    CHAR(3)        NOT NULL,

    liberada_key              BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT pk_liquidacion_honorario_clase PRIMARY KEY (id),

    CONSTRAINT uk_liquidacion_honorario_clase_vigente
        UNIQUE (organization_id, clase_id, colaborador_membership_id, liberada_key),

    CONSTRAINT fk_liquidacion_honorario_clase_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_liquidacion_honorario_clase_liquidacion
        FOREIGN KEY (liquidacion_id) REFERENCES liquidacion_honorario (id),
    CONSTRAINT fk_liquidacion_honorario_clase_clase
        FOREIGN KEY (clase_id) REFERENCES clase_programada (id),
    CONSTRAINT fk_liquidacion_honorario_clase_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),

    CONSTRAINT ck_liquidacion_honorario_clase_reparto
        CHECK (presentes > 0 AND instructores > 0 AND posicion BETWEEN 1 AND instructores
            AND importe_colaborador > 0 AND importe_colaborador <= importe_clase),

    INDEX ix_liquidacion_honorario_clase_liquidacion (organization_id, liquidacion_id)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci;
