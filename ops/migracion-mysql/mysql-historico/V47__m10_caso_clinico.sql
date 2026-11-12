-- =====================================================================================
-- AKINE-04.03 — Caso Clinico y numeracion contextual (M10).
--
-- POR QUE V47
--
-- El diseño de la etapa (docs/diseno/AKINE-04.03-caso-clinico.md, seccion 10) reserva V47
-- para las cinco tablas del Caso y V48 para el gancho en `sesion`, ANTES de escribir codigo
-- y por el motivo que 02.07 y 03.01 pagaron caro: las dos nacieron como V26, Flyway rechaza
-- versiones duplicadas —"Found more than one migration with version N"— y la aplicacion no
-- arranca. Flyway no exige versiones contiguas.
--
-- Y SON DOS MIGRACIONES Y NO UNA, que es lo que hay que mirar de frente antes que nada:
-- `sesion` es de `encounter` y estas cinco tablas son de `clinical`. La regla 1 de AGENT.md
-- seccion 4 no admite que la migracion de un modulo toque la tabla de otro, asi que el
-- ALTER de `sesion` vive en V48 aunque las dos versiones salgan de la misma etapa.
--
-- Trazabilidad: RF-M10-001..006; RN-M10-001..007; reglas maestras 1 (HC != Caso != Sesion),
-- 3 (las sesiones se numeran dentro del Caso) y 10 (nada historico se borra). DP-03 /
-- ADR-0010 (la HC es de la ORGANIZACION), ADR-0011 (requisitos clinico-legales), DP-10 (se
-- corta alcance, no modelo). ADR-0003, ADR-0004, ADR-0007.
--
-- Propietario de las cinco tablas: modulo `clinical`. Ningun otro modulo las lee ni las
-- escribe. El caso que habia que mirar es `caso_sesion_numerador` —numera SESIONES, asi que
-- la intuicion dice que es de `encounter`—: cuenta POR CASO, y el ciclo de vida del caso
-- —cuando empieza a contar, que pasa al reabrir— es justamente lo que el `spi` existe para
-- que `encounter` no tenga que saber. Queda en `clinical`, y `encounter` PIDE el numero por
-- `clinical.spi.CasoDirectory` dentro de su transaccion de cierre (challenge seccion 1).
--
-- Migracion unica y no expandir-migrar-contraer, mismo motivo que V19, V24, V27, V32, V40 y
-- V45: ADR-0007 exige el ciclo de tres para CAMBIOS sobre datos existentes. Las cinco tablas
-- nacen vacias.
--
-- ---------------------------------------------------------------------------------------
-- 1. EL CORRELATIVO SALE DE `caso_numerador`, NUNCA DE UN `MAX + 1`
--
-- Dos administrativos abriendo un caso para el mismo paciente al mismo tiempo desde dos
-- sedes es el caso que rompe el diseño (challenge seccion 8), y con `SELECT MAX(numero) + 1`
-- los dos se llevan el mismo numero: hay una ventana entre la lectura y la escritura. El
-- `UPDATE ... SET ultimo_numero = ultimo_numero + 1` toma un lock exclusivo de fila y
-- serializa, sin leer nada antes.
--
-- LA FILA DEL NUMERADOR SE CREA EN UNA TRANSACCION APARTE, con `INSERT ... ON DUPLICATE KEY
-- UPDATE`. La creacion perezosa dentro de la transaccion que la bloquea produce un DEADLOCK
-- entre las primeras N escrituras concurrentes, y el try/catch no salva: atrapar una
-- excepcion de persistencia no des-marca la transaccion y Spring lanza
-- `UnexpectedRollbackException` al commitear. Ya se pago CUATRO veces en este repositorio
-- (`agenda_sede`, `consultorio_calendario`, `sesion_numerador`, `autorizacion_persona_lock`).
-- No se paga una quinta.
--
-- EL UNIQUE `(organization_id, historia_clinica_id, numero_caso)` ES EL RESPALDO, NO EL
-- MECANISMO. A diferencia del solapamiento de turnos —que ningun unique puede expresar, ver
-- V30— aca la regla SI es igualdad, asi que el motor puede hacerla cumplir: si el numerador
-- fallara, la base impide el numero repetido en vez de dejarlo pasar.
--
-- ---------------------------------------------------------------------------------------
-- 2. NO HAY UNIQUE DE "UN SOLO CASO ACTIVO POR HISTORIA", Y ES A PROPOSITO
--
-- RN-M10-002 admite varios casos activos a la vez: una rodilla y un hombro son dos casos
-- legitimos del mismo paciente el mismo dia. Un unique aca seria un bug disfrazado de
-- proteccion.
--
-- El duplicado razonable —un alta que coincide en oferta con un caso ACTIVO de la misma
-- historia— lo resuelve la aplicacion con 409 y la lista de candidatos, y se confirma
-- reenviando, exactamente como el alta de Persona de 03.01 (RN-M07-001). Tiene ventana de
-- carrera y NO se pretende que no la tenga: el resultado correcto no es rechazar, es que los
-- dos entren y alguien los unifique despues.
--
-- ---------------------------------------------------------------------------------------
-- 3. `estado`: `ACTIVO` | `CERRADO`. DOS VALORES Y NADA MAS
--
-- No hay `SUSPENDIDO` ni `EN_PAUSA` porque ningun RF los pide y un estado sin transicion que
-- lo produzca es modelo muerto. La reapertura (RF-M10-006) devuelve a `ACTIVO` y queda en
-- `caso_evento`: RF-M10-006 pide reabrir, no un estado nuevo.
--
-- Y NO HAY BAJA LOGICA DE CASO. Cerrar no es borrar, y un caso abierto por error se cierra
-- con motivo. Agregar `active` al lado de `estado` daria dos formas de que un caso "no este",
-- que es como se construye una consulta que se olvida de una. Es la unica tabla clinica de
-- este esquema sin el cuarteto active/deleted_at/deactivation_reason/deleted_key, y la
-- ausencia es la decision (challenge seccion 5).
--
-- `ck_caso_clinico_cierre_coherente` ata las cuatro columnas del cierre: un caso CERRADO
-- tiene instante, actor y motivo, y uno ACTIVO no tiene ninguno de los tres. Reabrir los
-- limpia —el historial del cierre anterior vive en `caso_evento`, que es append-only— porque
-- dejarlos puestos sobre un caso activo haria que una consulta por `cerrado_en IS NOT NULL`
-- devolviera casos abiertos.
--
-- ---------------------------------------------------------------------------------------
-- 4. `caso_sesion_numerador` NO ES UN CACHE DE COUNT(*), Y REABRIR NO LO REINICIA
--
-- Es el mismo mecanismo del punto 1 aplicado a la numeracion de sesiones DENTRO del caso
-- (regla maestra 3). El caso NO guarda un contador de sesiones como dato propio: un cache se
-- desincroniza y un numerador no puede, porque nunca vuelve atras.
--
-- REABRIR UN CASO NO REINICIA EL CONTADOR. La sesion siguiente a una reapertura es la 9, no
-- la 1: renumerar seria reescribir historia clinica (ADR-0011, regla maestra 10). Por eso el
-- numerador cuelga del caso y no del "periodo de actividad" del caso, que no existe como
-- entidad y no deberia.
--
-- EL RIESGO NUEVO QUE ESTA TABLA INTRODUCE: el cierre de sesion ya pedia un numero —el de la
-- historia, `sesion_numerador` de V35— y ahora puede pedir DOS en la misma transaccion. Es
-- la primera vez que una transaccion de este sistema toma dos numeradores. El ORDEN tiene
-- que ser SIEMPRE el mismo —historia primero, caso despues— o dos cierres concurrentes de
-- sesiones de casos cruzados se bloquean mutuamente. Queda fijado en el javadoc de
-- `SesionService#cerrar` (challenge seccion 8.4).
--
-- ---------------------------------------------------------------------------------------
-- 5. `caso_profesional` USA VIGENCIA Y NO BORRA
--
-- Un profesional desvinculado de la organizacion sigue figurando en el equipo del caso que
-- trato, porque lo trato (caso borde del plan). Lo que cambia es que deja de poder escribir,
-- y eso lo decide la membership vigente, no esta tabla. Sacarlo de la lista reescribiria
-- historia.
--
-- `hasta_key` repite el truco del centinela de V27/V32/V40/V45 con otro significado: aca
-- `hasta IS NULL` es "sigue en el equipo", y varios NULL no colisionan en MySQL, asi que sin
-- la columna generada el unique protegeria el historico y desprotegeria lo vigente — que es
-- exactamente al reves de lo que hace falta.
--
-- ---------------------------------------------------------------------------------------
-- 6. `caso_evento` ES APPEND-ONLY
--
-- Sin `updated_at`, sin `version`, sin baja: un historial que se puede editar no es un
-- historial. Mismo diseño que `turno_evento` (V38). Se inserta DENTRO de la misma
-- transaccion del cambio de estado, porque un evento escrito despues del commit puede
-- perderse y dejar la transicion sin rastro.
--
-- ---------------------------------------------------------------------------------------
-- 7. TENANT EN LAS CINCO, Y EN TODOS LOS UNIQUES E INDICES
--
-- `caso_profesional` y `caso_evento` llevan `organization_id` AUNQUE SEA DERIVABLE del caso,
-- por el mismo motivo que `entrada_clinica_version` en 04.02: derivarlo obliga a un join para
-- filtrar por tenant, y el dia que alguien escriba la consulta sin el join tiene una fuga que
-- ningun test de la etapa ve, porque los tests de una etapa usan un solo tenant.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE NO SE MODELA, Y POR QUE
--
--   * NINGUN `consultorio_id` del caso. El Caso cuelga de la Historia Clinica, que es de la
--     ORGANIZACION (DP-03): un caso de sede seria un caso que no se puede continuar en la
--     otra sede del mismo centro. Lo que si viaja es `oferta_consultorio_id`, que es la sede
--     de la OFERTA y no la del caso — ver su comentario.
--   * NINGUN contador de sesiones en `caso_clinico`. Ver el punto 4.
--   * NINGUNA columna de Plan de Tratamiento. Es 04.04 y la regla maestra 2 lo separa del
--     Caso y del Turno.
--   * NINGUN `active`. Ver el punto 3.
--   * NINGUNA FK hacia `cuenta`: esa tabla es de `identity` y una FK cruzaria la propiedad de
--     datos entre modulos. `abierto_por`, `cerrado_por` y `actor_cuenta_id` son accountId
--     sueltos, como en V32, V40 y V45.
--   * NINGUNA tabla propia de auditoria. Ya existe `audit_event` (V5, V14) con sus triggers
--     de append-only, y ahi se escribe tambien la LECTURA (DP-03). `caso_evento` es otra
--     cosa: es el historial de ESTADOS del caso, que el profesional lee, y no el registro de
--     accesos, que lee quien audita.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- caso_clinico — el agregado. Ver los puntos 2 y 3.
-- -------------------------------------------------------------------------------------
CREATE TABLE caso_clinico
(
    id                     BIGINT        NOT NULL AUTO_INCREMENT,

    organization_id        BIGINT        NOT NULL COMMENT 'Tenant propietario. Todo indice de esta tabla empieza por el (AGENT.md seccion 5)',
    historia_clinica_id    BIGINT        NOT NULL COMMENT 'Historia de la que cuelga el caso. La HC existe sin casos y el caso no existe sin HC (regla maestra 1)',

    numero_caso            INT           NOT NULL COMMENT 'Correlativo del caso DENTRO de la historia. Sale de caso_numerador con UPDATE ultimo_numero + 1, nunca de un MAX. Ver el punto 1',

    oferta_id              BIGINT        NOT NULL COMMENT 'Oferta que motiva el caso. RN-M10-006: la necesidad de Caso la determina la Oferta efectiva',
    oferta_consultorio_id  BIGINT        NOT NULL COMMENT 'Sede de la OFERTA, no del caso. La oferta es por sede (V24) y sin esta columna oferta_id no se puede volver a resolver; el caso sigue siendo de la ORGANIZACION',

    diagnostico_presuntivo VARCHAR(500)  NOT NULL COMMENT 'Lo que se esta tratando. Es contenido clinico y es obligatorio: un caso sin el es una fila que en una lista de casos del paciente no se puede distinguir de la de al lado',
    objetivo_terapeutico   VARCHAR(1000) NULL COMMENT 'A donde se quiere llegar. Opcional: se suele definir despues de la primera evaluacion',

    estado                 VARCHAR(16)   NOT NULL DEFAULT 'ACTIVO' COMMENT 'ACTIVO o CERRADO. Dos valores y nada mas: ver el punto 3',

    abierto_en             DATETIME(6)   NOT NULL COMMENT 'Instante UTC de la apertura (RN-M09-004)',
    abierto_por            BIGINT        NOT NULL COMMENT 'accountId de quien abrio. Sin FK a cuenta, mismo motivo que en V32',

    cerrado_en             DATETIME(6)   NULL COMMENT 'Instante UTC del cierre. Se limpia al reabrir: el historial vive en caso_evento',
    cerrado_por            BIGINT        NULL COMMENT 'accountId de quien cerro',
    motivo_cierre          VARCHAR(500)  NULL COMMENT 'Por que se cerro. OBLIGATORIO al cerrar: sin motivo, un cierre es indistinguible de un abandono y el historial deja de servir para lo unico que sirve',

    version                BIGINT        NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Dos profesionales del equipo editando el objetivo del mismo caso son el caso normal, no el raro (RF-M10-004)',
    created_at             DATETIME(6)   NOT NULL,
    updated_at             DATETIME(6)   NOT NULL,

    CONSTRAINT pk_caso_clinico PRIMARY KEY (id),

    -- El respaldo del numerador, no el mecanismo. Ver el punto 1.
    CONSTRAINT uk_caso_numero
        UNIQUE (organization_id, historia_clinica_id, numero_caso),

    CONSTRAINT fk_caso_clinico_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    -- RESTRICT (el default): no hay borrado fisico de una historia con casos.
    CONSTRAINT fk_caso_clinico_historia
        FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id),

    CONSTRAINT fk_caso_clinico_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),

    CONSTRAINT ck_caso_clinico_estado
        CHECK (estado IN ('ACTIVO', 'CERRADO')),

    CONSTRAINT ck_caso_clinico_numero_positivo
        CHECK (numero_caso > 0),

    -- Los cuatro datos del cierre van juntos o no va ninguno. Ver el punto 3.
    CONSTRAINT ck_caso_clinico_cierre_coherente
        CHECK ((estado = 'ACTIVO' AND cerrado_en IS NULL AND cerrado_por IS NULL
            AND motivo_cierre IS NULL)
            OR (estado = 'CERRADO' AND cerrado_en IS NOT NULL AND cerrado_por IS NOT NULL
                AND motivo_cierre IS NOT NULL)),

    -- Los dos caminos de lectura de la etapa: los casos de una historia —filtrando o no por
    -- estado— y la deteccion de duplicado, que es el mismo filtro mas la oferta.
    INDEX ix_caso_clinico_historia (organization_id, historia_clinica_id, estado, abierto_en),
    INDEX ix_caso_clinico_oferta (organization_id, historia_clinica_id, oferta_id, estado)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Problema clinico concreto de un paciente dentro de su Historia Clinica (M10, RF-M10-001). Varios activos a la vez son legitimos (RN-M10-002). Propietario: modulo clinical';


-- -------------------------------------------------------------------------------------
-- caso_numerador — asignacion atomica del correlativo del caso. Ver el punto 1.
-- -------------------------------------------------------------------------------------
CREATE TABLE caso_numerador
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id     BIGINT      NOT NULL,
    historia_clinica_id BIGINT      NOT NULL,

    ultimo_numero       INT         NOT NULL DEFAULT 0
        COMMENT 'Ultimo correlativo entregado. Se incrementa con UPDATE, nunca con SELECT MAX + 1.',

    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_caso_numerador
        UNIQUE (organization_id, historia_clinica_id),

    CONSTRAINT fk_caso_numerador_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_caso_numerador_historia
        FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Secuencia de casos por historia clinica. No es un cache de COUNT(*): existe para asignar de forma atomica. Gemelo de sesion_numerador (V35)';


-- -------------------------------------------------------------------------------------
-- caso_sesion_numerador — el correlativo de sesion DENTRO del caso. Ver el punto 4.
-- -------------------------------------------------------------------------------------
CREATE TABLE caso_sesion_numerador
(
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id BIGINT      NOT NULL,
    caso_id         BIGINT      NOT NULL,

    ultimo_numero   INT         NOT NULL DEFAULT 0
        COMMENT 'Ultimo numero de sesion entregado DENTRO del caso. Reabrir el caso NO lo reinicia: ver el punto 4.',

    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_caso_sesion_numerador
        UNIQUE (organization_id, caso_id),

    CONSTRAINT fk_caso_sesion_numerador_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_caso_sesion_numerador_caso
        FOREIGN KEY (caso_id) REFERENCES caso_clinico (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Secuencia de sesiones dentro de un Caso Clinico (regla maestra 3). Vive en clinical y no en encounter: cuenta por CASO, y el ciclo de vida del caso es lo que el spi existe para no tener que exportar';


-- -------------------------------------------------------------------------------------
-- caso_profesional — el equipo tratante, con vigencia. Ver el punto 5.
-- -------------------------------------------------------------------------------------
CREATE TABLE caso_profesional
(
    id                        BIGINT      NOT NULL AUTO_INCREMENT,

    organization_id           BIGINT      NOT NULL COMMENT 'Derivable del caso y va IGUAL: ver el punto 7',
    caso_id                   BIGINT      NOT NULL,

    profesional_membership_id BIGINT      NOT NULL
        COMMENT 'La MEMBERSHIP y no la cuenta: la misma persona puede ser profesional en un centro y administrativa en otro. Misma decision que V23 y V28',

    rol                       VARCHAR(16) NOT NULL DEFAULT 'TRATANTE'
        COMMENT 'RESPONSABLE (uno, el que responde por el caso) o TRATANTE. Ver el CHECK.',

    desde                     DATETIME(6) NOT NULL COMMENT 'Instante UTC en que se incorporo al equipo',
    hasta                     DATETIME(6) NULL COMMENT 'Instante UTC en que dejo el equipo. NULL = sigue. NO se borra la fila: quien trato al paciente lo trato (punto 5)',

    hasta_key                 DATETIME(6) AS (IFNULL(hasta, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador de unicidad. Varios NULL no colisionan en MySQL, asi que sin esto el unique protegeria el historico y desprotegeria lo vigente. Ver el punto 5',

    created_at                DATETIME(6) NOT NULL,
    updated_at                DATETIME(6) NOT NULL,

    CONSTRAINT pk_caso_profesional PRIMARY KEY (id),

    CONSTRAINT uk_caso_profesional_vigente
        UNIQUE (organization_id, caso_id, profesional_membership_id, hasta_key),

    CONSTRAINT fk_caso_profesional_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_caso_profesional_caso
        FOREIGN KEY (caso_id) REFERENCES caso_clinico (id),

    CONSTRAINT fk_caso_profesional_membership
        FOREIGN KEY (profesional_membership_id) REFERENCES membership (id),

    CONSTRAINT ck_caso_profesional_rol
        CHECK (rol IN ('RESPONSABLE', 'TRATANTE')),

    CONSTRAINT ck_caso_profesional_vigencia
        CHECK (hasta IS NULL OR hasta >= desde),

    INDEX ix_caso_profesional_caso (organization_id, caso_id, hasta_key)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Equipo tratante de un Caso Clinico, con vigencia (RF-M10-005). No borra: un profesional desvinculado sigue figurando en el caso que trato. Propietario: modulo clinical';


-- -------------------------------------------------------------------------------------
-- caso_evento — historial inmutable de estados. Ver el punto 6.
-- -------------------------------------------------------------------------------------
CREATE TABLE caso_evento
(
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id BIGINT       NOT NULL COMMENT 'Derivable del caso y va IGUAL: ver el punto 7',
    caso_id         BIGINT       NOT NULL,

    tipo            VARCHAR(24)  NOT NULL
        COMMENT 'APERTURA, EDICION, CIERRE, REAPERTURA o CAMBIO_DE_EQUIPO.',

    estado_anterior VARCHAR(16)  NULL COMMENT 'NULL solo en APERTURA: antes no habia estado.',
    estado_nuevo    VARCHAR(16)  NOT NULL,

    motivo          VARCHAR(500) NULL
        COMMENT 'Obligatorio en CIERRE y en REAPERTURA; no aplica al resto. Ver el CHECK.',

    detalle         VARCHAR(500) NULL
        COMMENT 'Resumen no clinico de que cambio. NUNCA lleva diagnostico ni objetivo: este historial lo lee tambien quien no abrio la ficha.',

    ocurrio_en      DATETIME(6)  NOT NULL COMMENT 'Instante UTC de la transicion',
    actor_cuenta_id BIGINT       NOT NULL COMMENT 'accountId de quien la produjo. Sin FK a cuenta',

    created_at      DATETIME(6)  NOT NULL,

    CONSTRAINT fk_caso_evento_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_caso_evento_caso
        FOREIGN KEY (caso_id) REFERENCES caso_clinico (id),

    CONSTRAINT ck_caso_evento_tipo
        CHECK (tipo IN ('APERTURA', 'EDICION', 'CIERRE', 'REAPERTURA', 'CAMBIO_DE_EQUIPO')),

    CONSTRAINT ck_caso_evento_estados
        CHECK (estado_nuevo IN ('ACTIVO', 'CERRADO')
            AND (estado_anterior IS NULL OR estado_anterior IN ('ACTIVO', 'CERRADO'))),

    -- Cerrar y reabrir exigen motivo. Sin el, el historial registra que algo paso y no por
    -- que, que es lo unico que despues se quiere leer.
    CONSTRAINT ck_caso_evento_motivo
        CHECK ((tipo IN ('CIERRE', 'REAPERTURA') AND motivo IS NOT NULL)
            OR (tipo NOT IN ('CIERRE', 'REAPERTURA'))),

    CONSTRAINT ck_caso_evento_apertura
        CHECK ((tipo = 'APERTURA' AND estado_anterior IS NULL)
            OR (tipo <> 'APERTURA' AND estado_anterior IS NOT NULL)),

    INDEX ix_caso_evento_caso (organization_id, caso_id, ocurrio_en)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Historial inmutable de estados de un Caso Clinico (RF-M10-006). APPEND-ONLY: sin updated_at, sin version, sin baja. Mismo diseño que turno_evento (V38). Propietario: modulo clinical';
