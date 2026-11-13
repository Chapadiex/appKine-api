-- =====================================================================================
-- AKINE-02.05 — Especialidades, practicas y nomencladores (M06).
--
-- Trazabilidad: RF-M06-001 (administrar especialidad), RF-M06-002 (administrar practica),
-- RF-M06-003 (gestionar nomenclador y vigencias), RF-M06-004 (buscar practicas),
-- RF-M06-005 (solicitar alta de catalogo); RN-M06-001 (los catalogos usados historicamente
-- no se eliminan fisicamente), RN-M06-002 (la practica conserva su significado historico
-- aunque se de de baja), RN-M06-003 (los convenios referencian practicas y valores POR
-- VIGENCIA).
--
-- ADR-0003 (Flyway unica autoridad), ADR-0004 (persistencia multi-tenant),
-- ADR-0007 (expandir-migrar-contraer), ADR-0019 y ADR-0020 (lista de excepciones a
-- ADR-0004), y ADR-0021, que es el que autoriza que cuatro de estas cinco tablas tengan
-- `organization_id` NULLABLE.
--
-- Propietario de las cinco tablas: modulo `resource`. Ningun otro modulo las lee ni las
-- escribe: lo que `clinical` (M14) y `contracting` (M16) necesiten sale por
-- `com.akine.resource.spi.CatalogoDirectory` (AGENT.md seccion 4, regla 1).
--
-- ---------------------------------------------------------------------------------------
-- POR QUE ES UNA SOLA MIGRACION Y NO TRES
--
-- ADR-0007 exige expandir-migrar-contraer para CAMBIOS sobre datos existentes. Aca no hay
-- ninguno: las cinco tablas nacen vacias en esta release, no hay backfill que verificar y no
-- existe codigo viejo corriendo contra un esquema anterior. Mismo razonamiento que V19.
--
-- ---------------------------------------------------------------------------------------
-- EL CORAZON DE LA ETAPA: GLOBAL CONTRA CONTEXTUAL
--
-- Un catalogo clinico tiene DOS duenios posibles y el modelo tiene que poder decir cual:
--
--   * GLOBAL / de plataforma  -> `organization_id IS NULL`. "Kinesiologia" o el Nomenclador
--     Nacional no son de ningun centro: son la oferta comun que todos los tenants ven en su
--     selector. Los administra el administrador de plataforma, igual que el catalogo de
--     planes.
--   * CONTEXTUAL / del tenant -> `organization_id = <id>`. El concepto propio de un centro:
--     una practica que solo el hace, un nomenclador interno. Nadie mas lo ve.
--
-- `organization_id` NULLABLE es una excepcion a ADR-0004 y por lo tanto exige ADR-0021; un
-- comentario aca no alcanza, lo dice ADR-0019 al cerrar su lista. La forma elegida es la
-- misma que ADR-0019 ya admitio para `notification_outbox`: la columna existe, es nullable, y
-- el NULL SIGNIFICA algo —"de plataforma"— en vez de ser un hueco. No es el caso que ADR-0020
-- descarto (una columna NULL en el 100 % de las filas, que no documenta nada): aca las dos
-- poblaciones conviven en la misma tabla y el discriminador es el dato.
--
-- ---------------------------------------------------------------------------------------
-- LOS NULL EN LOS UNIQUE DE MySQL — QUINTA VEZ EN ESTE REPOSITORIO, Y AHORA POR DOS EJES
--
-- En MySQL —como en el estandar SQL— varios NULL NO colisionan en un indice unico. Esta
-- etapa se lo cruza DOS veces en la misma tabla, y las dos veces con signo contrario:
--
--   EJE 1 — el duenio. `UNIQUE (organization_id, codigo)` a secas dejaria de proteger
--   exactamente la poblacion mas sensible: TODAS las filas globales tienen
--   `organization_id IS NULL`, con lo que dos especialidades globales homonimas NO
--   colisionarian. El catalogo de plataforma —el unico que ven todos los tenants a la vez—
--   seria el unico sin proteccion contra duplicados.
--
--   EJE 2 — la baja logica. `UNIQUE (..., deleted_at)` es la inversion exacta de lo buscado,
--   y ya esta explicado en V19: todas las filas VIGENTES tienen `deleted_at IS NULL`, asi que
--   protegeria el historico y desprotegeria lo vigente.
--
-- Este repositorio tiene las tres formas y NO son intercambiables. Aca se usan las tres, cada
-- una donde corresponde:
--
--   * CENTINELA NUMERICO (forma de V10) para el duenio:
--         owner_key BIGINT AS (IFNULL(organization_id, 0)) STORED NOT NULL
--     0 no es ni puede ser el id de ninguna organizacion —`organization.id` es AUTO_INCREMENT
--     y arranca en 1—, asi que es un valor libre y estable para decir "plataforma". Con el,
--     TODAS las filas entran al unique y las globales se comparan entre si.
--     Se descarto el reflejo obvio, `UNIQUE (organization_id, ...)` con un CHECK aparte:
--     ningun CHECK puede expresar "dos NULL son iguales".
--
--   * CENTINELA DE FECHA (forma de V18/V19) para la baja:
--         deleted_key DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED
--     Dos filas VIGENTES homonimas comparten centinela, colisionan y producen 409. Una
--     vigente y una dada de baja no colisionan: el codigo y el nombre de un concepto dado de
--     baja se pueden reusar, que es lo que RN-M06-002 vuelve posible sin borrar historia.
--     Dos bajas del mismo codigo solo colisionan si ocurrieron en el MISMO microsegundo.
--
--   * NULL A PROPOSITO (forma de V12) en `catalogo_solicitud`, que es un caso distinto y
--     esta explicado en su propio bloque mas abajo.
--
-- Los uniques empiezan por `owner_key` y no por `organization_id`: el alcance del tenant no
-- se debilita —es la MISMA columna, con el NULL resuelto—, y es la unica forma de que el
-- indice sirva para las dos poblaciones.
--
-- IRREVERSIBILIDAD (riesgo, se anota y no se resuelve): una vez que exista un concepto dado
-- de baja y otro vigente con el mismo codigo y el mismo duenio, volver a un unique sin
-- `deleted_key` es imposible sin renombrar uno de los dos.
--
-- ---------------------------------------------------------------------------------------
-- LOS DOS EJES TEMPORALES, OTRA VEZ Y POR EL MISMO MOTIVO QUE EN V19
--
--   * active / deleted_at / deactivation_reason = CICLO DE VIDA. "Esta practica ya no se
--     ofrece". Irreversible, con motivo, decidida por una persona. Baja LOGICA: RN-M06-001 lo
--     exige de frente —"los catalogos utilizados historicamente no se eliminan fisicamente"—
--     y la regla maestra 10 lo generaliza.
--   * valid_from / valid_until = VIGENCIA. "Este codigo del nomenclador rige entre marzo y
--     diciembre". Es un dato de planificacion, no una baja, y es lo que RN-M06-003 necesita
--     para que un convenio pueda referenciar LA VIGENCIA APLICABLE y no "el codigo".
--
-- Un concepto se ofrece para una seleccion NUEVA en el instante T si y solo si:
--     active = 1 AND valid_from <= T AND (valid_until IS NULL OR T < valid_until)
--
-- Un concepto historico se RESUELVE siempre, este activo o no, este vigente o no: es la
-- mitad de RN-M06-002 que hace que una sesion de 2024 siga diciendo que practica fue.
--
-- ---------------------------------------------------------------------------------------
-- BUSQUEDA NORMALIZADA (RF-M06-004) — POR QUE NO HAY COLUMNA `nombre_normalizado`
--
-- La collation de estas tablas es utf8mb4_0900_ai_ci: accent-insensitive y case-insensitive.
-- "Kinesiologia", "kinesiologia" con tilde y "KINESIOLOGIA" son el MISMO valor para el motor,
-- tanto para el UNIQUE de duplicados como para el LIKE de la busqueda incremental. Una
-- columna generada con una normalizacion escrita a mano seria una segunda definicion de
-- "igual" que tarde o temprano diverge de la primera.
-- Lo que la collation NO normaliza —espacios al principio, al final y dobles espacios
-- internos— lo normaliza la aplicacion antes de escribir, en un solo lugar.
-- =====================================================================================


-- =====================================================================================
-- ESPECIALIDAD (RF-M06-001)
-- =====================================================================================
CREATE TABLE especialidad
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT       NULL COMMENT 'Tenant propietario, o NULL si la especialidad es GLOBAL de plataforma (ADR-0021). El NULL significa de plataforma, no falta el dato',
    owner_key           BIGINT AS (IFNULL(organization_id, 0)) STORED NOT NULL
        COMMENT 'Discriminador de duenio para los UNIQUE: el id del tenant, o el centinela 0 para lo global. Existe solo porque en MySQL varios NULL no colisionan, y sin el dos especialidades GLOBALES homonimas no chocarian',

    codigo              VARCHAR(48)  NOT NULL COMMENT 'Clave estable con la que los convenios y las sesiones referencian la especialidad. No cambia nunca: renombrar es cambiar name, no codigo',
    name                VARCHAR(160) NOT NULL COMMENT 'Nombre visible. Unico entre las especialidades VIGENTES del mismo duenio; la collation ai_ci hace que la comparacion sea insensible a mayusculas y acentos',
    descripcion         VARCHAR(280) NULL,

    valid_from          DATETIME(6)  NOT NULL COMMENT 'Instante UTC desde el que la especialidad se puede seleccionar. Eje de VIGENCIA, distinto del ciclo de vida de active/deleted_at',
    valid_until         DATETIME(6)  NULL COMMENT 'Instante UTC hasta el que se puede seleccionar, EXCLUSIVO. NULL = sin fin previsto, que es un estado real y no un valor faltante',

    active              TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida. 0 tras la baja logica: deja de ofrecerse pero sigue resolviendo para los historicos (RN-M06-001, RN-M06-002)',
    deleted_at          DATETIME(6)  NULL,
    deactivation_reason VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja. Obligatorio: sin el, la auditoria no responde por que seis meses despues',
    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Centinela de fecha para el UNIQUE: el instante de la baja, o 1970-01-01 mientras la fila este vigente. Ver la cabecera',

    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,

    CONSTRAINT pk_especialidad PRIMARY KEY (id),

    CONSTRAINT uk_especialidad_codigo_vigente UNIQUE (owner_key, codigo, deleted_key),
    CONSTRAINT uk_especialidad_name_vigente   UNIQUE (owner_key, name, deleted_key),

    CONSTRAINT fk_especialidad_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT ck_especialidad_vigencia_coherente
        CHECK (valid_until IS NULL OR valid_until > valid_from),

    CONSTRAINT ck_especialidad_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    INDEX ix_especialidad_owner_estado (owner_key, active, name),
    INDEX ix_especialidad_vigencia (owner_key, valid_from, valid_until)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Especialidad clinica, global de plataforma (organization_id NULL) o propia de un tenant (M06). Propietario: modulo resource';


-- =====================================================================================
-- PRACTICA (RF-M06-002, RF-M06-004)
--
-- La regla de alcance que la FK NO puede expresar: una practica GLOBAL solo puede colgar de
-- una especialidad GLOBAL. Si colgara de una especialidad de un tenant, ese tenant decidiria
-- por si solo el destino de un concepto que ven todos los demas, y dar de baja su
-- especialidad dejaria huerfana una practica de plataforma. Al reves si vale: una practica
-- CONTEXTUAL puede colgar de una especialidad global o de una del propio tenant.
-- Se valida en la aplicacion (409 catalogo-scope-mismatch) porque una FK compara ids, no
-- alcances.
-- =====================================================================================
CREATE TABLE practica
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT       NULL COMMENT 'Tenant propietario, o NULL si la practica es GLOBAL de plataforma (ADR-0021)',
    owner_key           BIGINT AS (IFNULL(organization_id, 0)) STORED NOT NULL
        COMMENT 'Centinela de duenio para los UNIQUE. Ver la cabecera de la migracion',

    especialidad_id     BIGINT       NOT NULL COMMENT 'Especialidad a la que pertenece la prestacion (RF-M06-002). Una practica GLOBAL solo puede colgar de una especialidad GLOBAL, y eso lo valida la aplicacion: una FK compara ids, no alcances',

    codigo              VARCHAR(48)  NOT NULL COMMENT 'Clave estable de la prestacion. Es lo que un convenio o una sesion guardan, y por eso no cambia nunca',
    name                VARCHAR(160) NOT NULL COMMENT 'Nombre visible de la prestacion. Es el campo sobre el que corre la busqueda incremental de RF-M06-004',
    descripcion         VARCHAR(280) NULL,

    valid_from          DATETIME(6)  NOT NULL,
    valid_until         DATETIME(6)  NULL COMMENT 'EXCLUSIVO. NULL = sin fin previsto',

    active              TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'RN-M06-002: una practica dada de baja conserva su significado historico. Deja de ofrecerse, nunca deja de resolver',
    deleted_at          DATETIME(6)  NULL,
    deactivation_reason VARCHAR(280) NULL,
    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL,

    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,

    CONSTRAINT pk_practica PRIMARY KEY (id),

    CONSTRAINT uk_practica_codigo_vigente UNIQUE (owner_key, codigo, deleted_key),
    CONSTRAINT uk_practica_name_vigente   UNIQUE (owner_key, especialidad_id, name, deleted_key),

    CONSTRAINT fk_practica_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_practica_especialidad
        FOREIGN KEY (especialidad_id) REFERENCES especialidad (id),

    CONSTRAINT ck_practica_vigencia_coherente
        CHECK (valid_until IS NULL OR valid_until > valid_from),

    CONSTRAINT ck_practica_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- Selector de practicas de una especialidad, que es la consulta de todo formulario
    -- clinico. Con name adentro el ORDER BY sale del indice.
    INDEX ix_practica_owner_especialidad (owner_key, especialidad_id, active, name),
    -- Busqueda incremental (RF-M06-004): filtra por duenio y estado y ordena por nombre.
    INDEX ix_practica_busqueda (owner_key, active, name),
    INDEX ix_practica_vigencia (owner_key, valid_from, valid_until)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Prestacion clinica asociada a una especialidad, global o contextual (M06). Propietario: modulo resource';


-- =====================================================================================
-- NOMENCLADOR (RF-M06-003) — el CONTENEDOR
--
-- El nomenclador es el sistema de codificacion —Nomenclador Nacional, NBU, el propio de un
-- financiador, o el interno de un centro—. No codifica nada por si mismo: lo que codifica
-- son sus ITEMS, cada uno con su vigencia. Separar contenedor de items es lo que permite que
-- el mismo codigo tenga historia sin duplicar el nomenclador entero por cada cambio.
-- =====================================================================================
CREATE TABLE nomenclador
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT       NULL COMMENT 'Tenant propietario, o NULL si el nomenclador es GLOBAL de plataforma (ADR-0021)',
    owner_key           BIGINT AS (IFNULL(organization_id, 0)) STORED NOT NULL,

    codigo              VARCHAR(48)  NOT NULL COMMENT 'Clave estable del nomenclador: NN, NBU, PMO. Es lo que un convenio guarda',
    name                VARCHAR(160) NOT NULL,
    descripcion         VARCHAR(280) NULL,

    valid_from          DATETIME(6)  NOT NULL,
    valid_until         DATETIME(6)  NULL,

    active              TINYINT(1)   NOT NULL DEFAULT 1,
    deleted_at          DATETIME(6)  NULL,
    deactivation_reason VARCHAR(280) NULL,
    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL,

    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,

    CONSTRAINT pk_nomenclador PRIMARY KEY (id),

    CONSTRAINT uk_nomenclador_codigo_vigente UNIQUE (owner_key, codigo, deleted_key),
    CONSTRAINT uk_nomenclador_name_vigente   UNIQUE (owner_key, name, deleted_key),

    CONSTRAINT fk_nomenclador_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT ck_nomenclador_vigencia_coherente
        CHECK (valid_until IS NULL OR valid_until > valid_from),

    CONSTRAINT ck_nomenclador_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    INDEX ix_nomenclador_owner_estado (owner_key, active, name)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Sistema de codificacion de prestaciones, global o contextual (M06). Propietario: modulo resource';


-- =====================================================================================
-- NOMENCLADOR_ITEM (RF-M06-003, RN-M06-003) — LA VIGENCIA
--
-- Aca vive la historia. Un mismo `codigo` dentro de un mismo nomenclador puede tener VARIAS
-- filas, cada una con su ventana [valid_from, valid_until) y su valor de referencia. Eso es
-- lo que RN-M06-003 llama "valores por vigencia" y lo que permite que un convenio de 2024
-- siga resolviendo el valor de 2024 despues de que el de 2026 lo reemplace.
--
-- POR QUE EL SOLAPAMIENTO NO ES UNA CONSTRAINT
--
-- "Dos vigencias del mismo codigo no se pisan" es una restriccion de EXCLUSION sobre rangos.
-- MySQL 8.4 no las tiene —son de PostgreSQL— y un UNIQUE no puede expresarla: dos ventanas
-- distintas que se solapan tienen valores distintos en toda columna. El UNIQUE de aca protege
-- solo el caso degenerado (dos vigencias que ARRANCAN en el mismo instante); el solapamiento
-- lo valida la aplicacion, y para que dos altas simultaneas no se cuelen entre la lectura y
-- la escritura, la transaccion toma primero un lock EXCLUSIVO sobre la fila del NOMENCLADOR
-- padre y despues consulta. El orden importa por partida doble:
--   * una lectura no bloqueante ANTES del lock fija el snapshot y el conteo posterior
--     devuelve datos anteriores al commit del competidor aunque el lock ya se haya tomado
--     (el lock serializa el ACCESO, no la VISIBILIDAD);
--   * bloquear el padre ANTES de insertar el hijo evita el deadlock simetrico de dos INSERT
--     que toman lock compartido sobre el padre y lo escalan a exclusivo al commit.
-- El orden de bloqueo de este modulo es, entonces: `nomenclador` -> `nomenclador_item`.
-- =====================================================================================
CREATE TABLE nomenclador_item
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT        NULL COMMENT 'Duenio, replicado del nomenclador padre: NULL si el nomenclador es global. Se guarda en la fila para que toda consulta pueda filtrar por tenant sin join, y la aplicacion exige que coincida con la del padre',
    owner_key           BIGINT AS (IFNULL(organization_id, 0)) STORED NOT NULL,

    nomenclador_id      BIGINT        NOT NULL,
    practica_id         BIGINT        NOT NULL COMMENT 'Prestacion que este codigo representa. Es el puente entre el catalogo clinico y el economico',

    codigo              VARCHAR(48)   NOT NULL COMMENT 'Codigo dentro del nomenclador: 27.01.01. Se repite entre vigencias del mismo concepto a proposito: cada fila es una VERSION de ese codigo',
    name                VARCHAR(160)  NOT NULL COMMENT 'Denominacion del codigo tal como la publica el nomenclador en esta vigencia. Se conserva por vigencia porque los renombres son parte de la historia (RN-M06-002)',
    descripcion         VARCHAR(280)  NULL,

    valor_referencia    DECIMAL(14,4) NULL COMMENT 'Valor o unidades que el nomenclador publica para este codigo en esta vigencia. NO es el arancel cobrable: el arancel lo fija el convenio (M16) y puede apartarse de este. Es DECIMAL y jamas float: el punto flotante acumula error y rompe el cierre de caja',

    valid_from          DATETIME(6)   NOT NULL COMMENT 'Inicio de la vigencia de esta version del codigo',
    valid_until         DATETIME(6)   NULL COMMENT 'Fin EXCLUSIVO. NULL = vigencia abierta. Dos vigencias del mismo codigo no pueden solaparse, y eso lo valida la aplicacion: ver la cabecera',

    active              TINYINT(1)    NOT NULL DEFAULT 1,
    deleted_at          DATETIME(6)   NULL,
    deactivation_reason VARCHAR(280)  NULL,
    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL,

    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,

    CONSTRAINT pk_nomenclador_item PRIMARY KEY (id),

    -- Caso degenerado del solapamiento: dos vigencias del mismo codigo que arrancan en el
    -- MISMO instante. Es el unico que un UNIQUE puede atrapar, y se pone porque es gratis.
    CONSTRAINT uk_nomenclador_item_vigencia
        UNIQUE (owner_key, nomenclador_id, codigo, valid_from, deleted_key),

    CONSTRAINT fk_nomenclador_item_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_nomenclador_item_nomenclador
        FOREIGN KEY (nomenclador_id) REFERENCES nomenclador (id),
    CONSTRAINT fk_nomenclador_item_practica
        FOREIGN KEY (practica_id) REFERENCES practica (id),

    CONSTRAINT ck_nomenclador_item_vigencia_coherente
        CHECK (valid_until IS NULL OR valid_until > valid_from),

    CONSTRAINT ck_nomenclador_item_valor_no_negativo
        CHECK (valor_referencia IS NULL OR valor_referencia >= 0),

    CONSTRAINT ck_nomenclador_item_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- La consulta de resolucion historica: "que decia este codigo el dia D". Es la que M16
    -- va a ejecutar por cada linea de un convenio.
    INDEX ix_nomenclador_item_resolucion (nomenclador_id, codigo, valid_from, valid_until),
    -- El camino inverso: "en que nomencladores figura esta practica".
    INDEX ix_nomenclador_item_practica (practica_id, active, valid_from)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Version vigente de un codigo dentro de un nomenclador (M06, RN-M06-003). Cada fila es una VIGENCIA: el historico nunca se pisa, se cierra. Propietario: modulo resource';


-- =====================================================================================
-- CATALOGO_SOLICITUD (RF-M06-005) — canalizar un concepto global que no existe
--
-- Un centro necesita una especialidad o una practica que el catalogo de plataforma no tiene.
-- No puede crearla el mismo como global —seria decidir por todos los tenants— asi que la
-- SOLICITA, y el administrador de plataforma la aprueba o la rechaza. Mientras tanto puede
-- crearla como concepto CONTEXTUAL suyo: la solicitud no lo bloquea.
--
-- ESTA TABLA SI LLEVA organization_id NOT NULL, y no es una inconsistencia: una solicitud
-- siempre la hace un tenant concreto. No hay solicitudes de plataforma.
--
-- LA TERCERA FORMA DEL PROBLEMA DE LOS NULL: aca se usa NULL A PROPOSITO, la forma de V12, y
-- no el centinela de las cuatro tablas de arriba. Lo que hay que impedir es que el mismo
-- centro pida DOS VECES lo mismo mientras la primera sigue pendiente —que es lo que produce
-- un reintento por timeout, y lo que CA-M06-005-05 exige que no duplique efectos—. Lo que NO
-- hay que impedir es volver a pedir algo que ya fue rechazado: eso es legitimo, con
-- argumentos nuevos. Con un centinela las solicitudes resueltas seguirian dentro del unique y
-- bloquearian el segundo pedido para siempre; con NULL salen solas del indice en cuanto se
-- resuelven. Es exactamente el caso de `membership_grant.grant_activo`.
-- =====================================================================================
CREATE TABLE catalogo_solicitud
(
    id                        BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id           BIGINT       NOT NULL COMMENT 'Tenant que solicita. NOT NULL sin excepcion: no existen solicitudes sin tenant, asi que ADR-0021 no la alcanza',
    consultorio_id            BIGINT       NULL COMMENT 'Sede desde la que se pidio, si la habia en el contexto. Es trazabilidad, no alcance: la solicitud es de la organizacion',

    tipo                      VARCHAR(24)  NOT NULL COMMENT 'Que concepto global se pide',
    nombre_propuesto          VARCHAR(160) NOT NULL,
    codigo_propuesto          VARCHAR(48)  NULL COMMENT 'Codigo sugerido. Opcional: el centro propone, la plataforma dispone',
    justificacion             VARCHAR(500) NOT NULL COMMENT 'Por que hace falta. Obligatoria: sin ella la plataforma no puede decidir y la solicitud es ruido',

    estado                    VARCHAR(16)  NOT NULL DEFAULT 'PENDIENTE',
    solicitada_por_account_id BIGINT       NOT NULL,
    resuelta_por_account_id   BIGINT       NULL,
    resuelta_at               DATETIME(6)  NULL,
    resolucion_nota           VARCHAR(500) NULL COMMENT 'Motivo de la aprobacion o del rechazo. Obligatorio al resolver',
    concepto_id               BIGINT       NULL COMMENT 'Id del concepto GLOBAL creado al aprobar, si se creo. Sin FK: apunta a una de tres tablas segun tipo, y una FK solo puede apuntar a una',

    nombre_pendiente          VARCHAR(160) AS (IF(estado = 'PENDIENTE', nombre_propuesto, NULL)) STORED
        COMMENT 'Discriminador del unique de duplicados: vale el nombre mientras la solicitud esta PENDIENTE y NULL en cuanto se resuelve. Es la forma de V12 —NULL a proposito— y aca es la correcta: pedir de nuevo algo ya rechazado es legitimo, pedir dos veces lo mismo mientras esta pendiente no',

    version                   BIGINT       NOT NULL DEFAULT 0,
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NOT NULL,

    CONSTRAINT pk_catalogo_solicitud PRIMARY KEY (id),

    CONSTRAINT uk_catalogo_solicitud_pendiente
        UNIQUE (organization_id, tipo, nombre_pendiente),

    CONSTRAINT fk_catalogo_solicitud_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_catalogo_solicitud_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT ck_catalogo_solicitud_tipo
        CHECK (tipo IN ('ESPECIALIDAD', 'PRACTICA', 'NOMENCLADOR')),

    CONSTRAINT ck_catalogo_solicitud_estado
        CHECK (estado IN ('PENDIENTE', 'APROBADA', 'RECHAZADA')),

    -- Los campos de resolucion se mueven juntos o no se mueven. Sin esto es posible una
    -- solicitud APROBADA sin quien ni cuando, que nadie puede explicar al leerla.
    CONSTRAINT ck_catalogo_solicitud_resolucion_coherente
        CHECK ((estado = 'PENDIENTE'
                    AND resuelta_por_account_id IS NULL
                    AND resuelta_at IS NULL
                    AND resolucion_nota IS NULL)
            OR (estado <> 'PENDIENTE'
                    AND resuelta_por_account_id IS NOT NULL
                    AND resuelta_at IS NOT NULL
                    AND resolucion_nota IS NOT NULL)),

    INDEX ix_catalogo_solicitud_bandeja (estado, created_at),
    INDEX ix_catalogo_solicitud_tenant (organization_id, estado, created_at)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Pedido de un tenant para que la plataforma incorpore un concepto GLOBAL al catalogo (M06, RF-M06-005). Propietario: modulo resource';
