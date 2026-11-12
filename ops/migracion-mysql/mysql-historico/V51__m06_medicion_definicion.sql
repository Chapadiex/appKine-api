-- =====================================================================================
-- AKINE-06.03 — Catalogo de definiciones de medicion (M06, al servicio de M14).
--
-- Trazabilidad: RF-M14-004 (examen fisico y mediciones); RN-M06-001 (los catalogos usados
-- historicamente no se eliminan fisicamente), RN-M06-002 (un concepto dado de baja conserva
-- su significado historico). Regla maestra 10 (nada historico se borra).
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0007 (expandir-migrar-contraer), ADR-0021 (autoriza `organization_id` NULLABLE en los
-- catalogos global + tenant), ADR-0023.
--
-- Propietario: modulo `resource`. Ningun otro modulo la lee ni la escribe: lo que `encounter`
-- (M14) necesita sale por `com.akine.resource.spi.MedicionDirectory` (AGENT.md seccion 4,
-- regla 1, verificada por ArchUnit).
--
-- POR QUE V51: el diseño de la etapa (docs/diseno/AKINE-06.03-examen-y-mediciones.md, seccion
-- 11) reserva V51 y V52 ANTES de escribir codigo, por el motivo que 02.07 y 03.01 pagaron
-- caro: las dos nacieron como V26, Flyway rechaza versiones duplicadas y la aplicacion no
-- arranca.
--
-- Migracion unica y no expandir-migrar-contraer, mismo motivo que V19, V20, V45, V47 y V49:
-- ADR-0007 exige el ciclo de tres para CAMBIOS sobre datos existentes. La tabla nace vacia.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE EL CATALOGO VIVE EN `resource` Y NO EN `encounter`
--
-- La intuicion lo pone en `encounter` —"es parte de la evaluacion"— y no lo es. Una
-- DEFINICION de medida es un catalogo: vive mas que cualquier sesion, la comparten todas las
-- especialidades y la plataforma siembra las universales (ROM de rodilla, Borg, EVA).
-- `resource` ya es dueño de `especialidad`, `practica`, `nomenclador` y `nomenclador_item`;
-- meter un sexto catalogo en otro modulo dejaria dos modulos dueños de la misma clase de cosa
-- y a `resource` sin la mitad de su catalogo.
--
-- ---------------------------------------------------------------------------------------
-- GLOBAL + POR TENANT, Y SE FILTRA POR `owner_key` — NUNCA POR `organization_id`
--
-- Es la trampa que 02.05 ya pago y que ADR-0021 documenta. En MySQL —como en el estandar—
-- varios NULL NO colisionan en un indice unico:
--
--   * `UNIQUE (organization_id, codigo)` a secas dejaria SIN PROTECCION a la poblacion mas
--     sensible: todas las filas globales tienen `organization_id IS NULL`, con lo que dos
--     definiciones globales homonimas no chocarian. El catalogo de plataforma —el unico que
--     ven todos los tenants a la vez— seria el unico sin proteccion contra duplicados.
--   * Filtrar con `organization_id IS NULL OR organization_id = :org` en una consulta
--     funciona, pero en un UNIQUE no hay forma de expresarlo, y ningun CHECK puede decir
--     "dos NULL son iguales".
--
-- Por eso el centinela numerico de V10 y V20: `owner_key = IFNULL(organization_id, 0)`. 0 no
-- es ni puede ser el id de ninguna organizacion —`organization.id` es AUTO_INCREMENT y
-- arranca en 1—, asi que TODAS las filas entran al unique y las globales se comparan entre
-- si. Los uniques y los indices empiezan por `owner_key` y no por `organization_id`: es la
-- MISMA columna con el NULL resuelto, asi que el alcance del tenant no se debilita.
--
-- Y el centinela de fecha de V18/V19/V20 para la baja logica, por el motivo inverso:
-- `UNIQUE (..., deleted_at)` protegeria el historico y desprotegeria lo vigente, porque todas
-- las filas VIGENTES tienen `deleted_at IS NULL`.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE ESTA TABLA NO TIENE `valid_from` / `valid_until`
--
-- Las cuatro tablas de V20 llevan DOS ejes temporales: ciclo de vida (baja logica) y VIGENCIA
-- (planificacion). Esta lleva solo el primero, y es deliberado. La vigencia existe en V20
-- porque RN-M06-003 necesita que un convenio referencie LA VERSION APLICABLE de un codigo:
-- un mismo codigo tiene varias filas con ventanas distintas. Aca no hay nada equivalente —una
-- definicion de medida no se "republica" por periodos— y agregar el eje obligaria a decidir
-- que significa registrar una medicion contra una definicion fuera de vigencia, pregunta que
-- ningun RF hace. Un test discontinuado se da de BAJA, que es lo que la etapa pide.
--
-- ---------------------------------------------------------------------------------------
-- EL VALOR ES TIPADO, Y EL TIPO SE DECIDE ACA
--
-- `tipo` fija que columna de `sesion_medicion` (V52) puede llevar valor. Los cuatro:
--
--   NUMERICO  -> valor_numerico. ROM en grados, perimetro en cm, fuerza en kg.
--   ESCALA    -> valor_numerico. EVA 0-10, Borg 6-20. Es numerico CON rango acotado, y se
--                distingue de NUMERICO porque la pantalla lo dibuja como escala y no como
--                campo libre; para la base los dos son el mismo DECIMAL.
--   TEXTO     -> valor_texto. "Marcha antalgica con claudicacion a los 50 m".
--   BOOLEANO  -> valor_booleano. Signo de Lasegue positivo/negativo.
--
-- `minimo`/`maximo` son DECIMAL y solo tienen sentido para los dos numericos: el CHECK lo
-- obliga. Son DECIMAL y jamas float, igual que en V20 y por el mismo motivo — un ROM de 92,5
-- guardado como binario flotante deja de ser comparable consigo mismo.
--
-- `unidad` es OBLIGATORIA en los dos tipos numericos y PROHIBIDA en los otros dos. Un numero
-- sin unidad no es una medicion: es un numero, y el dia que el catalogo pase de centimetros a
-- milimetros nadie puede saber que decia el de antes. `sesion_medicion` COPIA la unidad en la
-- fila justamente por eso (ver la cabecera de V52).
-- =====================================================================================

CREATE TABLE medicion_definicion
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT        NULL COMMENT 'Tenant propietario, o NULL si la definicion es GLOBAL de plataforma (ADR-0021). El NULL SIGNIFICA de plataforma, no falta el dato',
    owner_key           BIGINT AS (IFNULL(organization_id, 0)) STORED NOT NULL
        COMMENT 'Discriminador de duenio para los UNIQUE y para TODA consulta: el id del tenant, o el centinela 0 para lo global. Existe porque en MySQL varios NULL no colisionan, y sin el dos definiciones GLOBALES homonimas no chocarian. Se filtra por ESTA columna, nunca por organization_id',

    codigo              VARCHAR(48)   NOT NULL COMMENT 'Clave estable con la que se referencia la medida. No cambia nunca: renombrar es cambiar name, no codigo',
    name                VARCHAR(160)  NOT NULL COMMENT 'Nombre visible del test. La collation ai_ci hace que la unicidad sea insensible a mayusculas y acentos',
    descripcion         VARCHAR(280)  NULL COMMENT 'Como se toma la medida. Nunca contenido clinico de un paciente',

    tipo                VARCHAR(16)   NOT NULL COMMENT 'NUMERICO, ESCALA, TEXTO o BOOLEANO. Decide que columna de valor puede llevar sesion_medicion: ver la cabecera',
    unidad              VARCHAR(24)   NULL COMMENT 'Grados, cm, kg, puntos. OBLIGATORIA en NUMERICO y ESCALA, PROHIBIDA en TEXTO y BOOLEANO: un numero sin unidad no es una medicion',

    minimo              DECIMAL(10,3) NULL COMMENT 'Piso admitido al REGISTRAR. Solo en los tipos numericos. DECIMAL y jamas float',
    maximo              DECIMAL(10,3) NULL COMMENT 'Techo admitido al REGISTRAR, INCLUSIVE. El rango valida al registrar y NUNCA al leer: una medicion vieja no se vuelve invalida porque el catalogo estreche el rango',

    active              TINYINT(1)    NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida. 0 tras la baja logica: deja de admitir mediciones NUEVAS y las existentes siguen legibles y comparables (RN-M06-001, RN-M06-002). La baja NO cascadea',
    deleted_at          DATETIME(6)   NULL,
    deactivation_reason VARCHAR(280)  NULL COMMENT 'Motivo declarado de la baja. Obligatorio: sin el, la auditoria no responde por que seis meses despues',
    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Centinela de fecha para el UNIQUE: el instante de la baja, o 1970-01-01 mientras la fila este vigente. Sin el, el unique protegeria el historico y desprotegeria lo vigente',

    version             BIGINT        NOT NULL DEFAULT 0 COMMENT 'Control optimista, y ADEMAS lo que sesion_medicion copia como definicion_version: es lo que permite saber contra que redaccion del test se tomo una medicion vieja',
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,

    CONSTRAINT pk_medicion_definicion PRIMARY KEY (id),

    CONSTRAINT uk_medicion_definicion_codigo_vigente UNIQUE (owner_key, codigo, deleted_key),
    CONSTRAINT uk_medicion_definicion_name_vigente   UNIQUE (owner_key, name, deleted_key),

    CONSTRAINT fk_medicion_definicion_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT ck_medicion_definicion_tipo
        CHECK (tipo IN ('NUMERICO', 'ESCALA', 'TEXTO', 'BOOLEANO')),

    -- La unidad acompaña al numero o no existe. Las dos direcciones importan: un ROM sin
    -- unidad es incomparable, y un "Lasegue positivo" con unidad 'cm' es un dato incoherente
    -- que despues alguien grafica.
    CONSTRAINT ck_medicion_definicion_unidad
        CHECK ((tipo IN ('NUMERICO', 'ESCALA') AND unidad IS NOT NULL)
            OR (tipo IN ('TEXTO', 'BOOLEANO') AND unidad IS NULL)),

    -- Un rango sobre un texto o un booleano no significa nada.
    CONSTRAINT ck_medicion_definicion_rango_solo_numerico
        CHECK (tipo IN ('NUMERICO', 'ESCALA') OR (minimo IS NULL AND maximo IS NULL)),

    CONSTRAINT ck_medicion_definicion_rango_coherente
        CHECK (minimo IS NULL OR maximo IS NULL OR maximo >= minimo),

    CONSTRAINT ck_medicion_definicion_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- El selector de un formulario de examen: lo global mas lo propio, activo, por nombre.
    -- Con name adentro el ORDER BY sale del indice.
    INDEX ix_medicion_definicion_owner_estado (owner_key, active, name)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Definicion de una medida del examen fisico: que se mide, en que unidad y con que rango. Global de plataforma (organization_id NULL) o propia de un tenant. Propietario: modulo resource';
