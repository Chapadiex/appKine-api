-- =====================================================================================
-- AKINE-02.06 — Servicio global y Oferta de servicio por consultorio (M27 / M06 / M03).
--
-- Trazabilidad: RF-M03-006 (configurar los servicios que ofrece un consultorio), RF-M03-007
-- (valores operativos por defecto), RF-M06-006 (naturaleza y modalidad del Servicio),
-- RF-M06-007 (Servicio distinto de Practica), RF-M06-008 (una oferta clinica puede usar
-- Practicas durante la atencion — se difiere, ver abajo), RF-M27-001 (crear Servicio),
-- RF-M27-002 (editar y dar de baja Servicio sin borrado fisico), RF-M27-003 (crear Oferta);
-- RN-M03-006..007, RN-M06-004..006, RN-M27-001..008; reglas maestras 13-20 y 27.
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0007 (expandir-migrar-contraer), ADR-0023 (tablas globales sin organization_id: la
-- lista consolidada de excepciones a ADR-0004, que supersede a ADR-0019, 0020, 0021 y 0022).
--
-- Propietario de las dos tablas: modulo `offering`, que nace en esta etapa. Ningun otro
-- modulo las lee ni las escribe (AGENT.md seccion 4, regla 1). `offering` depende de
-- `organization.spi` y de `platform.spi.audit`, y nadie depende de `offering`.
--
-- Migracion unica y no expandir-migrar-contraer, por el mismo motivo que V19: ADR-0007 exige
-- el ciclo de tres para CAMBIOS sobre datos existentes. Estas dos tablas nacen vacias en esta
-- release, no hay backfill que verificar y no hay codigo viejo corriendo contra un esquema
-- anterior. Una tabla nueva se crea con sus constraints definitivas desde el minuto cero.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE `servicio` NO LLEVA organization_id NI owner_key
--
-- No hay dos poblaciones que discriminar. `especialidad` y `practica` (V20) llevan owner_key
-- porque conviven conceptos globales y conceptos propios de un tenant en la misma tabla, y en
-- MySQL varios NULL no colisionan en un unique: sin el centinela, dos conceptos globales
-- homonimos no chocarian y la poblacion que ven todos los tenants seria la unica sin proteger.
--
-- Un Servicio es siempre global: RN-M27-001 lo dice —"un Servicio es catalogo global/
-- conceptual"— y la seccion 30.6 lista sus campos sin incluir organizationId, a diferencia de
-- especialidad y practica. Tampoco existe ningun RF de solicitud de alta para Servicio, como
-- si existe RF-M06-005 para el catalogo clinico. Con una sola poblacion, el centinela owner_key
-- no distinguiria nada: seria el patron de V20 sin el problema que lo motiva, y un lector
-- futuro se preguntaria con razon para que sirve una columna generada que nunca discrimina.
-- Es exactamente el razonamiento que ADR-0022 hizo para `feriado`.
--
-- La configuracion propia de cada centro NO va aca: va en la Oferta. Esa es la regla maestra
-- 14 y el objetivo entero de esta etapa —el Servicio define el concepto, la Oferta define como
-- lo presta un consultorio concreto—. Duplicar la capacidad contextual en el catalogo daria
-- dos formas de hacer lo mismo.
--
-- `deleted_key` SI hace falta, por el motivo de siempre (V18/V19/V20): RF-M27-002 prohibe el
-- borrado fisico, asi que un codigo o un nombre dado de baja tiene que poder reusarse sin que
-- el unique lo impida y sin borrar la fila vieja.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE `oferta_servicio_consultorio` NO SE PARECE AL CATALOGO SINO A `espacio`
--
-- organization_id NOT NULL: toda oferta pertenece a una sede real y no existe la "oferta
-- global". Por eso NO lleva owner_key —no tiene el problema que owner_key resuelve, porque no
-- hay filas con organization_id NULL— y todos sus indices y uniques empiezan por
-- organization_id (ADR-0004, AGENT.md seccion 5), aunque consultorio_id ya determine el tenant
-- por la FK: un unique sin la columna de tenant es un bug de aislamiento aunque hoy sea
-- redundante.
--
-- SI lleva el centinela `deleted_key`, el CENTINELA DE FECHA de V18/V19: el instante de la
-- baja, o 1970-01-01 mientras la fila este activa. Con el, dos ofertas ACTIVAS de la misma sede
-- no pueden compartir nombre comercial —el caso que importa— y un nombre comercial se puede
-- reusar despues de una baja logica. El reflejo `UNIQUE (..., nombre_comercial, deleted_at)`
-- esta ROTO: todas las filas vigentes tienen deleted_at NULL, varios NULL no colisionan, y el
-- unique terminaria protegiendo el historico y desprotegiendo lo vigente.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE GRUPAL EXIGE capacidad > 1 Y NO > 0
--
-- RF-M27-003 dice "mayor a cero". Una oferta grupal de capacidad 1 es una individual mal
-- rotulada, y el motor de inscripciones de F2 la trataria como un grupo de una persona: una
-- clase con cupo uno, con lista de espera y con asistencia de grupo, para un unico paciente.
-- El check mas estricto es la lectura util de la regla.
--
-- DECISION REVISABLE: aflojarlo a "> 0" es borrar ck_oferta_grupal_capacidad y nada mas. No
-- hay codigo, indice ni columna que dependa de el, y ninguna fila existente lo violaria.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE precio_base Y moneda VIAJAN JUNTOS
--
-- Un precio sin moneda no es un precio, y este SaaS va a operar en mas de un pais: 4500 no
-- significa nada hasta saber si son pesos o dolares, y el dia que exista una segunda moneda
-- todas las filas viejas serian ambiguas sin forma de resolverlas. Los dos son nullable porque
-- una oferta sin precio declarado es un estado real —RN-M27-006: las reglas economicas dependen
-- de convenios y aranceles vigentes, que llegan con M15/M16/M18—, pero es "sin precio", nunca
-- "con precio de moneda desconocida". El CHECK exige los dos o ninguno.
--
-- `esquema_cobro` es un dato DECLARADO, NO RESUELTO. M15, M16 y M18 no existen. En esta etapa
-- se guarda y se muestra; nadie lo interpreta. Por eso es VARCHAR nullable sin lista cerrada:
-- fijar el enum ahora seria adivinar el vocabulario de un modulo que nadie escribio.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE NO SE MODELA, Y POR QUE
--
--   * Ninguna FK a Turno ni a Sesion: no existen los modulos `scheduling` ni `clinical`. La
--     regla maestra 27 es una restriccion sobre el ORDEN de construccion futuro, no sobre el
--     codigo presente. No hay ninguna FK que escribir hoy.
--   * Ninguna tabla puente Oferta-Practica (RF-M06-008): la practica se usa "durante la
--     atencion", que es un hecho de `clinical`. Modelarla ahora seria inventar la forma de un
--     vinculo cuyo consumidor nadie escribio. La costura se declara y se difiere.
--   * Ninguna habilitacion de profesionales ni espacios por oferta: RF-M03-006 paso 7 lo pide
--     "cuando corresponda", y requiere_profesional / requiere_espacio capturan la intencion sin
--     materializar dos tablas puente que solo la agenda va a consumir.
--
-- ---------------------------------------------------------------------------------------
-- LA BAJA DE UN SERVICIO GLOBAL NO CASCADEA
--
-- La FK fk_oferta_servicio es RESTRICT por defecto y ninguna clausula ON DELETE la afloja: el
-- borrado fisico de un servicio referenciado es imposible desde la base, que es lo que
-- RF-M27-002 pide. La baja LOGICA de un servicio deja intactas las ofertas vigentes de todos
-- los centros que lo referencian (RN-M03-006: no afectar historicos); lo que se impide es crear
-- ofertas NUEVAS sobre un servicio inactivo, y eso lo valida la aplicacion, no un CHECK: la
-- base no puede expresar "solo al insertar".
-- =====================================================================================

CREATE TABLE servicio
(
    id                              BIGINT       NOT NULL AUTO_INCREMENT,

    -- Sin organization_id NI owner_key. Excepcion declarada a ADR-0004 en ADR-0023. Ver la
    -- cabecera: una sola poblacion, asi que el centinela no distinguiria nada.

    codigo                          VARCHAR(64)  NOT NULL COMMENT 'Codigo estable del servicio en el catalogo global. Unico entre los servicios vigentes de la plataforma',
    nombre                          VARCHAR(160) NOT NULL COMMENT 'Nombre del concepto tal como lo ve todo tenant en su selector. Unico entre los vigentes',
    descripcion                     VARCHAR(500) NULL COMMENT 'Descripcion del concepto. Nunca contenido clinico de nadie: esta tabla es global',

    naturaleza                      VARCHAR(24)  NOT NULL COMMENT 'Clasificacion del servicio (RF-M06-006). RN-M06-005: sirve para CLASIFICAR y no debe imponer por si sola comportamiento clinico. Lo que decide el comportamiento son requiere_* y modalidad de la Oferta',
    modalidad_default               VARCHAR(16)  NOT NULL COMMENT 'Modalidad sugerida al crear una Oferta. Es una PROPUESTA INICIAL, no una regla: RF-M06-006 dice que los defaults no reemplazan la configuracion concreta de cada Oferta',
    requiere_caso_clinico_default   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Sugerencia de si la atencion abre Caso Clinico. Propuesta inicial, la Oferta manda',
    genera_registro_clinico_default TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Sugerencia de si la atencion genera registro clinico. Propuesta inicial, la Oferta manda',

    active                          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida administrativo. 0 tras la baja logica: RF-M27-002 impide nuevas altas de Oferta sobre el, y las ofertas ya creadas siguen operando',
    deleted_at                      DATETIME(6)  NULL COMMENT 'Instante UTC de la baja logica. NULL mientras el servicio este activo',
    deactivation_reason             VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja. Obligatorio: sin el, la auditoria no responde por que seis meses despues',

    deleted_key                     DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador de los uniques de codigo y nombre: el instante de la baja, o el centinela 1970-01-01 mientras el servicio este activo. Existe solo para que el unique funcione: en MySQL varios NULL no colisionan, asi que un unique sobre deleted_at a secas dejaria de proteger justo el caso que importa, dos servicios activos homonimos',

    version                         BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Una version vieja produce 409 conflict en vez de pisar el cambio ajeno en silencio',
    created_at                      DATETIME(6)  NOT NULL,
    updated_at                      DATETIME(6)  NOT NULL,

    CONSTRAINT pk_servicio PRIMARY KEY (id),

    CONSTRAINT uk_servicio_codigo_vigente UNIQUE (codigo, deleted_key),
    CONSTRAINT uk_servicio_nombre_vigente UNIQUE (nombre, deleted_key),

    -- Listas cerradas. Mismo criterio que espacio.tipo (V19): el conjunto es del producto y no
    -- del tenant, asi que no hay tabla de catalogo. RN-M06-005 y la regla maestra 15 exigen
    -- ademas que ninguna condicion del sistema se decida POR NOMBRE: estas columnas clasifican,
    -- no habilitan.
    CONSTRAINT ck_servicio_naturaleza
        CHECK (naturaleza IN ('CLINICO', 'TERAPEUTICO', 'PREVENTIVO', 'BIENESTAR')),
    CONSTRAINT ck_servicio_modalidad_default
        CHECK (modalidad_default IN ('INDIVIDUAL', 'GRUPAL')),

    -- Coherencia de la baja logica: los tres campos se mueven juntos o no se mueven. Sin esto
    -- es posible active = 0 con deleted_at NULL, que ademas rompe el centinela del unique
    -- porque la fila cae en 1970 junto con las activas.
    CONSTRAINT ck_servicio_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- El selector del catalogo global: filtra por estado y ordena por nombre. Con nombre
    -- adentro el ORDER BY sale del indice.
    INDEX ix_servicio_estado_nombre (active, nombre)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Servicio del catalogo GLOBAL de la plataforma (M27/M06). Sin organization_id: excepcion declarada a ADR-0004 en ADR-0023. Define QUE existe como concepto; COMO lo presta un centro concreto vive en oferta_servicio_consultorio. Propietario: modulo offering';


CREATE TABLE oferta_servicio_consultorio
(
    id                      BIGINT         NOT NULL AUTO_INCREMENT,

    organization_id         BIGINT         NOT NULL COMMENT 'Tenant propietario. Toda tabla de negocio lo lleva (AGENT.md seccion 5) y todo unique e indice de esta tabla empieza por el. No existe la oferta global: por eso NOT NULL y por eso sin owner_key',
    consultorio_id          BIGINT         NOT NULL COMMENT 'Sede que presta la oferta (RN-M03-006). Una oferta sin sede no existe: la configuracion de que se ofrece es de la sede, no de la organizacion',
    servicio_id             BIGINT         NOT NULL COMMENT 'Concepto global que esta oferta materializa (regla maestra 14). La FK es RESTRICT: el borrado fisico de un servicio referenciado es imposible desde la base, que es lo que RF-M27-002 pide',

    nombre_comercial        VARCHAR(160)   NOT NULL COMMENT 'Como llama el centro a esta oferta en su cartelera y en el turnero. Puede diferir del nombre del Servicio: "Kinesiologia deportiva vespertina" sobre el servicio "Kinesiologia". Unico entre las ofertas VIGENTES de la sede',
    descripcion             VARCHAR(500)   NULL COMMENT 'Descripcion comercial que ve el paciente. Nunca contenido clinico',

    modalidad               VARCHAR(16)    NOT NULL COMMENT 'INDIVIDUAL o GRUPAL (RF-M06-006). Se copia del modalidad_default del Servicio al crear, pero manda esta: RF-M06-006 dice que los defaults no reemplazan la configuracion de la Oferta',
    duracion_minutos        INT            NOT NULL COMMENT 'Duracion de una atencion de esta oferta. La consumira la agenda de F5 para dimensionar el bloque',
    capacidad               INT            NOT NULL COMMENT 'Cuantas personas admite simultaneamente. 1 para una oferta individual; mayor a 1 obligatorio si la modalidad es GRUPAL (ver la cabecera)',

    precio_base             DECIMAL(12, 2) NULL COMMENT 'Precio de lista de la oferta. NULL = sin precio declarado, que es un estado real mientras M15/M16/M18 no existan. Viaja siempre con moneda: nunca hay precio sin moneda',
    moneda                  CHAR(3)        NULL COMMENT 'ISO 4217 del precio_base. NULL si y solo si precio_base es NULL. Existe desde el dia uno para no migrar datos el dia que un centro opere en otro pais',
    esquema_cobro           VARCHAR(32)    NULL COMMENT 'Esquema economico declarado por el centro (RN-M27-006). DATO DECLARADO, NO RESUELTO: en esta etapa se guarda y se muestra, nadie lo interpreta. Sin lista cerrada a proposito, porque el vocabulario lo fija M16/M18 y esos modulos no existen',

    admite_obra_social      TINYINT(1)     NOT NULL DEFAULT 0 COMMENT 'Si la oferta se puede facturar a un financiador. Declarativo hasta que exista M15',

    requiere_caso_clinico   TINYINT(1)     NOT NULL COMMENT 'Si la atencion de esta oferta abre o exige un Caso Clinico. Sin DEFAULT: al crear se copia del Servicio y quien crea decide explicitamente, que es lo que RF-M03-007 pide',
    genera_registro_clinico TINYINT(1)     NOT NULL COMMENT 'Si la atencion genera registro clinico. Reglas maestras 17 y 19: esta etapa NO crea ningun registro clinico, solo declara si esta oferta lo requeriria',
    requiere_profesional    TINYINT(1)     NOT NULL COMMENT 'Si la atencion necesita un profesional asignado. Captura la intencion de RF-M03-006 paso 7 sin materializar una tabla puente que solo la agenda va a consumir',
    requiere_espacio        TINYINT(1)     NOT NULL COMMENT 'Si la atencion necesita un espacio fisico asignado (M04). Misma logica que requiere_profesional',

    vigencia_desde          DATE           NOT NULL COMMENT 'Fecha desde la que la oferta se puede reservar. Ventana OPERATIVA, distinta del ciclo de vida administrativo de active/deleted_at — misma separacion que espacio (V19) y profesional_disponibilidad (V23)',
    vigencia_hasta          DATE           NULL COMMENT 'Fecha hasta la que se puede reservar, EXCLUSIVA. NULL significa sin fin previsto, que es un estado real y no un valor faltante',

    active                  TINYINT(1)     NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida administrativo. 0 tras la baja logica: RN-M27-007, la oferta inactiva no admite reservas nuevas y conserva sus historicos',
    deleted_at              DATETIME(6)    NULL COMMENT 'Instante UTC de la baja logica. NULL mientras la oferta este activa',
    deactivation_reason     VARCHAR(280)   NULL COMMENT 'Motivo declarado de la baja. Obligatorio al dar de baja',

    deleted_key             DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique de nombre comercial: el instante de la baja, o el centinela 1970-01-01 mientras la oferta este activa. Sin el, dos ofertas activas homonimas de la misma sede no colisionarian, porque en MySQL varios NULL no colisionan en un unique',

    version                 BIGINT         NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Una version vieja produce 409 conflict en vez de pisar el cambio ajeno en silencio',
    created_at              DATETIME(6)    NOT NULL,
    updated_at              DATETIME(6)    NOT NULL,

    CONSTRAINT pk_oferta_servicio_consultorio PRIMARY KEY (id),

    CONSTRAINT uk_oferta_sede_nombre_vigente
        UNIQUE (organization_id, consultorio_id, nombre_comercial, deleted_key),

    CONSTRAINT fk_oferta_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_oferta_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_oferta_servicio
        FOREIGN KEY (servicio_id) REFERENCES servicio (id),

    CONSTRAINT ck_oferta_modalidad
        CHECK (modalidad IN ('INDIVIDUAL', 'GRUPAL')),

    -- Mismo criterio que espacio.capacidad (V19): cero no es "menos cupo", es una baja sin
    -- motivo ni rastro, y la baja ya tiene sus tres columnas.
    CONSTRAINT ck_oferta_duracion_positiva
        CHECK (duracion_minutos > 0),
    CONSTRAINT ck_oferta_capacidad_positiva
        CHECK (capacidad > 0),

    -- DECISION REVISABLE. Ver la cabecera: RF-M27-003 dice "mayor a cero", pero una oferta
    -- GRUPAL de capacidad 1 es una individual mal rotulada. Para aflojarlo a "> 0" se borra
    -- este CHECK y nada mas: nada depende de el.
    CONSTRAINT ck_oferta_grupal_capacidad
        CHECK (modalidad <> 'GRUPAL' OR capacidad > 1),

    -- Coherencia temporal. El limite superior es EXCLUSIVO, asi que vigencia_hasta =
    -- vigencia_desde tampoco es valido: seria una oferta reservable durante cero dias.
    CONSTRAINT ck_oferta_vigencia_coherente
        CHECK (vigencia_hasta IS NULL OR vigencia_hasta > vigencia_desde),

    -- Precio y moneda viajan juntos o no viajan. Ver la cabecera.
    CONSTRAINT ck_oferta_precio_con_moneda
        CHECK ((precio_base IS NULL AND moneda IS NULL)
            OR (precio_base IS NOT NULL AND moneda IS NOT NULL)),

    -- Coherencia de la baja logica: los tres campos se mueven juntos o no se mueven.
    CONSTRAINT ck_oferta_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- Listado de la pantalla de ofertas de la sede y del selector: filtra por sede y estado,
    -- ordena por nombre comercial. Con el nombre adentro el ORDER BY sale del indice.
    INDEX ix_oferta_sede_estado (organization_id, consultorio_id, active, nombre_comercial),

    -- "Que ofertas de esta sede materializan tal servicio": la consulta que necesita la baja de
    -- un servicio global para saber a quien afecta, y la que usara la agenda de F5.
    INDEX ix_oferta_sede_servicio (organization_id, consultorio_id, servicio_id)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Como una sede concreta presta un Servicio global (M27/M03, regla maestra 14). Lleva organization_id NOT NULL: no existe la oferta global. La habilitacion de profesionales y espacios por oferta (RF-M03-006 paso 7) y el vinculo con Practicas (RF-M06-008) se difieren a las etapas de agenda y clinical. Propietario: modulo offering';
