-- =====================================================================================
-- V71 — C-6: la version de una sesion lleva la foto de sus tratamientos y mediciones
-- =====================================================================================
--
-- Modulo propietario: encounter. Diseno: docs/diseno/AKINE-C-6-enmiendas.md.
--
-- 06.06 (V53) versiono el RELATO de una sesion cerrada y dejo afuera los tratamientos
-- realizados (V55) y las mediciones (V52): una vez cerrada la atencion no se podian corregir, y
-- aunque se pudiera, la version no guardaria lo que habia antes. Estas tres tablas son la foto
-- COMPLETA de esos dos conjuntos en cada version: lo que puede cambiar cuelga de la version
-- (el principio de 04.04, plan_item -> plan_tratamiento_version).
--
-- POR QUE TABLAS Y NO JSON: V52 y V55 ya lo explicaron. Los CHECK tipados son los que impiden
-- un parametro sin tipo o un valor que no es del tipo copiado; una foto en json los saltearia,
-- y MySQL normaliza el json al guardarlo. Por eso las columnas y los CHECK se repiten.
--
-- SIN active, deleted_at, version ni updated_at: una foto es un hecho pasado y no se da de baja
-- ni se modifica, igual que sesion_version.
--
-- Nombres de constraint con prefijo propio (svt_, svtp_, svm_): en MySQL los nombres de FK y de
-- CHECK son unicos POR ESQUEMA, y V64 ya murio una vez por repetir uno.
-- =====================================================================================

CREATE TABLE sesion_version_tratamiento
(
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id           BIGINT       NOT NULL COMMENT 'Tenant. Encabeza el unique y el indice (AGENT.md seccion 5)',
    sesion_version_id         BIGINT       NOT NULL COMMENT 'Version de la sesion a la que pertenece esta foto',
    tratamiento_realizado_id  BIGINT       NOT NULL COMMENT 'El tratamiento vivo que se fotografio. Permite seguir el mismo tratamiento a traves de las versiones',
    orden                     INT          NOT NULL COMMENT 'Copia del orden cronologico del tratamiento',
    practica_id               BIGINT       NOT NULL COMMENT 'Practica realizada en esa version. El conjunto de practicas no cambia entre versiones: ver la seccion 4 del diseno',
    practica_codigo           VARCHAR(64)  NOT NULL,
    practica_nombre           VARCHAR(160) NOT NULL,
    tecnica                   VARCHAR(160) NULL,
    zona                      VARCHAR(120) NULL,
    lateralidad               VARCHAR(16)  NULL,
    duracion_minutos          INT          NULL,
    profesional_membership_id BIGINT       NOT NULL,
    espacio_id                BIGINT       NULL,
    espacio_nombre            VARCHAR(160) NULL,
    observacion               VARCHAR(500) NULL,
    created_at                DATETIME(6)  NOT NULL,
    CONSTRAINT pk_sesion_version_tratamiento PRIMARY KEY (id),
    CONSTRAINT uk_svt_orden
        UNIQUE (organization_id, sesion_version_id, orden),
    CONSTRAINT fk_svt_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_svt_sesion_version
        FOREIGN KEY (sesion_version_id) REFERENCES sesion_version (id),
    CONSTRAINT fk_svt_tratamiento
        FOREIGN KEY (tratamiento_realizado_id) REFERENCES tratamiento_realizado (id),
    CONSTRAINT ck_svt_orden_positivo
        CHECK (orden > 0),
    CONSTRAINT ck_svt_duracion
        CHECK (duracion_minutos IS NULL
            OR (duracion_minutos > 0 AND duracion_minutos <= 1440)),
    -- El vocabulario de V65, NO_APLICA incluido.
    CONSTRAINT ck_svt_lateralidad
        CHECK (lateralidad IS NULL
            OR lateralidad IN ('IZQUIERDA', 'DERECHA', 'BILATERAL', 'NO_APLICA')),
    CONSTRAINT ck_svt_lateralidad_con_zona
        CHECK (lateralidad IS NULL OR zona IS NOT NULL),
    CONSTRAINT ck_svt_espacio_snapshot
        CHECK ((espacio_id IS NULL AND espacio_nombre IS NULL)
            OR (espacio_id IS NOT NULL AND espacio_nombre IS NOT NULL)),
    INDEX ix_svt_tratamiento (organization_id, tratamiento_realizado_id)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Foto inmutable de un tratamiento realizado en una version de la sesion (C-6, RF-M14-010). Propietario: modulo encounter';

CREATE TABLE sesion_version_tratamiento_parametro
(
    id                            BIGINT         NOT NULL AUTO_INCREMENT,
    organization_id               BIGINT         NOT NULL COMMENT 'Tenant. Redundante con el del padre a proposito, igual que tratamiento_parametro',
    sesion_version_tratamiento_id BIGINT         NOT NULL,
    clave                         VARCHAR(64)    NOT NULL,
    tipo_dato                     VARCHAR(16)    NOT NULL,
    valor_numerico                DECIMAL(12, 3) NULL,
    valor_texto                   VARCHAR(280)   NULL,
    valor_booleano                TINYINT(1)     NULL,
    unidad                        VARCHAR(24)    NULL,
    orden                         INT            NOT NULL DEFAULT 0,
    created_at                    DATETIME(6)    NOT NULL,
    CONSTRAINT pk_sesion_version_tratamiento_parametro PRIMARY KEY (id),
    CONSTRAINT uk_svtp_clave
        UNIQUE (organization_id, sesion_version_tratamiento_id, clave),
    CONSTRAINT fk_svtp_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_svtp_tratamiento
        FOREIGN KEY (sesion_version_tratamiento_id) REFERENCES sesion_version_tratamiento (id),
    CONSTRAINT ck_svtp_tipo
        CHECK (tipo_dato IN ('NUMERICO', 'TEXTO', 'BOOLEANO')),
    CONSTRAINT ck_svtp_valor
        CHECK ((tipo_dato = 'NUMERICO'
                    AND valor_numerico IS NOT NULL
                    AND valor_texto IS NULL AND valor_booleano IS NULL)
            OR (tipo_dato = 'TEXTO'
                    AND valor_texto IS NOT NULL
                    AND valor_numerico IS NULL AND valor_booleano IS NULL)
            OR (tipo_dato = 'BOOLEANO'
                    AND valor_booleano IS NOT NULL
                    AND valor_numerico IS NULL AND valor_texto IS NULL)),
    CONSTRAINT ck_svtp_unidad
        CHECK (unidad IS NULL OR tipo_dato = 'NUMERICO')
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Foto inmutable de un parametro tipado de un tratamiento en una version de la sesion (C-6). Propietario: modulo encounter';

CREATE TABLE sesion_version_medicion
(
    id                       BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id          BIGINT        NOT NULL COMMENT 'Tenant. Encabeza el unique (AGENT.md seccion 5)',
    sesion_version_id        BIGINT        NOT NULL,
    definicion_id            BIGINT        NOT NULL,
    definicion_codigo        VARCHAR(48)   NOT NULL,
    definicion_nombre        VARCHAR(160)  NOT NULL,
    definicion_unidad        VARCHAR(24)   NULL,
    definicion_tipo          VARCHAR(16)   NOT NULL,
    definicion_version       BIGINT        NOT NULL,
    lateralidad              VARCHAR(16)   NOT NULL,
    valor_numerico           DECIMAL(10,3) NULL,
    valor_texto              VARCHAR(500)  NULL,
    valor_booleano           TINYINT(1)    NULL,
    nota                     VARCHAR(280)  NULL,
    registrada_en            DATETIME(6)   NOT NULL COMMENT 'Ultima escritura de la medicion viva al momento de la foto',
    registrada_por_cuenta_id BIGINT        NOT NULL COMMENT 'Quien la cargo o la corrigio por ultima vez al momento de la foto',
    created_at               DATETIME(6)   NOT NULL,
    CONSTRAINT pk_sesion_version_medicion PRIMARY KEY (id),
    CONSTRAINT uk_svm_medida
        UNIQUE (organization_id, sesion_version_id, definicion_id, lateralidad),
    CONSTRAINT fk_svm_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_svm_sesion_version
        FOREIGN KEY (sesion_version_id) REFERENCES sesion_version (id),
    CONSTRAINT fk_svm_definicion
        FOREIGN KEY (definicion_id) REFERENCES medicion_definicion (id),
    CONSTRAINT ck_svm_lateralidad
        CHECK (lateralidad IN ('IZQUIERDA', 'DERECHA', 'NO_APLICA')),
    CONSTRAINT ck_svm_tipo
        CHECK (definicion_tipo IN ('NUMERICO', 'ESCALA', 'TEXTO', 'BOOLEANO')),
    CONSTRAINT ck_svm_valor_tipado
        CHECK ((definicion_tipo IN ('NUMERICO', 'ESCALA')
                    AND valor_numerico IS NOT NULL
                    AND valor_texto IS NULL
                    AND valor_booleano IS NULL)
            OR (definicion_tipo = 'TEXTO'
                    AND valor_texto IS NOT NULL
                    AND valor_numerico IS NULL
                    AND valor_booleano IS NULL)
            OR (definicion_tipo = 'BOOLEANO'
                    AND valor_booleano IS NOT NULL
                    AND valor_numerico IS NULL
                    AND valor_texto IS NULL)),
    CONSTRAINT ck_svm_unidad
        CHECK ((definicion_tipo IN ('NUMERICO', 'ESCALA') AND definicion_unidad IS NOT NULL)
            OR (definicion_tipo IN ('TEXTO', 'BOOLEANO') AND definicion_unidad IS NULL))
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Foto inmutable de una medicion en una version de la sesion (C-6, RF-M14-004/010). Propietario: modulo encounter';

-- =====================================================================================
-- Backfill: cada version existente recibe la foto del estado ACTUAL de su sesion.
--
-- Es exacto y no una aproximacion: hasta esta etapa una sesion cerrada no podia cambiar sus
-- tratamientos ni sus mediciones (TratamientoService y MedicionService respondian 409
-- sesion-cerrada), y las versiones solo existen para sesiones cerradas. El estado de hoy es el
-- estado de todas sus versiones. Solo tratamientos VIGENTES: uno dado de baja antes del cierre no
-- formaba parte de lo cerrado.
-- =====================================================================================

INSERT INTO sesion_version_tratamiento
       (organization_id, sesion_version_id, tratamiento_realizado_id, orden, practica_id,
        practica_codigo, practica_nombre, tecnica, zona, lateralidad, duracion_minutos,
        profesional_membership_id, espacio_id, espacio_nombre, observacion, created_at)
SELECT sv.organization_id, sv.id, tr.id, tr.orden, tr.practica_id,
       tr.practica_codigo, tr.practica_nombre, tr.tecnica, tr.zona, tr.lateralidad,
       tr.duracion_minutos, tr.profesional_membership_id, tr.espacio_id, tr.espacio_nombre,
       tr.observacion, sv.registrada_en
  FROM sesion_version sv
  JOIN tratamiento_realizado tr
    ON tr.organization_id = sv.organization_id
   AND tr.sesion_id = sv.sesion_id
   AND tr.active = 1;

INSERT INTO sesion_version_tratamiento_parametro
       (organization_id, sesion_version_tratamiento_id, clave, tipo_dato, valor_numerico,
        valor_texto, valor_booleano, unidad, orden, created_at)
SELECT svt.organization_id, svt.id, tp.clave, tp.tipo_dato, tp.valor_numerico,
       tp.valor_texto, tp.valor_booleano, tp.unidad, tp.orden, svt.created_at
  FROM sesion_version_tratamiento svt
  JOIN tratamiento_parametro tp
    ON tp.organization_id = svt.organization_id
   AND tp.tratamiento_realizado_id = svt.tratamiento_realizado_id;

INSERT INTO sesion_version_medicion
       (organization_id, sesion_version_id, definicion_id, definicion_codigo, definicion_nombre,
        definicion_unidad, definicion_tipo, definicion_version, lateralidad, valor_numerico,
        valor_texto, valor_booleano, nota, registrada_en, registrada_por_cuenta_id, created_at)
SELECT sv.organization_id, sv.id, sm.definicion_id, sm.definicion_codigo, sm.definicion_nombre,
       sm.definicion_unidad, sm.definicion_tipo, sm.definicion_version, sm.lateralidad,
       sm.valor_numerico, sm.valor_texto, sm.valor_booleano, sm.nota, sm.registrada_en,
       sm.registrada_por_cuenta_id, sv.registrada_en
  FROM sesion_version sv
  JOIN sesion_medicion sm
    ON sm.organization_id = sv.organization_id
   AND sm.sesion_id = sv.sesion_id;
