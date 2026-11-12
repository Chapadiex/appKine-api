-- =====================================================================================
-- AKINE-04.02 — Adjunto CLINICO: un binario que cuelga de la Historia Clinica (M09 / M25).
--
-- POR QUE V46
--
-- El diseño de la etapa (docs/diseno/AKINE-04.02-timeline-y-adjuntos-clinicos.md, seccion 8)
-- reservo V45 para `entrada_clinica` y V46 para esta tabla ANTES de escribir una linea, y por
-- el motivo que 02.07 y 03.01 pagaron caro: las dos nacieron como V26, Flyway rechaza
-- versiones duplicadas —"Found more than one migration with version N"— y la aplicacion no
-- arranca. Esta etapa se escribe en varios carriles a la vez, asi que el numero se reserva
-- antes y no se negocia despues. Flyway no exige versiones contiguas.
--
-- Trazabilidad: RF-M25-001..005 (subir, descargar, clasificar, dar de baja, listar) aplicados
-- a lo CLINICO, RF-M09-004 (el timeline los indexa); RN-M25-001 (validar tipo y tamano),
-- RN-M25-002 (no exponer rutas internas), RN-M25-003 (el acceso hereda permisos de la entidad
-- asociada), RN-M25-004 (un adjunto no reemplaza informacion estructurada), RN-M25-005 (lo
-- clinico NO vive en el contenedor administrativo); RN-M09-004 (los cambios clinicos son
-- trazables); DP-03 / ADR-0010 (la HC es de la ORGANIZACION), ADR-0011 (requisitos
-- clinico-legales); reglas maestras 1 y 10.
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0005 (Problem Details), ADR-0007 (expandir-migrar-contraer).
--
-- Propietario: modulo `clinical`, el mismo de `historia_clinica` y `entrada_clinica`. Ningun
-- otro modulo la lee ni la escribe (AGENT.md seccion 4, regla 1; challenge seccion 1).
--
-- Migracion unica y no expandir-migrar-contraer, mismo motivo que V19, V24, V27, V32, V40 y
-- V45: ADR-0007 exige el ciclo de tres para CAMBIOS sobre datos existentes. La tabla nace
-- vacia.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE ESTA TABLA EXISTE EN VEZ DE UNA CATEGORIA MAS EN adjunto_administrativo
--
-- Porque la cabecera de V40 ya habia decidido que no: RN-M25-005 prohibe que "una clase no
-- clinica se transforme en contenedor clinico", y V40 dejo escrito que el dia que hicieran
-- falta categorias clinicas "la tabla es otra y el modulo tambien". Esta es esa tabla.
--
-- La diferencia no es cosmetica y no se reduce a la lista de categorias. Un ESTUDIO cargado
-- en `adjunto_administrativo` se leeria con PERTENENCIA al tenant —que es como se autoriza la
-- ficha de una Persona— y no con la politica de DP-03: sin justificacion declarada, sin
-- relacion asistencial y sin auditoria de la LECTURA. O sea, una historia clinica paralela
-- sin ninguno de los controles de M09. Cambiar el ancla de `persona_id` a
-- `historia_clinica_id` es lo que arrastra a los tres controles detras.
--
-- ---------------------------------------------------------------------------------------
-- LOS BINARIOS NO ENTRAN A LA BASE, Y SU RAIZ DE DISCO ES OTRA
--
-- La tabla guarda METADATA; el contenido vive detras de
-- `clinical.domain.port.ContenidoClinicoStoragePort`. Los tres motivos son los de V40 y no se
-- repiten: backups y buffer pool, portabilidad a almacenamiento de objetos, y que una fila sin
-- binario es un estado detectable mientras que un binario sin fila no existe.
--
-- Lo que SI es nuevo: la raiz de disco es DISTINTA de la de los adjuntos administrativos
-- (`akine.clinical.adjuntos.base-dir` vs `akine.person.adjuntos.base-dir`). Es segregacion
-- fisica de binarios clinicos, que es un control real —otro volumen, otro cifrado en reposo,
-- otra politica de backup y de retencion— y no una consecuencia del codigo. El adaptador se
-- DUPLICA a proposito en vez de extraerse a `platform`, con su condicion de salida escrita:
-- tercer consumidor -> se extrae (diseño seccion 4).
--
-- El orden de escritura es el mismo y sigue siendo lo que importa: fila primero, con flush,
-- y blob despues, dentro de la misma transaccion de negocio. Si el blob falla, la transaccion
-- revierte y no queda fila. Si la transaccion revierte despues de escribirlo, queda un archivo
-- huerfano que nadie referencia: invisible e inofensivo. El orden inverso produciria filas
-- apuntando a nada, que es el unico de los dos errores que el usuario ve.
--
-- ---------------------------------------------------------------------------------------
-- storage_key: OPACA, GENERADA POR EL SERVIDOR, Y NUNCA VIAJA AL CLIENTE
--
-- RN-M25-002 prohibe exponer rutas internas. `storage_key` es un UUID sin guiones y no
-- contiene el nombre del archivo, ni la organizacion, ni la historia, ni la extension: el
-- adaptador deriva la ruta a partir de ella. Consecuencia buscada: no hay ninguna cadena de
-- origen externo que participe de un path, y el path traversal deja de ser una clase de bug
-- POSIBLE en vez de ser un bug prevenido por un `if`.
--
-- ---------------------------------------------------------------------------------------
-- checksum_sha256 Y LA IDEMPOTENCIA DEL REINTENTO
--
-- El unique lleva (organization_id, historia_clinica_id, checksum_sha256, deleted_key). No
-- esta para ahorrar disco: esta para que subir dos veces el mismo estudio a la misma historia
-- sea UNA sola cosa. Subir es el caso de uso mas expuesto a reintentos que existe —conexiones
-- de consultorio, informes de varios MB, timeouts— y sin esta clave el reintento de un POST
-- que si habia llegado deja dos filas identicas que despues alguien desempata a ojo dentro de
-- una historia clinica.
--
-- La aplicacion no responde 409 ante ese choque: devuelve el adjunto que ya existe con 200,
-- que es lo que hace idempotente al endpoint en vez de meramente seguro.
--
-- El alcance del unique es la HISTORIA y no la entrada, aunque `entrada_clinica_id` exista.
-- Es deliberado: el mismo informe adjuntado a dos evoluciones distintas del mismo paciente
-- sigue siendo UN documento, y duplicarlo por cada entrada que lo menciona convertiria la
-- reclasificacion y la baja en operaciones que hay que repetir N veces para que el paciente
-- deje de verlo.
--
-- Lleva `deleted_key` con el mismo centinela de V18/V19/V20/V24/V27/V32/V40/V45, y por el
-- mismo motivo: un estudio dado de baja se puede volver a subir, y `UNIQUE (..., deleted_at)`
-- protegeria el historico y desprotegeria lo vigente porque varios NULL no colisionan en
-- MySQL.
--
-- ---------------------------------------------------------------------------------------
-- entrada_clinica_id ES NULLABLE, Y LAS DOS MITADES DE ESO SON DECISIONES
--
-- NULLABLE porque un estudio que un paciente trae al mostrador cuelga de la historia y de
-- nada mas: exigir una entrada obligaria a inventar una evolucion vacia para poder adjuntar,
-- que es informacion falsa escrita por una restriccion de esquema.
--
-- Y EXISTE porque el caso normal es el contrario —"este informe es el que motivo esta
-- evolucion"— y sin la columna ese vinculo se perderia en el texto libre de un titulo. La FK
-- es RESTRICT: una entrada con adjuntos no se borra fisicamente, y no hace falta que lo haga
-- porque su baja es logica.
--
-- Lo que la BASE no puede exigir y hace cumplir la aplicacion: que esa entrada pertenezca a
-- ESTA historia. Un CHECK no puede consultar otra tabla y una FK compuesta obligaria a
-- meter `historia_clinica_id` dentro de una clave de `entrada_clinica` solo para esto.
-- `AdjuntoClinicoService` lo valida antes de insertar y rechaza como "no accesible" —404— para
-- no confirmar que ese id de entrada existe en otra historia.
--
-- ---------------------------------------------------------------------------------------
-- categoria: CLINICA, Y ESA ES LA LINEA QUE SEPARA ESTA TABLA DE LA OTRA
--
-- ESTUDIO, INFORME, IMAGEN, CONSENTIMIENTO_CLINICO, EVOLUCION_ESCANEADA, OTRO. Ninguna
-- categoria administrativa: un DNI o una credencial de cobertura cargados aca quedarian detras
-- de `hc:read` con justificacion, o sea inaccesibles para el mostrador que los necesita todos
-- los dias. La separacion corre en las dos direcciones o no es una separacion.
--
-- CONSENTIMIENTO_CLINICO se llama asi y no CONSENTIMIENTO para que nadie lo confunda con el
-- administrativo de V40: el de alla es el de tratamiento de datos y el de aca es el informado
-- de una practica.
--
-- OTRO existe para no bloquear una carga legitima por una categoria que este catalogo no
-- previo, mismo criterio que V40 y que `persona.tipo_documento`.
--
-- La lista esta duplicada en el enum de la aplicacion. Es deliberado y es el mismo patron que
-- `espacio.tipo` y `servicio.naturaleza`: la base tiene que rechazar por si sola lo que la
-- aplicacion no deberia mandar nunca.
--
-- ---------------------------------------------------------------------------------------
-- LA BAJA ES LOGICA Y NO BORRA EL BINARIO
--
-- Cuarteto `active`/`deleted_at`/`deactivation_reason`/`deleted_key`, igual que el resto del
-- esquema. Y el archivo en disco SE QUEDA (challenge seccion 5): un job de limpieza es otra
-- cosa y necesita una politica de retencion que nadie escribio todavia. Borrarlo aca
-- significaria que dar de baja por error es irreversible sobre un estudio clinico.
--
-- `estado` DISPONIBLE/NO_DISPONIBLE cubre el otro lado: si el almacenamiento perdio el
-- binario, la metadata sobrevive y la descarga responde 409, no 404. Un 404 le diria al
-- profesional que el estudio no existe y lo empujaria a pedirselo de nuevo al paciente bajo
-- una historia que todavia afirma tenerlo.
--
-- ---------------------------------------------------------------------------------------
-- consultorio_id ES DE AUDITORIA, NO DE PROPIEDAD
--
-- La Historia Clinica es de la ORGANIZACION (DP-03, V32) y su adjunto tambien: se ve y se
-- descarga desde cualquier sede de la organizacion, con la politica de acceso de DP-03.
-- `consultorio_id` guarda la sede desde la que se cargo, que es un dato del HECHO y no del
-- dato, y por eso es NULLABLE y no participa de ningun unique ni de ningun indice de
-- busqueda. Ponerlo en el unique ataria el estudio a la sede y reintroduciria por la ventana
-- el modelo que DP-03 rechazo por la puerta.
-- =====================================================================================

CREATE TABLE adjunto_clinico
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT       NOT NULL COMMENT 'Tenant propietario. Todo unique e indice de esta tabla empieza por el (AGENT.md seccion 5)',
    historia_clinica_id BIGINT       NOT NULL COMMENT 'Historia de la que cuelga el documento. El ancla es la HC y NO la Persona: es lo que arrastra la politica de DP-03 (permiso clinico, justificacion declarada y auditoria de la lectura). Ver la cabecera',
    entrada_clinica_id  BIGINT       NULL COMMENT 'Entrada clinica que el documento respalda, si la hay. NULLABLE: un estudio que el paciente trae al mostrador cuelga de la historia y de nada mas. Que la entrada sea de ESTA historia lo hace cumplir la aplicacion, no la base',
    consultorio_id      BIGINT       NULL COMMENT 'Sede desde la que se cargo. Dato de auditoria del hecho, NO de propiedad: ver la cabecera. No participa de ningun unique',

    categoria           VARCHAR(32)  NOT NULL COMMENT 'Clasificacion CLINICA (RF-M25-003). Ninguna categoria administrativa a proposito: la separacion con adjunto_administrativo corre en las dos direcciones',
    titulo              VARCHAR(160) NULL COMMENT 'Titulo con el que el profesional describe el documento. Opcional: el nombre del archivo ya dice algo',

    nombre_archivo      VARCHAR(255) NOT NULL COMMENT 'Nombre original con el que se subio. Se devuelve al descargar y NUNCA participa de la ruta en disco',
    content_type        VARCHAR(120) NOT NULL COMMENT 'Tipo DETECTADO por los bytes, no el declarado por el cliente. Un cliente que miente sobre su Content-Type no consigue nada',
    tamano_bytes        BIGINT       NOT NULL COMMENT 'Tamano real del contenido almacenado',
    checksum_sha256     CHAR(64)     NOT NULL COMMENT 'SHA-256 del contenido en hexadecimal. Integridad y, sobre todo, idempotencia del reintento: ver la cabecera',

    storage_key         VARCHAR(64)  NOT NULL COMMENT 'Clave OPACA del contenido en el almacenamiento clinico. UUID sin guiones, generado por el servidor. NUNCA viaja al cliente (RN-M25-002)',
    estado              VARCHAR(24)  NOT NULL DEFAULT 'DISPONIBLE' COMMENT 'DISPONIBLE mientras el contenido se pueda entregar. NO_DISPONIBLE cuando el almacenamiento perdio el binario: la metadata sobrevive y la descarga responde 409, no 404',

    subido_por          BIGINT       NOT NULL COMMENT 'accountId del actor que subio el archivo. Sin FK a cuenta: cuenta es de identity y una FK cruzaria la propiedad de datos entre modulos',
    subido_en           DATETIME(6)  NOT NULL COMMENT 'Instante UTC de la carga (RN-M09-004: los cambios clinicos son trazables)',

    active              TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida del adjunto. La baja es LOGICA (RF-M25-004) y NO borra el binario del disco: ver la cabecera',
    deleted_at          DATETIME(6)  NULL,
    deactivation_reason VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja. Obligatorio: un estudio que desaparece de una historia clinica sin explicacion es lo que ADR-0011 quiere impedir',

    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique de checksum. Mismo centinela y mismo motivo que en V27, V32, V40 y V45',

    version             BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista de la reclasificacion y de la baja',
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,

    CONSTRAINT pk_adjunto_clinico PRIMARY KEY (id),

    -- El mismo archivo, para la misma HISTORIA, es UNA sola fila mientras este vigente. Ver la
    -- cabecera: esto es la idempotencia del reintento, y el alcance es la historia y no la
    -- entrada a proposito.
    CONSTRAINT uk_adjunto_clinico_contenido_vigente
        UNIQUE (organization_id, historia_clinica_id, checksum_sha256, deleted_key),

    -- Dos filas no pueden apuntar al mismo binario: si lo hicieran, dar de baja una borraria
    -- el contenido de la otra el dia que exista el job de limpieza.
    CONSTRAINT uk_adjunto_clinico_storage_key UNIQUE (storage_key),

    CONSTRAINT fk_adjunto_clinico_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    -- RESTRICT (el default) en las dos: no hay borrado fisico de una historia ni de una
    -- entrada que tengan adjuntos. Las dos tienen baja logica y no lo necesitan.
    CONSTRAINT fk_adjunto_clinico_historia
        FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id),

    CONSTRAINT fk_adjunto_clinico_entrada
        FOREIGN KEY (entrada_clinica_id) REFERENCES entrada_clinica (id),

    CONSTRAINT ck_adjunto_clinico_categoria
        CHECK (categoria IN ('ESTUDIO', 'INFORME', 'IMAGEN', 'CONSENTIMIENTO_CLINICO',
                             'EVOLUCION_ESCANEADA', 'OTRO')),

    CONSTRAINT ck_adjunto_clinico_estado
        CHECK (estado IN ('DISPONIBLE', 'NO_DISPONIBLE')),

    CONSTRAINT ck_adjunto_clinico_tamano CHECK (tamano_bytes > 0),

    CONSTRAINT ck_adjunto_clinico_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- El camino de lectura principal: los adjuntos de una historia, filtrando por vigencia y
    -- opcionalmente por categoria (RF-M25-005). Empieza por organization_id como todo indice
    -- de este esquema (ADR-0004).
    INDEX ix_adjunto_clinico_historia (organization_id, historia_clinica_id, active, categoria),

    -- El otro: los documentos que respaldan una entrada. Es el que evita que abrir una
    -- evolucion con adjuntos recorra la tabla entera de la organizacion.
    INDEX ix_adjunto_clinico_entrada (organization_id, entrada_clinica_id, active)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Metadata de un documento CLINICO vinculado a una Historia Clinica (M09/M25). El binario vive fuera de la base, en una raiz SEPARADA de la de los adjuntos administrativos. NO es el contenedor administrativo de V40: RN-M25-005. Propietario: modulo clinical';
