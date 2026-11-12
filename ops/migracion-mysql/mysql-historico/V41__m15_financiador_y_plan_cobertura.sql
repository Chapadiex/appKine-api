-- =====================================================================================
-- AKINE-03.03 — Financiadores y planes de cobertura (M15).
--
-- Trazabilidad: RF-M15-001 (crear financiador), RF-M15-002 (editar financiador),
-- RF-M15-003 (dar de baja financiador), RF-M15-004 (crear plan), RF-M15-005 (editar y
-- finalizar vigencia de plan), RF-M15-006 (buscar financiador);
-- RN-M15-001 (un plan pertenece a un financiador), RN-M15-002 (los planes inactivos no se
-- ofrecen en altas nuevas), RN-M15-003 (no se eliminan historicos), RN-M15-004 (PARTICULAR
-- es una modalidad y NO un financiador — ver abajo); reglas maestras 9, 10 y 11.
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0007 (expandir-migrar-contraer).
--
-- Propietario de las dos tablas: modulo `contracting`, que nace en esta etapa (AGENT.md §4
-- lo declara como el modulo de M15-M17). Ningun otro modulo las lee ni las escribe: los
-- consumidores previstos —M08 coberturas del paciente y M16 convenios— llegan por
-- `contracting.spi`.
--
-- Migracion unica y no expandir-migrar-contraer, mismo motivo que V24: ADR-0007 exige el
-- ciclo de tres para CAMBIOS sobre datos existentes. Estas dos tablas nacen vacias, no hay
-- backfill que verificar y no hay codigo viejo corriendo contra un esquema anterior.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE ES DE LA ORGANIZACION Y NO GLOBAL, Y QUE QUEDA SIN IMPLEMENTAR POR ESA DECISION
--
-- `financiador` lleva organization_id NOT NULL: la obra social con la que un centro trabaja
-- —con su codigo interno, su contacto y su condicion— es dato de ese centro. Dos centros
-- pueden tener "OSDE" con datos distintos y ninguno ve el del otro.
--
-- La matriz de permisos §3 define el valor "Catalogo global" de la fila Administrar
-- Convenios como "solo sobre el catalogo de plataforma (financiadores/planes globales)".
-- ESE CATALOGO GLOBAL NO EXISTE EN 03.03 y esta etapa no lo crea. La consecuencia esta
-- declarada, no escondida: PLATFORM_ADMIN no recibe asignacion base de `convenio:manage`
-- porque no hay ninguna fila global que administrar, y su celda de la matriz queda sin
-- cumplirse hasta que exista. La razon de no adelantarlo es la misma que bloquea RF-M06-005
-- desde 02.05: la consola de plataforma no se puede construir porque ningun endpoint le dice
-- al frontend si quien mira tiene rol de plataforma.
--
-- Si un dia se agrega la poblacion global, el camino es el de `especialidad` y `practica`
-- (V20, ADR-0021): organization_id pasa a NULL-able y aparece el centinela `owner_key`
-- generado como IFNULL(organization_id, 0), porque en MySQL varios NULL no colisionan en un
-- unique y sin el centinela la poblacion global seria la unica desprotegida. Mientras haya
-- una sola poblacion ese centinela no discriminaria nada, que es el razonamiento de ADR-0022
-- y ADR-0023 y por eso no se pone hoy.
--
-- ---------------------------------------------------------------------------------------
-- PARTICULAR NO ES UNA FILA DE ESTA TABLA (RN-M15-004)
--
-- "PARTICULAR es una modalidad siempre disponible aunque no sea financiador externo".
-- Sembrar un financiador llamado PARTICULAR por organizacion lo convertiria en un dato
-- borrable, renombrable y duplicable, y ademas obligaria a que alguna decision del sistema
-- dependa de un NOMBRE — exactamente lo que la regla maestra 15 prohibe. La cobertura
-- particular se modela en M08 como la AUSENCIA de plan financiado, no como un plan especial.
-- Esta migracion, deliberadamente, no siembra ninguna fila.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE NO HAY NINGUNA REGLA DE NO-SOLAPAMIENTO DE VIGENCIAS, Y POR QUE ESO ESTA BIEN
--
-- Un financiador tiene VARIOS planes vigentes al mismo tiempo: 210, 310 y 450 de la misma
-- prepaga conviven, y esa convivencia es el caso normal, no una anomalia. La vigencia de un
-- plan acota CUANDO se lo puede elegir, no un turno de exclusividad entre planes.
--
-- Por eso esta migracion no intenta expresar no-solapamiento con ningun indice —MySQL 8.4 no
-- tiene exclusion constraints y ningun UNIQUE puede decir que dos intervalos se pisan: 09:00
-- a 10:00 y 09:30 a 10:00 no comparten ningun valor de columna— y tampoco necesita la
-- fila-lock que M05 y M12 usan para serializar escrituras. Si M16 (convenios) llega a
-- necesitar que dos convenios del mismo financiador no se solapen, esa regla la va a hacer
-- cumplir un lock tomado en READ_COMMITTED, nunca un indice, y va a vivir en su propia
-- migracion.
--
-- Lo unico temporal que esta tabla si expresa es coherencia interna de la fila:
-- ck_plan_vigencia_coherente.
--
-- ---------------------------------------------------------------------------------------
-- VIGENCIA_HASTA ES INCLUSIVA, Y SE DECLARA PORQUE V24 DEJO UNA AMBIGUEDAD
--
-- La cabecera de V24 llama "EXCLUSIVA" a oferta.vigencia_hasta y su propio codigo Java la
-- evalua INCLUSIVA (`OfertaSnapshot.vigenteEl` usa !fecha.isAfter(vigenciaHasta)). No se
-- repite la ambiguedad: aca `vigencia_hasta` es el ULTIMO DIA en que el plan se puede
-- elegir, y por eso ck_plan_vigencia_coherente admite vigencia_hasta = vigencia_desde —un
-- plan que vale un solo dia es un estado real—. El codigo Java (PlanCobertura.vigenteEl) usa
-- exactamente el mismo criterio y su test lo fija.
--
-- ---------------------------------------------------------------------------------------
-- LAS REFERENCIAS HISTORICAS NO SE PROTEGEN DESDE ACA, Y ESTO ES LO MAS IMPORTANTE
--
-- El requisito de la etapa —"una cobertura firmada ayer no puede cambiar porque hoy alguien
-- edito el plan"— NO se cumple con una columna de esta tabla. Se cumple porque el consumidor
-- (M08, M16) COPIA a sus propias columnas la referencia congelada que le entrega
-- `contracting.spi.ReferenciaDeCobertura`, igual que `obligacion` congela el precio de la
-- oferta en vez de leerlo vivo al mostrar la deuda (AKINE-07.01).
--
-- Lo que esta migracion aporta a esa garantia es la mitad estructural:
--
--   * `codigo` es la clave estable y la aplicacion la declara inmutable. Renombrar es
--     cambiar `nombre`, nunca `codigo`, para que una referencia vieja no pase a significar
--     otra cosa. Mismo criterio que servicio.codigo (V24).
--   * No hay borrado fisico: RN-M15-003. Las FK son RESTRICT por defecto y la baja es
--     logica, asi que la fila referenciada siempre resuelve.
--
-- La otra mitad —que el consumidor copie en vez de leer— no la puede imponer el esquema, y
-- por eso el spi entrega un record de copia y no un puntero.
--
-- ---------------------------------------------------------------------------------------
-- LA BAJA DE UN FINANCIADOR NO CASCADEA
--
-- Mismo criterio que la baja de un Servicio global en V24, y por la misma regla (RN-M15-003,
-- no eliminar historicos). Dar de baja un financiador NO da de baja sus planes ni toca
-- ninguna cobertura ya firmada: lo unico que se impide es CREAR planes nuevos bajo el, y que
-- sus planes se ofrezcan para selecciones nuevas. Eso ultimo lo decide la aplicacion leyendo
-- el estado del financiador junto con el del plan; la base no puede expresar "solo al
-- insertar".
-- =====================================================================================

CREATE TABLE financiador
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT       NOT NULL COMMENT 'Tenant propietario. Toda tabla de negocio lo lleva (AGENT.md §5) y todo unique e indice de esta tabla empieza por el. No existe el financiador global en 03.03: ver la cabecera',

    codigo              VARCHAR(64)  NOT NULL COMMENT 'Clave estable con la que la organizacion nombra al financiador. INMUTABLE despues del alta: es lo que las coberturas y los convenios guardan como referencia. Unico entre los financiadores vigentes de la organizacion',
    nombre              VARCHAR(160) NOT NULL COMMENT 'Razon social o nombre con el que se lo muestra. Unico entre los vigentes de la organizacion. SI se puede cambiar: renombrar es esto, no cambiar el codigo',
    tipo                VARCHAR(24)  NOT NULL COMMENT 'Clasificacion del financiador (obra social, prepaga, ART...). CLASIFICA, no habilita: ninguna decision del sistema depende de este valor ni del nombre (regla maestra 15)',

    cuit                VARCHAR(13)  NULL COMMENT 'Identidad fiscal, normalizada a 11 digitos sin guiones por la aplicacion. NULL es un estado real: en el mostrador se carga una obra social sin tener su CUIT a mano',
    email_contacto      VARCHAR(254) NULL COMMENT 'Contacto administrativo del financiador. Nunca contacto de un paciente',
    telefono_contacto   VARCHAR(40)  NULL COMMENT 'Contacto administrativo del financiador',
    observaciones       VARCHAR(500) NULL COMMENT 'Notas administrativas. NUNCA contenido clinico ni datos de un paciente',

    active              TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida administrativo. 0 tras la baja logica: no admite planes nuevos y sus planes dejan de ofrecerse para selecciones nuevas. Las coberturas y convenios ya firmados NO se tocan (RN-M15-003)',
    deleted_at          DATETIME(6)  NULL COMMENT 'Instante UTC de la baja logica. NULL mientras este activo',
    deactivation_reason VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja. Obligatorio: sin el, la auditoria no responde por que seis meses despues',

    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador de los uniques de codigo, nombre y CUIT: el instante de la baja, o el centinela 1970-01-01 mientras la fila este activa. Sin el, un unique sobre deleted_at a secas protegeria el historico y desprotegeria lo vigente, porque en MySQL varios NULL no colisionan',

    version             BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Una version vieja produce 409 en vez de pisar el cambio ajeno en silencio',
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,

    CONSTRAINT pk_financiador PRIMARY KEY (id),

    CONSTRAINT uk_financiador_codigo_vigente UNIQUE (organization_id, codigo, deleted_key),
    CONSTRAINT uk_financiador_nombre_vigente UNIQUE (organization_id, nombre, deleted_key),

    -- El CUIT tambien es unico entre los vigentes de la organizacion, y aca los NULL SI
    -- ayudan: varios financiadores sin CUIT conviven sin chocar, que es lo que hace falta en
    -- el mostrador. Es el mismo comportamiento que persona.documento (V27) aprovecha.
    CONSTRAINT uk_financiador_cuit_vigente UNIQUE (organization_id, cuit, deleted_key),

    CONSTRAINT fk_financiador_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    -- Lista cerrada. El conjunto es del producto y no del tenant, asi que no hay tabla de
    -- catalogo — mismo criterio que espacio.tipo (V19) y servicio.naturaleza (V24).
    CONSTRAINT ck_financiador_tipo
        CHECK (tipo IN ('OBRA_SOCIAL', 'PREPAGA', 'ART', 'MUTUAL', 'ORGANISMO_PUBLICO', 'OTRO')),

    -- El CUIT se guarda normalizado: 11 digitos, sin guiones. La normalizacion la hace la
    -- aplicacion; este CHECK impide que otro camino de escritura —una carga a mano, una
    -- migracion de datos— meta un formato distinto y rompa el unique sin que nadie se entere.
    CONSTRAINT ck_financiador_cuit_normalizado
        CHECK (cuit IS NULL OR cuit REGEXP '^[0-9]{11}$'),

    -- Coherencia de la baja logica: los tres campos se mueven juntos o no se mueven. Sin
    -- esto es posible active = 0 con deleted_at NULL, que ademas rompe el centinela del
    -- unique porque la fila cae en 1970 junto con las activas.
    CONSTRAINT ck_financiador_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- El listado y el buscador de RF-M15-006: filtra por tenant y estado, ordena por nombre.
    -- Con el nombre adentro el ORDER BY sale del indice.
    INDEX ix_financiador_estado_nombre (organization_id, active, nombre)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Obra social, prepaga u otro financiador con el que trabaja una organizacion (M15). Lleva organization_id NOT NULL: en 03.03 no existe el financiador global de plataforma. Propietario: modulo contracting';


CREATE TABLE plan_cobertura
(
    id                    BIGINT         NOT NULL AUTO_INCREMENT,

    organization_id       BIGINT         NOT NULL COMMENT 'Tenant propietario. Redundante con el del financiador por la FK, y aun asi obligatorio: un unique o un indice sin la columna de tenant es un bug de aislamiento aunque hoy sea deducible (AGENT.md §5)',
    financiador_id        BIGINT         NOT NULL COMMENT 'Financiador al que pertenece el plan (RN-M15-001). Un plan sin financiador no existe, y no se puede mudar de financiador: cambiarlo reescribiria el significado de las coberturas que ya lo referencian',

    codigo                VARCHAR(64)    NOT NULL COMMENT 'Clave estable del plan dentro del financiador. INMUTABLE despues del alta, por el mismo motivo que financiador.codigo. Unico entre los planes vigentes de ESE financiador',
    nombre                VARCHAR(160)   NOT NULL COMMENT 'Nombre del plan tal como lo ve el mostrador. Unico entre los vigentes de ese financiador. SI se puede cambiar',
    descripcion           VARCHAR(500)   NULL COMMENT 'Descripcion administrativa del plan. Nunca contenido clinico',

    vigencia_desde        DATE           NOT NULL COMMENT 'Primer dia en que el plan se puede elegir para una cobertura o un convenio nuevo',
    vigencia_hasta        DATE           NULL COMMENT 'ULTIMO dia en que se puede elegir, INCLUSIVO (ver la cabecera). NULL significa sin fin previsto, que es un estado real y no un dato faltante',

    requiere_autorizacion TINYINT(1)     NOT NULL DEFAULT 0 COMMENT 'Si las prestaciones bajo este plan exigen autorizacion previa del financiador. DATO DECLARADO, NO RESUELTO en esta etapa: quien lo interpreta es M17, que no existe',
    requiere_credencial   TINYINT(1)     NOT NULL DEFAULT 1 COMMENT 'Si la cobertura del paciente bajo este plan exige numero de credencial. Lo consume M08 al validar la cobertura; aca solo se declara',

    copago                DECIMAL(12, 2) NULL COMMENT 'Copago de referencia del plan. DECIMAL y jamas float (AGENT.md §5). NULL = sin copago declarado, que es distinto de copago cero. Viaja siempre con moneda',
    moneda                CHAR(3)        NULL COMMENT 'ISO 4217 del copago. NULL si y solo si copago es NULL: un importe sin moneda no es un importe, y este SaaS va a operar en mas de un pais',

    active                TINYINT(1)     NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida administrativo. 0 tras la baja logica: RN-M15-002, no se ofrece en altas nuevas y sus coberturas historicas siguen resolviendo',
    deleted_at            DATETIME(6)    NULL COMMENT 'Instante UTC de la baja logica. NULL mientras el plan este activo',
    deactivation_reason   VARCHAR(280)   NULL COMMENT 'Motivo declarado de la baja. Obligatorio',

    deleted_key           DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador de los uniques de codigo y nombre. Mismo centinela y mismo motivo que en financiador',

    version               BIGINT         NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Una version vieja produce 409 en vez de pisar el cambio ajeno en silencio',
    created_at            DATETIME(6)    NOT NULL,
    updated_at            DATETIME(6)    NOT NULL,

    CONSTRAINT pk_plan_cobertura PRIMARY KEY (id),

    -- El alcance de la unicidad es el FINANCIADOR y no la organizacion: dos financiadores
    -- distintos pueden tener los dos un plan "210", y obligar a que no se repita entre ellos
    -- forzaria a inventar codigos que el financiador real no usa.
    CONSTRAINT uk_plan_codigo_vigente
        UNIQUE (organization_id, financiador_id, codigo, deleted_key),
    CONSTRAINT uk_plan_nombre_vigente
        UNIQUE (organization_id, financiador_id, nombre, deleted_key),

    CONSTRAINT fk_plan_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    -- RESTRICT por defecto: el borrado fisico de un financiador con planes es imposible desde
    -- la base, que es lo que RN-M15-003 pide. La baja LOGICA no cascadea (ver la cabecera).
    CONSTRAINT fk_plan_financiador
        FOREIGN KEY (financiador_id) REFERENCES financiador (id),

    -- vigencia_hasta INCLUSIVA: un plan que vale un solo dia es un estado real, asi que la
    -- igualdad se admite. Ver la cabecera sobre la ambiguedad que dejo V24.
    CONSTRAINT ck_plan_vigencia_coherente
        CHECK (vigencia_hasta IS NULL OR vigencia_hasta >= vigencia_desde),

    -- Importe y moneda viajan juntos o no viajan, mismo criterio que oferta.precio_base (V24).
    CONSTRAINT ck_plan_copago_con_moneda
        CHECK ((copago IS NULL AND moneda IS NULL)
            OR (copago IS NOT NULL AND moneda IS NOT NULL)),

    -- Un copago negativo no es un descuento: es un dato roto. Cero SI es valido y significa
    -- "sin copago", distinto de NULL que significa "no declarado".
    CONSTRAINT ck_plan_copago_no_negativo
        CHECK (copago IS NULL OR copago >= 0),

    CONSTRAINT ck_plan_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- El listado de planes de un financiador y el selector de M08: filtra por tenant,
    -- financiador y estado, ordena por nombre.
    INDEX ix_plan_financiador_estado (organization_id, financiador_id, active, nombre)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Plan de cobertura de un financiador (M15). RN-M15-001: siempre pertenece a un financiador. La vigencia acota cuando se lo puede ELEGIR; las coberturas ya firmadas guardan su propia copia congelada. Propietario: modulo contracting';
