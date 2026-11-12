-- =====================================================================================
-- AKINE-03.05 — Convenios, aranceles y vigencias (M16).
--
-- Trazabilidad: RF-M16-001 (crear convenio), RF-M16-002 (editar convenio), RF-M16-003
-- (cerrar vigencia), RF-M16-004 (definir arancel por practica), RF-M16-005 (definir
-- requisitos), RF-M16-006 y RF-M16-010 (resolver convenio y arancel efectivo);
-- RN-M16-001 (el convenio es contextual al CONSULTORIO), RN-M16-002 (las vigencias
-- superpuestas se controlan), RN-M16-003 (cambiar un arancel no recalcula lo historico),
-- RN-M16-004 (se guarda snapshot economico aplicado), RN-M16-005 (sin convenio valido no se
-- asume cobertura); §37 (importes y precision monetaria), §38 (bajas logicas y vigencias);
-- reglas maestras 6, 9, 10 y 11.
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant).
--
-- Propietario de las tres tablas: modulo `contracting`, que nacio en AKINE-03.03 con
-- `financiador` y `plan_cobertura` (V41). Ningun otro modulo las lee ni las escribe: los
-- consumidores previstos —M17 autorizaciones, M18 obligaciones, M21 presentaciones— llegan
-- por `contracting.spi.ArancelDirectory`.
--
-- Migracion unica y no expandir-migrar-contraer, mismo motivo que V41: las tres tablas nacen
-- vacias, no hay backfill que verificar y no hay codigo viejo corriendo contra un esquema
-- anterior.
--
-- ---------------------------------------------------------------------------------------
-- LO MAS IMPORTANTE DE ESTA MIGRACION: NINGUN INDICE EXPRESA EL NO-SOLAPAMIENTO
--
-- RN-M16-002 dice que las vigencias superpuestas se controlan, y la cabecera de V41 ya dejo
-- escrito como iba a tener que hacerse cuando M16 lo necesitara. Aca lo necesita.
--
-- Un UNIQUE compara IGUALDAD. Dos convenios que van del 01/01 al 30/06 y del 01/03 al 31/12
-- se pisan, y no comparten ningun valor de columna: no hay conjunto de columnas cuya
-- igualdad detecte esa interseccion. MySQL 8.4 tampoco tiene exclusion constraints —son de
-- PostgreSQL— ni indices GiST sobre rangos.
--
-- La regla la hace cumplir la APLICACION, y para que la aplicacion pueda hacerlo con dos
-- escrituras concurrentes hace falta un punto de serializacion: `convenio_lock`. Es la
-- misma solucion que `agenda_sede` (V30) le dio al solapamiento de turnos y que
-- `consultorio_calendario` (V16) le dio a la disponibilidad, y por la misma razon: bloquear
-- las filas de `convenio` que YA existen no impide que otra transaccion INSERTE una en el
-- hueco, que es exactamente el caso a evitar.
--
-- Tres consecuencias que quien toque este modulo tiene que conocer:
--
--   1. Las mutaciones que serializan corren en READ_COMMITTED y no en el REPEATABLE READ por
--      defecto de InnoDB. Con REPEATABLE READ la foto de la transaccion se fija en la primera
--      lectura consistente, que ocurre ANTES del lock, asi que el lock se toma correctamente
--      y despues se lee un mundo viejo donde el convenio ajeno todavia no existe.
--   2. El lock se toma ANTES de leer nada del conjunto que se valida. Leer primero y bloquear
--      despues es una escalada S->X entre dos transacciones simetricas, o sea un deadlock.
--   3. La fila de `convenio_lock` se crea en una transaccion APARTE, con
--      INSERT ... ON DUPLICATE KEY UPDATE. Crearla perezosamente dentro de la transaccion que
--      la bloquea produce deadlock entre las primeras N escrituras concurrentes de una sede, y
--      el try/catch no salva: atrapar una excepcion de persistencia no des-marca la
--      transaccion y Spring lanza UnexpectedRollbackException al commitear. Ya se pago tres
--      veces en este proyecto —agenda_sede, consultorio_calendario y sesion_numerador—.
--
-- Lo que los UNIQUE de aca SI expresan es igualdad: el codigo del convenio dentro de la sede.
-- Eso un indice lo puede sostener y por eso lo sostiene.
--
-- ---------------------------------------------------------------------------------------
-- EL CONVENIO ES DE LA SEDE Y EL FINANCIADOR ES DE LA ORGANIZACION (RN-M16-001)
--
-- `financiador` y `plan_cobertura` llevan organization_id y NO consultorio_id: la obra social
-- con la que trabaja un centro es dato del centro entero. El CONVENIO, en cambio, es lo que
-- una SEDE concreta negocio con esa obra social, y dos sedes de la misma organizacion pueden
-- tener aranceles distintos para la misma practica bajo el mismo plan. Por eso `convenio`
-- lleva las dos columnas y la unicidad, la resolucion y el lock son todos por sede.
--
-- No hay FK compuesta (organization_id, consultorio_id) ni (organization_id, financiador_id):
-- a nivel de base una fila podria declarar la organizacion A y apuntar a un financiador de la
-- B. El aislamiento no lo da el esquema, lo da que cada consulta lleve las columnas en el
-- WHERE. Misma advertencia, y mismo motivo, que la cabecera de ContractingRepositoryPorts.
--
-- ---------------------------------------------------------------------------------------
-- PLAN_ID ES NOT NULL, Y ESO ES LO QUE HACE DETERMINISTA LA RESOLUCION
--
-- RF-M16-001 pide "financiador, plan, vigencia y modalidad". Un convenio "para todo el
-- financiador, sin plan" seria una segunda regla candidata para la misma consulta, y entonces
-- resolver exigiria una prioridad y un desempate — que es justamente el caso borde "dos reglas
-- candidatas" que la etapa nombra. Con plan obligatorio la clave de resolucion es
-- (consultorio, financiador, plan, practica, fecha) y el no-solapamiento garantiza que devuelva
-- COMO MUCHO UNA fila. El resultado unico no sale de una regla de prioridad: sale de que no
-- existan dos candidatas.
--
-- ---------------------------------------------------------------------------------------
-- LOS IMPORTES SON DECIMAL Y NO HAY NINGUN PORCENTAJE (§37)
--
-- RF-M16-004 pide "valor, cobertura, coseguro/copago". Se modela con TRES importes explicitos
-- —total, lo que paga el financiador y lo que paga el paciente— y un CHECK que exige que los
-- dos ultimos sumen el primero. Deliberadamente NO hay un porcentaje de cobertura: un
-- porcentaje obliga a multiplicar y redondear, §37 exige documentar todo redondeo, y el
-- redondeo de un arancel es exactamente el tipo de diferencia de un centavo que aparece seis
-- meses despues en una presentacion rechazada. Guardando los tres importes no hay ninguna
-- operacion que redondear: la suma de dos DECIMAL(12,2) es exacta.
--
-- La moneda viaja con los importes y es NOT NULL aca —a diferencia de plan_cobertura.copago,
-- que es nullable porque "sin copago declarado" es un estado real—: un arancel sin importe no
-- existe, asi que tampoco existe un arancel sin moneda.
--
-- ---------------------------------------------------------------------------------------
-- CAMBIAR UN ARANCEL NO RECALCULA LO HISTORICO (RN-M16-003, RN-M16-004)
--
-- Esta migracion NO garantiza eso, y es importante saber quien lo garantiza. Lo garantiza que
-- el consumidor —M18 obligaciones, hoy; M17 y M21 despues— COPIE a sus propias columnas el
-- snapshot que entrega `contracting.spi.ArancelDirectory#congelar`, en vez de volver a
-- resolver al mostrar. Es exactamente lo que `obligacion` ya hace con el precio de la oferta
-- desde AKINE-07.01, y lo que `ReferenciaDeCobertura` hace con el plan desde 03.03.
--
-- Lo que esta migracion aporta a esa garantia es la mitad estructural: no hay borrado fisico
-- (§38, las FK son RESTRICT y la baja es logica) y editar un arancel es cerrar su vigencia y
-- abrir otra, no pisar el importe de la ventana ya transcurrida.
--
-- ---------------------------------------------------------------------------------------
-- VIGENCIA_HASTA ES INCLUSIVA
--
-- Ultimo dia en que el convenio o el arancel se aplican. Lo fijo V41 para plan_cobertura y no
-- se contradice: los CHECK admiten hasta = desde —un arancel que vale un solo dia es un estado
-- real— y el codigo Java usa el mismo criterio (`Convenio.vigenteEl`, `ConvenioArancel.vigenteEl`).
-- =====================================================================================

-- =====================================================================================
-- convenio_lock — una fila por sede cuyo unico proposito es ser bloqueada.
--
-- NO guarda estado. Ver la cabecera: es el punto de serializacion sin el cual el control de
-- no-solapamiento no resiste dos escrituras concurrentes. Es la misma solucion que
-- `agenda_sede` (V30), y NO se reusa aquella fila porque pertenece al modulo `scheduling`: un
-- modulo no bloquea las tablas de otro, y ArchUnit lo verifica.
--
-- Una fila por SEDE y no por (financiador, plan): serializa todas las escrituras de convenios
-- y aranceles de una sede entre si, que son unas pocas por mes en un centro de kinesiologia.
-- Correctitud sobre paralelismo, mismo criterio que agenda_sede. Si algun dia el volumen lo
-- pidiera, esta tabla admite filas mas finas sin migrar un solo convenio.
-- =====================================================================================

CREATE TABLE convenio_lock
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,

    organization_id BIGINT      NOT NULL COMMENT 'Tenant propietario',
    consultorio_id  BIGINT      NOT NULL COMMENT 'Sede cuyas escrituras de convenios y aranceles serializa esta fila',

    created_at      DATETIME(6) NOT NULL,

    CONSTRAINT pk_convenio_lock PRIMARY KEY (id),

    -- El unique es lo que hace atomico el INSERT ... ON DUPLICATE KEY UPDATE del iniciador.
    CONSTRAINT uk_convenio_lock_scope UNIQUE (organization_id, consultorio_id),

    CONSTRAINT fk_convenio_lock_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_convenio_lock_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Fila-lock por sede. Serializa las escrituras de convenio y convenio_arancel para hacer cumplir RN-M16-002. No guarda estado. Propietario: modulo contracting';


CREATE TABLE convenio
(
    id                       BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id          BIGINT       NOT NULL COMMENT 'Tenant propietario. Toda tabla de negocio lo lleva (AGENT.md §5) y todo unique e indice declarado de esta tabla empieza por el',
    consultorio_id           BIGINT       NOT NULL COMMENT 'RN-M16-001: el convenio es contextual al CONSULTORIO. Dos sedes de la misma organizacion pueden tener aranceles distintos con el mismo financiador y el mismo plan',

    financiador_id           BIGINT       NOT NULL COMMENT 'Financiador con el que se negocio (M15). No se puede cambiar: mudarlo reescribiria el significado de todo lo que ya se liquido bajo este convenio',
    plan_id                  BIGINT       NOT NULL COMMENT 'Plan de cobertura del financiador. NOT NULL a proposito: es lo que hace que la resolucion tenga como mucho una candidata. Ver la cabecera',

    codigo                   VARCHAR(64)  NOT NULL COMMENT 'Clave estable con la que la sede nombra al convenio. INMUTABLE despues del alta: es lo que un snapshot economico guarda para explicar, seis meses despues, bajo que acuerdo se cobro',
    nombre                   VARCHAR(160) NOT NULL COMMENT 'Nombre visible del convenio. SI se puede cambiar',

    modalidad                VARCHAR(24)  NOT NULL COMMENT 'Como se pacto la prestacion (RF-M16-001). CLASIFICA, no habilita: ninguna decision del sistema depende de este valor ni del nombre (regla maestra 15)',

    vigencia_desde           DATE         NOT NULL COMMENT 'Primer dia en que el convenio se aplica',
    vigencia_hasta           DATE         NULL COMMENT 'ULTIMO dia en que se aplica, INCLUSIVO. NULL = sin fin previsto, que es un estado real y no un dato faltante',

    moneda                   CHAR(3)      NOT NULL COMMENT 'ISO 4217 de los aranceles de este convenio. NOT NULL: un convenio sin moneda no podria tener aranceles, y todos los suyos comparten esta',

    -- RF-M16-005: requisitos. Se DECLARAN aca y los interpreta M17, que no existe todavia.
    requiere_orden           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Si la prestacion exige orden medica. DATO DECLARADO, NO RESUELTO en esta etapa',
    requiere_autorizacion    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Si exige autorizacion previa del financiador. DATO DECLARADO, NO RESUELTO: quien lo interpreta es M17',
    requiere_credencial      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Si exige numero de credencial del paciente. Lo consume M08 al validar la cobertura',
    limite_sesiones_mensual  INT          NULL COMMENT 'Tope de sesiones por mes pactado. NULL = sin tope pactado, distinto de cero. DECLARADO, no aplicado en esta etapa',
    documentacion_requerida  VARCHAR(500) NULL COMMENT 'Documentacion administrativa que el financiador exige. Texto libre: nunca contenido clinico ni datos de un paciente',

    observaciones            VARCHAR(500) NULL COMMENT 'Notas administrativas. NUNCA contenido clinico ni datos de un paciente',

    active                   TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida administrativo. 0 tras la baja logica: deja de resolver para prestaciones nuevas y sus aranceles dejan de aplicarse. Lo ya liquidado NO se toca (RN-M16-003)',
    deleted_at               DATETIME(6)  NULL COMMENT 'Instante UTC de la baja logica. NULL mientras este activo',
    deactivation_reason      VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja. Obligatorio: sin el, la auditoria no responde por que seis meses despues',

    deleted_key              DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique de codigo. Mismo centinela y mismo motivo que en financiador (V41): sin el, un unique sobre deleted_at a secas protegeria el historico y desprotegeria lo vigente, porque en MySQL varios NULL no colisionan',

    version                  BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Una version vieja produce 409 en vez de pisar el cambio ajeno en silencio',
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,

    CONSTRAINT pk_convenio PRIMARY KEY (id),

    -- IGUALDAD, que es lo unico que un unique sabe expresar. El alcance es la SEDE.
    CONSTRAINT uk_convenio_codigo_vigente
        UNIQUE (organization_id, consultorio_id, codigo, deleted_key),

    CONSTRAINT fk_convenio_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_convenio_consultorio  FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_convenio_financiador  FOREIGN KEY (financiador_id) REFERENCES financiador (id),
    CONSTRAINT fk_convenio_plan         FOREIGN KEY (plan_id) REFERENCES plan_cobertura (id),

    -- Lista cerrada. El conjunto es del producto y no del tenant, mismo criterio que
    -- financiador.tipo (V41) y servicio.naturaleza (V24).
    CONSTRAINT ck_convenio_modalidad
        CHECK (modalidad IN ('POR_PRESTACION', 'POR_SESION', 'MODULO', 'CAPITA')),

    -- vigencia_hasta INCLUSIVA: un convenio que vale un solo dia es un estado real.
    CONSTRAINT ck_convenio_vigencia_coherente
        CHECK (vigencia_hasta IS NULL OR vigencia_hasta >= vigencia_desde),

    -- Cero no es "sin tope": es un tope de cero sesiones, que no tiene sentido pactar. NULL es
    -- "sin tope pactado".
    CONSTRAINT ck_convenio_limite_positivo
        CHECK (limite_sesiones_mensual IS NULL OR limite_sesiones_mensual > 0),

    CONSTRAINT ck_convenio_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- El indice de la RESOLUCION (RF-M16-006). Es la consulta caliente del modulo y tambien la
    -- que corre bajo el lock para validar el no-solapamiento: filtra por sede, financiador,
    -- plan y estado, y ordena por vigencia.
    INDEX ix_convenio_resolucion
        (organization_id, consultorio_id, financiador_id, plan_id, active, vigencia_desde),

    -- El listado de la pantalla de administracion.
    INDEX ix_convenio_sede_estado (organization_id, consultorio_id, active, nombre)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Acuerdo economico entre una SEDE y un plan de un financiador (M16, RN-M16-001). Dos convenios de la misma (sede, financiador, plan) no se pueden solapar en el tiempo, y esa regla la hace cumplir un lock sobre convenio_lock, NUNCA un indice: ver la cabecera de V43. Propietario: modulo contracting';


CREATE TABLE convenio_arancel
(
    id                  BIGINT         NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT         NOT NULL COMMENT 'Tenant propietario. Redundante con el del convenio por la FK, y aun asi obligatorio: un unique o un indice sin la columna de tenant es un bug de aislamiento aunque hoy sea deducible (AGENT.md §5)',
    consultorio_id      BIGINT         NOT NULL COMMENT 'Sede del convenio. Redundante por la misma razon, y necesario para que el lock por sede y las consultas de resolucion no tengan que pasar por convenio',
    convenio_id         BIGINT         NOT NULL COMMENT 'Convenio al que pertenece el arancel. No se puede mudar: cambiarlo reescribiria bajo que acuerdo se pacto ese precio',

    practica_id         BIGINT         NOT NULL COMMENT 'Practica del catalogo clinico (M06) que se arancela. Puede ser una practica GLOBAL de plataforma o una propia del tenant: quien valida que el tenant la vea es la aplicacion por resource.spi.CatalogoDirectory, porque una FK compara ids y no alcances',

    importe_total       DECIMAL(12, 2) NOT NULL COMMENT 'Lo que vale la practica bajo este convenio. DECIMAL y jamas float (§37, AGENT.md §5)',
    importe_financiador DECIMAL(12, 2) NOT NULL COMMENT 'La parte que paga el financiador. Importe explicito y NO un porcentaje: un porcentaje obliga a redondear y §37 exige documentar todo redondeo. Ver la cabecera',
    coseguro            DECIMAL(12, 2) NOT NULL COMMENT 'La parte que paga el paciente (coseguro/copago del convenio). Cero es valido y significa cobertura total',
    moneda              CHAR(3)        NOT NULL COMMENT 'ISO 4217. NOT NULL: un arancel sin importe no existe, asi que tampoco existe uno sin moneda',

    vigencia_desde      DATE           NOT NULL COMMENT 'Primer dia en que este importe se aplica',
    vigencia_hasta      DATE           NULL COMMENT 'ULTIMO dia en que se aplica, INCLUSIVO. NULL = sin fin previsto',

    active              TINYINT(1)     NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida. 0 tras la baja logica: deja de resolver para prestaciones nuevas. Lo ya liquidado guarda su propio snapshot y no se toca (RN-M16-003)',
    deleted_at          DATETIME(6)    NULL,
    deactivation_reason VARCHAR(280)   NULL COMMENT 'Motivo declarado de la baja. Obligatorio',

    version             BIGINT         NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista',
    created_at          DATETIME(6)    NOT NULL,
    updated_at          DATETIME(6)    NOT NULL,

    CONSTRAINT pk_convenio_arancel PRIMARY KEY (id),

    -- NO HAY NINGUN UNIQUE DE (convenio_id, practica_id): seria falso. Dos aranceles de la
    -- misma practica en el mismo convenio son el caso NORMAL —el de 2026 y el de 2027— y lo
    -- unico prohibido es que sus vigencias se PISEN, que ningun unique sabe expresar. Ver la
    -- cabecera: lo hace cumplir el lock de convenio_lock.

    CONSTRAINT fk_arancel_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_arancel_consultorio  FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_arancel_convenio     FOREIGN KEY (convenio_id) REFERENCES convenio (id),
    CONSTRAINT fk_arancel_practica     FOREIGN KEY (practica_id) REFERENCES practica (id),

    CONSTRAINT ck_arancel_vigencia_coherente
        CHECK (vigencia_hasta IS NULL OR vigencia_hasta >= vigencia_desde),

    -- Ningun importe negativo. Cero SI es valido en las tres columnas y significa cosas
    -- distintas en cada una: total cero es una practica sin cargo, financiador cero es una
    -- practica que el financiador no cubre, coseguro cero es cobertura total.
    CONSTRAINT ck_arancel_importes_no_negativos
        CHECK (importe_total >= 0 AND importe_financiador >= 0 AND coseguro >= 0),

    -- LA INVARIANTE ECONOMICA DE LA TABLA. Las partes suman el total, exactamente, sin
    -- redondeo posible: la suma de dos DECIMAL(12,2) es exacta. Si algun dia se admitiera un
    -- porcentaje de cobertura, este CHECK es lo que habria que reemplazar por una regla de
    -- redondeo documentada, y por eso conviene no hacerlo.
    CONSTRAINT ck_arancel_partes_suman_total
        CHECK (importe_financiador + coseguro = importe_total),

    CONSTRAINT ck_arancel_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- El indice de la RESOLUCION (RF-M16-010) y el de la validacion de no-solapamiento.
    INDEX ix_arancel_resolucion
        (organization_id, convenio_id, practica_id, active, vigencia_desde),

    -- El listado de aranceles de un convenio.
    INDEX ix_arancel_convenio_estado (organization_id, convenio_id, active, vigencia_desde)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Arancel de una practica bajo un convenio, con vigencia propia (M16, RF-M16-004). Los tres importes son DECIMAL y las partes suman el total sin redondeo (§37). Dos aranceles de la misma practica en el mismo convenio no se pueden solapar, y esa regla la hace cumplir el lock de convenio_lock, NUNCA un indice. Propietario: modulo contracting';
