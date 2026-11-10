-- =====================================================================================
-- AKINE M30 — Biblioteca del centro (PDF por organizacion con embeddings)
--
-- libro             : el PDF subido y su estado de procesamiento. Unico por (organizacion, hash)
--                     mientras este activo y no sea un reemplazo en curso.
-- libro_fragmento   : trozos de texto con su vector (float32 little-endian en BLOB) y el modelo
--                     que lo calculo. El borrado del libro los cascadea.
-- biblioteca_version: contador por organizacion para invalidar caches de busqueda.
--
-- DESVIACION DEL DISENO: fk_libro_reemplaza NO lleva ON DELETE SET NULL. MySQL prohibe que una
-- columna generada STORED (hash_activo) dependa de una columna base cuya FK tenga una accion
-- referencial. El servicio borra primero el reemplazo pendiente y deja reemplaza_libro_id en
-- NULL cuando el reemplazo termina (queda RESTRICT para el resto).
-- =====================================================================================

CREATE TABLE libro
(
    id                                 BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id                    BIGINT       NOT NULL COMMENT 'Tenant propietario',

    titulo                             VARCHAR(300) NOT NULL,
    autores                            VARCHAR(500) NULL,
    edicion                            VARCHAR(100) NULL,
    area                               VARCHAR(100) NULL,

    storage_key                        VARCHAR(64)  NOT NULL COMMENT 'Clave opaca del PDF en el almacenamiento. Nunca viaja al cliente',
    nombre_archivo                     VARCHAR(255) NOT NULL,
    content_type                       VARCHAR(120) NOT NULL,
    tamano_bytes                       BIGINT       NOT NULL,
    hash_sha256                        CHAR(64)     NOT NULL,
    paginas                            INT          NULL COMMENT 'Se conoce al terminar de extraer',

    estado                             VARCHAR(20)  NOT NULL DEFAULT 'PENDIENTE',
    progreso                           INT          NOT NULL DEFAULT 0,
    motivo_error                       VARCHAR(500) NULL,

    reemplaza_libro_id                 BIGINT       NULL COMMENT 'Libro al que este reemplaza mientras no este LISTO',
    activo                             TINYINT(1)   NOT NULL DEFAULT 1,

    hash_activo                        CHAR(64) GENERATED ALWAYS AS (IF(activo AND reemplaza_libro_id IS NULL, hash_sha256, NULL)) STORED
        COMMENT 'Hash solo para libros activos que no son un reemplazo en curso: soporta el unique sin bloquear al reemplazo',

    procesando_desde                   DATETIME(6)  NULL COMMENT 'Lease del worker',
    declaracion_derechos_en            DATETIME(6)  NOT NULL,
    declaracion_derechos_por_cuenta_id BIGINT       NOT NULL,

    creado_por_cuenta_id               BIGINT       NOT NULL,
    version                            BIGINT       NOT NULL DEFAULT 0,
    created_at                         DATETIME(6)  NOT NULL,
    updated_at                         DATETIME(6)  NOT NULL,

    CONSTRAINT pk_libro PRIMARY KEY (id),
    CONSTRAINT uk_libro_hash_activo UNIQUE (organization_id, hash_activo),
    CONSTRAINT fk_libro_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_libro_reemplaza FOREIGN KEY (reemplaza_libro_id) REFERENCES libro (id),
    CONSTRAINT ck_libro_estado CHECK (estado IN ('PENDIENTE', 'PROCESANDO', 'LISTO', 'ERROR')),
    CONSTRAINT ck_libro_progreso CHECK (progreso BETWEEN 0 AND 100),
    INDEX ix_libro_estado (estado, procesando_desde),
    INDEX ix_libro_organizacion (organization_id, activo, titulo)
);

CREATE TABLE libro_fragmento
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id   BIGINT       NOT NULL,
    libro_id          BIGINT       NOT NULL,
    orden             INT          NOT NULL,
    pagina_desde      INT          NOT NULL,
    pagina_hasta      INT          NOT NULL,
    texto             TEXT         NOT NULL,
    vector            BLOB         NULL COMMENT '768 float32 little-endian (3072 bytes). NULL hasta que el worker lo calcula',
    modelo_embeddings VARCHAR(100) NULL COMMENT 'Modelo que calculo el vector; distinto del vigente = a reindexar',
    sospechoso        TINYINT(1)   NOT NULL DEFAULT 0,

    CONSTRAINT pk_libro_fragmento PRIMARY KEY (id),
    CONSTRAINT fk_libro_fragmento_libro FOREIGN KEY (libro_id) REFERENCES libro (id) ON DELETE CASCADE,
    INDEX ix_libro_fragmento_libro (organization_id, libro_id),
    INDEX ix_libro_fragmento_modelo (modelo_embeddings),
    FULLTEXT INDEX ft_libro_fragmento_texto (texto)
);

CREATE TABLE biblioteca_version
(
    organization_id BIGINT NOT NULL,
    version         BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_biblioteca_version PRIMARY KEY (organization_id),
    CONSTRAINT fk_biblioteca_version_organization FOREIGN KEY (organization_id) REFERENCES organization (id)
);
