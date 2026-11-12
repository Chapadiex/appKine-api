-- =====================================================================================
-- AKINE-03.02 — Adjuntos administrativos de una Persona (M07 / M25).
--
-- POR QUE V40 Y NO V39
--
-- V39 esta tomada por otra etapa en vuelo que todavia no aterrizo en esta rama. Es
-- exactamente la colision que 02.07 y 03.01 pagaron con V26 —dos migraciones con el mismo
-- numero y Flyway no arranca la aplicacion, "Found more than one migration with version N"—.
-- Reservar el numero por adelantado cuesta un hueco; descubrirlo al mergear cuesta renumerar
-- una migracion ya aplicada, que es peor. Flyway no exige versiones contiguas.
--
-- Trazabilidad: RF-M07-004 (Paciente 360), RF-M07-005 (baja logica), RF-M07-006 (adjuntos
-- administrativos), RF-M25-001..005 (subir, descargar, clasificar, dar de baja, listar);
-- RN-M07-003 (lo clinico vive en los modulos clinicos), RN-M07-004 (no hay borrado fisico),
-- RN-M25-001 (validar tipo y tamano), RN-M25-002 (no exponer rutas internas), RN-M25-003 (el
-- acceso hereda permisos de la entidad asociada), RN-M25-004 (un adjunto no reemplaza
-- informacion estructurada), RN-M25-005 (los adjuntos clinicos NO entran aca).
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0005 (Problem Details), ADR-0007 (expandir-migrar-contraer).
--
-- Propietario: modulo `person`, el mismo que ya posee `persona` y `perfil_paciente`. Ningun
-- otro modulo la lee ni la escribe (AGENT.md seccion 4, regla 1).
--
-- Migracion unica y no expandir-migrar-contraer, mismo motivo que V19, V24 y V27: ADR-0007
-- exige el ciclo de tres para CAMBIOS sobre datos existentes. La tabla nace vacia.
--
-- LA ETAPA NO CREA NINGUNA TABLA MAS. La baja logica de RF-M07-005 no necesita esquema nuevo:
-- V27 ya dejo `active`, `deleted_at`, `deactivation_reason` y `deleted_key` en `persona` y en
-- `perfil_paciente` precisamente por esto, y su cabecera lo dice. Y el Paciente 360 tampoco:
-- es una agregacion que se calcula al leer, con cada modulo aportando lo suyo por el spi. Una
-- tabla de resumen materializada seria una segunda copia de la verdad que se desincroniza el
-- dia que alguien anula una obligacion sin avisar.
--
-- ---------------------------------------------------------------------------------------
-- LOS BINARIOS NO ENTRAN A LA BASE
--
-- La tabla guarda METADATA. El contenido vive fuera, detras de `person.domain.port
-- .AdjuntoStoragePort`, cuyo unico adaptador hoy escribe en el sistema de archivos local. Tres
-- motivos, en orden de importancia:
--
--   1. Un BLOB de 10 MB por fila convierte cada backup, cada dump y cada replica del esquema
--      en un problema de almacenamiento, y arrastra al buffer pool de InnoDB contenido que
--      ninguna consulta filtra.
--   2. El dia que esto corra contra almacenamiento de objetos, migrar `storage_key` es cambiar
--      un adaptador; migrar un LONGBLOB es un proyecto.
--   3. Una fila sin binario es un estado detectable y recuperable (`estado`); un binario sin
--      fila no existe.
--
-- La contrapartida esta asumida y hay que conocerla: **la escritura del blob y la de la fila
-- no son una sola transaccion**. El orden elegido es fila primero —con flush, para que el
-- unique decida— y blob despues, dentro de la misma transaccion de negocio. Si el blob falla,
-- la transaccion revierte y no queda fila. Si el blob se escribe y la transaccion revierte
-- despues, queda un archivo huerfano que NADIE referencia: invisible, inofensivo y barrible
-- por un job. El orden inverso —blob primero— produciria filas apuntando a nada, que es el
-- unico de los dos errores que el usuario ve.
--
-- ---------------------------------------------------------------------------------------
-- storage_key: OPACA, GENERADA POR EL SERVIDOR, Y NUNCA VIAJA AL CLIENTE
--
-- RN-M25-002 prohibe exponer rutas internas. `storage_key` es un UUID sin guiones y **no
-- contiene el nombre del archivo, ni la organizacion, ni la extension**: el adaptador deriva
-- la ruta a partir de ella. Consecuencia buscada: no hay ninguna cadena de origen externo que
-- participe de un path, y el path traversal deja de ser una clase de bug posible en vez de ser
-- un bug prevenido por un `if`.
--
-- El nombre con el que el operador subio el archivo se guarda en `nombre_archivo` porque es lo
-- que hay que devolverle al descargar, y **solo** para eso. Nunca toca el disco.
--
-- ---------------------------------------------------------------------------------------
-- checksum_sha256 Y LA IDEMPOTENCIA DEL REINTENTO
--
-- El unique lleva (organization_id, persona_id, checksum_sha256, deleted_key). No esta para
-- ahorrar disco: esta para que **subir dos veces el mismo archivo a la misma persona sea una
-- sola cosa**. Una subida es el caso de uso mas expuesto a reintentos que existe —conexiones
-- de mostrador, archivos de varios MB, timeouts— y sin esta clave el reintento de un POST que
-- si habia llegado deja dos filas identicas que despues alguien tiene que desempatar a ojo.
--
-- La aplicacion no responde 409 ante ese choque: **devuelve el adjunto que ya existe con 200**,
-- que es lo que hace idempotente al endpoint en vez de meramente seguro. Un 409 seria correcto
-- y aun asi inutil: le pediria al operador que resuelva una carrera que el no produjo.
--
-- Lleva `deleted_key` con el mismo centinela de V18/V19/V20/V24/V27, y por el mismo motivo: un
-- archivo dado de baja se puede volver a subir, y `UNIQUE (..., deleted_at)` protegeria el
-- historico y desprotegeria lo vigente porque varios NULL no colisionan en MySQL.
--
-- ---------------------------------------------------------------------------------------
-- categoria: ADMINISTRATIVA, Y LA LISTA NO TIENE NINGUNA CLINICA
--
-- RN-M25-005 es explicita: los adjuntos clinicos siguen asociados a Caso o Atencion y "una
-- clase no clinica no debe transformarse en contenedor clinico". Por eso el CHECK no incluye
-- ESTUDIO, INFORME, RADIOGRAFIA ni nada parecido: agregar una de esas categorias convertiria
-- esta tabla en una historia clinica paralela sin ninguno de los controles de M09 —sin
-- justificacion declarada, sin auditoria de lectura clinica, sin relacion asistencial—. El dia
-- que hagan falta, la tabla es otra y el modulo tambien.
--
-- OTRO existe para no bloquear una carga por una categoria que este catalogo no previo, mismo
-- criterio que `persona.tipo_documento`.
--
-- ---------------------------------------------------------------------------------------
-- consultorio_id ES DE AUDITORIA, NO DE PROPIEDAD
--
-- La Persona pertenece a la ORGANIZACION (V27) y su adjunto tambien: se ve y se descarga desde
-- cualquier sede de la organizacion. `consultorio_id` guarda la sede desde la que se cargo,
-- que es un dato del hecho y no del dato, y por eso es NULLABLE y **no participa de ningun
-- unique ni de ningun indice de busqueda**. Ponerlo en el unique ataria el archivo a la sede y
-- reintroduciria por la ventana el modelo que V27 rechazo por la puerta.
-- =====================================================================================

CREATE TABLE adjunto_administrativo
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT       NOT NULL COMMENT 'Tenant propietario. Todo unique e indice de esta tabla empieza por el (AGENT.md seccion 5)',
    persona_id          BIGINT       NOT NULL COMMENT 'Persona a la que se vincula el documento. El acceso al adjunto hereda el de la persona (RN-M25-003)',
    consultorio_id      BIGINT       NULL COMMENT 'Sede desde la que se cargo. Dato de auditoria del hecho, NO de propiedad: ver la cabecera. No participa de ningun unique',

    categoria           VARCHAR(32)  NOT NULL COMMENT 'Clasificacion ADMINISTRATIVA (RF-M25-003). La lista no tiene ninguna categoria clinica a proposito: RN-M25-005',
    titulo              VARCHAR(160) NULL COMMENT 'Titulo con el que el operador describe el documento. Opcional: el nombre del archivo ya dice algo',

    nombre_archivo      VARCHAR(255) NOT NULL COMMENT 'Nombre original con el que se subio. Se devuelve al descargar y NUNCA participa de la ruta en disco',
    content_type        VARCHAR(120) NOT NULL COMMENT 'Tipo DETECTADO por los bytes, no el declarado por el cliente. Un cliente que miente sobre su Content-Type no consigue nada',
    tamano_bytes        BIGINT       NOT NULL COMMENT 'Tamano real del contenido almacenado',
    checksum_sha256     CHAR(64)     NOT NULL COMMENT 'SHA-256 del contenido en hexadecimal. Integridad y, sobre todo, idempotencia del reintento: ver la cabecera',

    storage_key         VARCHAR(64)  NOT NULL COMMENT 'Clave OPACA del contenido en el almacenamiento. UUID sin guiones, generado por el servidor. NUNCA viaja al cliente (RN-M25-002)',
    estado              VARCHAR(24)  NOT NULL DEFAULT 'DISPONIBLE' COMMENT 'DISPONIBLE mientras el contenido se pueda entregar. NO_DISPONIBLE cuando el almacenamiento perdio el binario: la metadata sobrevive y la descarga responde 409, no 404',

    subido_por          BIGINT       NOT NULL COMMENT 'accountId del actor que subio el archivo. Sin FK a cuenta: cuenta es de identity y una FK cruzaria la propiedad de datos entre modulos',
    subido_en           DATETIME(6)  NOT NULL COMMENT 'Instante UTC de la carga',

    active              TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida del adjunto. La baja es LOGICA (RF-M25-004): el archivo deja de listarse y su historico sigue existiendo',
    deleted_at          DATETIME(6)  NULL,
    deactivation_reason VARCHAR(280) NULL,

    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique de checksum. Mismo centinela y mismo motivo que en persona',

    version             BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista de la reclasificacion',
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,

    CONSTRAINT pk_adjunto_administrativo PRIMARY KEY (id),

    -- El mismo archivo, para la misma persona, es UNA sola fila mientras este vigente. Ver la
    -- cabecera: esto es la idempotencia del reintento, no un ahorro de disco.
    CONSTRAINT uk_adjunto_contenido_vigente
        UNIQUE (organization_id, persona_id, checksum_sha256, deleted_key),

    -- Dos filas no pueden apuntar al mismo binario: si lo hicieran, dar de baja una borraria
    -- el contenido de la otra el dia que exista el job de limpieza.
    CONSTRAINT uk_adjunto_storage_key UNIQUE (storage_key),

    CONSTRAINT fk_adjunto_persona FOREIGN KEY (persona_id) REFERENCES persona (id),

    CONSTRAINT ck_adjunto_categoria
        CHECK (categoria IN ('DOCUMENTO_IDENTIDAD', 'CREDENCIAL_COBERTURA', 'CONSENTIMIENTO',
                             'AUTORIZACION', 'COMPROBANTE', 'OTRO')),

    CONSTRAINT ck_adjunto_estado
        CHECK (estado IN ('DISPONIBLE', 'NO_DISPONIBLE')),

    CONSTRAINT ck_adjunto_tamano CHECK (tamano_bytes > 0),

    CONSTRAINT ck_adjunto_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- El unico camino de lectura que existe: los adjuntos de una persona, filtrando por estado
    -- y opcionalmente por categoria (RF-M25-005). Empieza por organization_id como todo indice
    -- de este esquema (ADR-0004).
    INDEX ix_adjunto_persona (organization_id, persona_id, active, categoria)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Metadata de un documento ADMINISTRATIVO vinculado a una Persona (M07/M25). El binario vive fuera de la base. NO es un contenedor clinico: RN-M25-005. Propietario: modulo person';
