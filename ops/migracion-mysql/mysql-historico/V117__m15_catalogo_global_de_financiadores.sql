-- =====================================================================================
-- V117 — Catalogo global de financiadores y planes, adoptado por copia (DP-43, DU-13 / U-12)
-- =====================================================================================
--
-- La plataforma mantiene un catalogo de obras sociales y prepagas con sus planes. Un centro
-- ADOPTA una entrada y eso crea una COPIA PROPIA en financiador / plan_cobertura, con un puntero
-- informativo (catalogo_id) a la fila global de origen. Es la opcion (B) de
-- docs/diseno/AKINE-A-7-plataforma.md §3:
--
--   * Ninguna lectura existente de contracting cambia: la copia es una fila del centro como
--     cualquier otra, con organization_id NOT NULL, y las coberturas, convenios, aranceles y
--     snapshots siguen leyendo financiador / plan_cobertura igual que antes.
--   * El dato propio del centro se conserva: la copia se edita libremente (contacto, nombre,
--     planes) sin tocar el catalogo ni a los otros centros.
--   * La edicion del catalogo NO se propaga a las copias. La lectura del centro informa
--     "hay una version mas nueva" comparando instantes, sin aplicar nada.
--
-- Tablas globales SIN organization_id y SIN owner_key: una sola poblacion (todas las filas son
-- de la plataforma), igual que servicio en 02.06. owner_key solo hace falta cuando conviven filas
-- globales y de tenant en la misma tabla (ADR-0023, "la mitad tecnica"). La tabla hermana con
-- organization_id NOT NULL —la que pide la condicion 2 de ADR-0023— es financiador. Se agregan a
-- la lista de EsquemaMultiTenantIT.
--
-- Propietario de las cuatro tablas: modulo contracting.
-- =====================================================================================

CREATE TABLE financiador_catalogo
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    codigo              VARCHAR(64)  NOT NULL COMMENT 'Clave estable del financiador en el catalogo de la plataforma. INMUTABLE. Es el codigo que recibe la copia del centro al adoptarlo',
    nombre              VARCHAR(160) NOT NULL COMMENT 'Nombre con el que se lo muestra. Unico entre los vigentes del catalogo',
    tipo                VARCHAR(24)  NOT NULL COMMENT 'Misma lista cerrada que financiador.tipo',
    cuit                VARCHAR(13)  NULL COMMENT 'Normalizado a 11 digitos. NULL es un estado real',
    email_contacto      VARCHAR(254) NULL COMMENT 'Contacto administrativo general del financiador. El centro puede cambiarlo en su copia',
    telefono_contacto   VARCHAR(40)  NULL COMMENT 'Telefono administrativo general del financiador',
    observaciones       VARCHAR(500) NULL COMMENT 'Notas administrativas. Nunca contenido clinico',

    active              TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0 tras la baja logica: deja de ofrecerse para adoptar. Las copias ya adoptadas NO se tocan',
    deleted_at          DATETIME(6)  NULL,
    deactivation_reason VARCHAR(280) NULL,
    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador de los uniques, mismo centinela que financiador',

    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL COMMENT 'Ultima edicion de la cabecera. Junto con plan_catalogo.updated_at decide si una copia esta desactualizada',

    CONSTRAINT pk_financiador_catalogo PRIMARY KEY (id),
    CONSTRAINT uk_financiador_catalogo_codigo_vigente UNIQUE (codigo, deleted_key),
    CONSTRAINT uk_financiador_catalogo_nombre_vigente UNIQUE (nombre, deleted_key),
    CONSTRAINT uk_financiador_catalogo_cuit_vigente UNIQUE (cuit, deleted_key),
    CONSTRAINT ck_financiador_catalogo_tipo
        CHECK (tipo IN ('OBRA_SOCIAL', 'PREPAGA', 'ART', 'MUTUAL', 'ORGANISMO_PUBLICO', 'OTRO')),
    CONSTRAINT ck_financiador_catalogo_cuit_normalizado
        CHECK (cuit IS NULL OR cuit REGEXP '^[0-9]{11}$'),
    CONSTRAINT ck_financiador_catalogo_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),
    INDEX ix_financiador_catalogo_estado_nombre (active, nombre)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Catalogo global de financiadores de la plataforma (M15, DP-43). Sin organization_id: excepcion de ADR-0023. Un centro lo adopta como copia en financiador. Propietario: modulo contracting';


CREATE TABLE plan_catalogo
(
    id                      BIGINT         NOT NULL AUTO_INCREMENT,
    financiador_catalogo_id BIGINT         NOT NULL COMMENT 'Financiador del catalogo al que pertenece. Inmutable',

    codigo                  VARCHAR(64)    NOT NULL COMMENT 'Clave estable del plan dentro del financiador del catalogo. INMUTABLE',
    nombre                  VARCHAR(160)   NOT NULL,
    descripcion             VARCHAR(500)   NULL,
    vigencia_desde          DATE           NOT NULL,
    vigencia_hasta          DATE           NULL COMMENT 'Ultimo dia INCLUSIVO, como plan_cobertura',
    requiere_autorizacion   TINYINT(1)     NOT NULL DEFAULT 0,
    requiere_credencial     TINYINT(1)     NOT NULL DEFAULT 1,
    copago                  DECIMAL(12, 2) NULL COMMENT 'Copago de referencia. Se copia como DATO, igual que plan_cobertura.copago: no fija ningun importe que el devengado use',
    moneda                  CHAR(3)        NULL,

    active                  TINYINT(1)     NOT NULL DEFAULT 1,
    deleted_at              DATETIME(6)    NULL,
    deactivation_reason     VARCHAR(280)   NULL,
    deleted_key             DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL,

    version                 BIGINT         NOT NULL DEFAULT 0,
    created_at              DATETIME(6)    NOT NULL,
    updated_at              DATETIME(6)    NOT NULL,

    CONSTRAINT pk_plan_catalogo PRIMARY KEY (id),
    CONSTRAINT uk_plan_catalogo_codigo_vigente UNIQUE (financiador_catalogo_id, codigo, deleted_key),
    CONSTRAINT uk_plan_catalogo_nombre_vigente UNIQUE (financiador_catalogo_id, nombre, deleted_key),
    CONSTRAINT fk_plan_catalogo_financiador_catalogo
        FOREIGN KEY (financiador_catalogo_id) REFERENCES financiador_catalogo (id),
    CONSTRAINT ck_plan_catalogo_vigencia_coherente
        CHECK (vigencia_hasta IS NULL OR vigencia_hasta >= vigencia_desde),
    CONSTRAINT ck_plan_catalogo_copago_con_moneda
        CHECK ((copago IS NULL AND moneda IS NULL) OR (copago IS NOT NULL AND moneda IS NOT NULL)),
    CONSTRAINT ck_plan_catalogo_copago_no_negativo
        CHECK (copago IS NULL OR copago >= 0),
    CONSTRAINT ck_plan_catalogo_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL))
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Planes del catalogo global de financiadores (M15, DP-43). Sin organization_id: excepcion de ADR-0023. Propietario: modulo contracting';


-- -------------------------------------------------------------------------------------
-- El puntero informativo de la copia. NULL = financiador o plan cargado a mano por el centro,
-- que es todo lo que existia hasta hoy: la columna es aditiva y ninguna fila cambia.
--
-- uk_financiador_catalogo_vigente es la IDEMPOTENCIA de la adopcion: un centro no puede tener
-- dos copias VIGENTES del mismo financiador del catalogo. Los NULL no colisionan en MySQL, asi
-- que los financiadores propios conviven sin chocar. Dada de baja la copia, deleted_key cambia y
-- el centro puede volver a adoptarlo.
-- -------------------------------------------------------------------------------------

ALTER TABLE financiador
    ADD COLUMN catalogo_id BIGINT NULL
        COMMENT 'Financiador del catalogo global del que se adopto esta copia (DP-43). INFORMATIVO: ninguna lectura lo sigue para resolver datos; la copia es del centro. NULL = cargado a mano'
        AFTER organization_id,
    ADD CONSTRAINT uk_financiador_catalogo_vigente UNIQUE (organization_id, catalogo_id, deleted_key),
    ADD CONSTRAINT fk_financiador_catalogo_origen
        FOREIGN KEY (catalogo_id) REFERENCES financiador_catalogo (id);

ALTER TABLE plan_cobertura
    ADD COLUMN catalogo_id BIGINT NULL
        COMMENT 'Plan del catalogo global del que se copio este plan al adoptar su financiador (DP-43). INFORMATIVO. NULL = cargado a mano'
        AFTER financiador_id,
    ADD CONSTRAINT fk_plan_cobertura_catalogo_origen
        FOREIGN KEY (catalogo_id) REFERENCES plan_catalogo (id);
