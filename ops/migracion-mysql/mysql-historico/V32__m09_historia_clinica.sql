-- =====================================================================================
-- AKINE-04.01 — Historia Clinica organizacional reducida (M09).
--
-- POR QUE V32
--
-- DP-10 reserva las versiones de migracion ANTES de escribir codigo, porque el trabajo en
-- paralelo ya rompio Flyway una vez: 02.07 y 03.01 nacieron las dos como V26, la version
-- duplicada fue rechazada y la aplicacion no arrancaba. V32 es la version que DP-10 le asigna
-- a esta etapa, que corre en el carril PARALELO. El carril principal tiene V29-V31 y V33-V37.
-- V26 sigue vacia a proposito.
--
-- Trazabilidad: RF-M09-001 (crear/obtener la HC), RF-M09-002 (antecedentes); RN-M09-001
-- (la HC no es una lista plana de sesiones), RN-M09-003 (no duplicar datos administrativos
-- del paciente), RN-M09-004 (los cambios clinicos sensibles son trazables); DP-03 / ADR-0010
-- (la HC pertenece a la ORGANIZACION); DP-10 (recorte de alcance, no de modelo); reglas
-- maestras 1 (HC != Caso != Sesion) y 10 (nada historico se borra fisicamente).
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0007 (expandir-migrar-contraer), ADR-0023 (excepciones de organization_id).
--
-- Propietario de las dos tablas: modulo `clinical`, que nace en esta etapa. Ningun otro modulo
-- las lee ni las escribe (AGENT.md seccion 4, regla 1); los que necesiten la HC entran por
-- `clinical.spi.HistoriaClinicaDirectory`. `clinical` depende de `person.spi`,
-- `organization.spi` y `platform.spi.audit`, y nadie depende de `clinical` todavia.
--
-- Migracion unica y no expandir-migrar-contraer, mismo motivo que V19, V24 y V27: ADR-0007
-- exige el ciclo de tres para CAMBIOS sobre datos existentes. Las dos tablas nacen vacias.
--
-- ---------------------------------------------------------------------------------------
-- LA DECISION CENTRAL: LA HC ES DE LA ORGANIZACION, Y ESO SE VE EN LAS COLUMNAS
--
-- `historia_clinica` lleva organization_id NOT NULL y NO lleva consultorio_id. No es una
-- omision: es DP-03 escrita en el esquema. La HC es el contexto longitudinal del paciente
-- DENTRO DE ESE TENANT, consultable desde cualquier sede de la misma organizacion por quien
-- tenga membership vigente y permiso clinico, y nunca compartida entre organizaciones.
--
-- Si llevara consultorio_id, la misma persona tendria dos historias en un centro con dos
-- sedes y la longitudinalidad se perderia justo en el caso que la motiva. La sede desde la
-- que se accedio NO se pierde: viaja en `audit_event.consultorio_id`, que es donde
-- corresponde, porque es un dato del ACCESO y no de la historia.
--
-- El unique uk_historia_clinica_persona_vigente lleva (organization_id, persona_id,
-- deleted_key) y es lo que hace cumplir "una HC por paciente y Organizacion". Tambien es lo
-- que hace IDEMPOTENTE el alta sin ventana de carrera: dos requests simultaneos no producen
-- dos historias, el segundo choca contra el unique y la aplicacion responde con la que gano.
-- Un pre-chequeo en la aplicacion no da esa garantia.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE persona_id Y NO paciente_id, Y POR QUE NO SE COPIA NADA DE LA PERSONA
--
-- V27 dejo fijado que Persona y Paciente son dos filas: la identidad esta en `persona` y el
-- perfil clinico en `perfil_paciente`. La HC cuelga de la PERSONA —que es la identidad
-- estable— y la precondicion de que esa persona tenga perfil de paciente vigente la verifica
-- la aplicacion contra `person.spi`, no una FK hacia `perfil_paciente`. Colgar la HC del
-- perfil la ataria a una fila que RF-M07-005 puede dar de baja y volver a activar: la
-- historia sobreviviria a la baja del perfil o se duplicaria en la reactivacion, y las dos
-- salidas son peores.
--
-- RN-M09-003 prohibe duplicar datos administrativos del paciente, asi que aca NO hay nombre,
-- ni documento, ni telefono, ni fecha de nacimiento. Quien muestre la HC los lee de `persona`
-- por el spi del modulo `person`. La contrapartida —que un cambio de nombre se refleje hacia
-- atras— es la correcta para datos administrativos; lo que si exige snapshot historico son
-- los importes y los aranceles, que son de otro modulo.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE LOS ANTECEDENTES SON FILAS Y NO CUATRO COLUMNAS DE TEXTO
--
-- RF-M09-002 pide registrar antecedentes medicos, quirurgicos, alergias y medicacion. El
-- reflejo es cuatro columnas TEXT en `historia_clinica`. Se descarto por RN-M09-004: los
-- cambios clinicos sensibles tienen que ser TRAZABLES, y un UPDATE sobre una columna de texto
-- pisa lo anterior sin dejar rastro de que decia, quien lo escribio ni cuando.
--
-- Como filas, cada antecedente tiene su autor, su instante y su baja logica con motivo. Un
-- antecedente que deja de aplicar NO se borra ni se sobreescribe: se da de baja y queda
-- consultable, que es la regla maestra 10. Y el tipo cerrado permite que el dia que M17 pida
-- "alergias vigentes" eso sea un WHERE y no un parseo de texto libre.
--
-- No hay unique sobre la descripcion a proposito: dos alergias con texto parecido son un
-- caso legitimo, y un unique ahi convertiria un dato clinico en un conflicto administrativo.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE NO SE MODELA, Y POR QUE
--
--   * Ningun timeline. Es 04.02 y esta CORTADA por DP-10. La costura queda representada en el
--     codigo —`clinical.spi.EventoClinicoContributor`, hoy sin implementaciones— y no como una
--     tabla vacia: una tabla sin escritor es peor que ninguna, porque invita a que alguien la
--     llene con un formato que 04.02 despues no puede usar. El ancla del timeline futuro es
--     `historia_clinica.id`, que ya existe y ya es estable.
--   * Ningun Caso Clinico. Es 04.03 y esta CORTADA. RN-M09-002 dice que los Casos organizan
--     los problemas concretos, y la regla maestra 1 los separa de la HC: la HC de hoy no
--     tiene ni una columna que apunte a un Caso.
--   * Ninguna sesion, ninguna evolucion, ningun adjunto. RN-M09-001 es explicita: la HC no es
--     una lista plana de sesiones.
--   * Ninguna tabla propia de auditoria de acceso. Ya existe `audit_event` (V5, V14) con sus
--     triggers de append-only, y todo acceso clinico sensible de esta etapa se escribe ahi.
--     Una segunda tabla de auditoria seria una segunda verdad.
--   * Ninguna FK hacia `perfil_paciente`: ver arriba. Ninguna FK hacia `cuenta`: esa tabla es
--     de `identity` y una FK cruzaria la propiedad de datos entre modulos.
--   * La baja logica de las dos tablas tiene columnas y solo `historia_clinica_antecedente`
--     tiene hoy quien la ejerza. Las columnas de `historia_clinica` estan desde ahora porque
--     agregarlas despues obligaria a rehacer el unique, que es la unica parte cara de esta
--     migracion.
-- =====================================================================================

CREATE TABLE historia_clinica
(
    id                      BIGINT        NOT NULL AUTO_INCREMENT,

    organization_id         BIGINT        NOT NULL COMMENT 'Tenant propietario. DP-03: la HC es de la ORGANIZACION. Sin consultorio_id a proposito, ver la cabecera',
    persona_id              BIGINT        NOT NULL COMMENT 'Persona titular de la historia. Cuelga de la identidad estable (persona) y no del perfil de paciente, que puede darse de baja y reactivarse',

    abierta_en              DATETIME(6)   NOT NULL COMMENT 'Instante UTC de apertura de la historia (RF-M09-001)',
    abierta_por             BIGINT        NOT NULL COMMENT 'accountId del actor que la abrio. Sin FK a cuenta: cuenta es de identity y una FK cruzaria la propiedad de datos entre modulos',

    resumen                 VARCHAR(2000) NULL COMMENT 'Resumen clinico minimo y editable de la historia (RF-M09-001, "resumen basico"). NULL mientras nadie lo escriba: una historia recien abierta no tiene resumen y eso no es un dato faltante, es su estado',
    resumen_actualizado_en  DATETIME(6)   NULL COMMENT 'Instante UTC de la ultima escritura del resumen. NULL mientras el resumen sea NULL',
    resumen_actualizado_por BIGINT        NULL COMMENT 'accountId del ultimo actor que escribio el resumen (RN-M09-004: los cambios clinicos son trazables)',

    active                  TINYINT(1)    NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida de la historia. Sin endpoint de baja en esta etapa; la columna existe desde ahora porque el unique la necesita',
    deleted_at              DATETIME(6)   NULL COMMENT 'Instante UTC de la baja logica. NULL mientras la historia este vigente',
    deactivation_reason     VARCHAR(280)  NULL COMMENT 'Motivo declarado de la baja',

    deleted_key             DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique de persona: el instante de la baja, o el centinela 1970-01-01 mientras la historia este vigente. Sin el, el unique protegeria el historico y desprotegeria lo vigente, porque en MySQL varios NULL no colisionan',

    version                 BIGINT        NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Una version vieja produce conflicto en vez de pisar el cambio ajeno en silencio',
    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,

    CONSTRAINT pk_historia_clinica PRIMARY KEY (id),

    -- "Una HC por paciente y Organizacion", la regla de negocio central de la etapa. Es
    -- tambien lo que hace idempotente la apertura sin ventana de carrera: ver la cabecera.
    CONSTRAINT uk_historia_clinica_persona_vigente
        UNIQUE (organization_id, persona_id, deleted_key),

    CONSTRAINT fk_historia_clinica_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    -- RESTRICT (el default): no hay borrado fisico de una persona con historia clinica.
    -- RN-M07-004 ya lo prohibia y aca la base lo hace cumplir.
    CONSTRAINT fk_historia_clinica_persona
        FOREIGN KEY (persona_id) REFERENCES persona (id),

    CONSTRAINT ck_historia_clinica_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- Un resumen escrito sin autor ni fecha no es trazable, y RN-M09-004 exige que lo sea.
    -- Los tres viajan juntos o los tres son NULL.
    CONSTRAINT ck_historia_clinica_resumen_trazable
        CHECK ((resumen IS NULL AND resumen_actualizado_en IS NULL AND resumen_actualizado_por IS NULL)
            OR (resumen IS NOT NULL AND resumen_actualizado_en IS NOT NULL AND resumen_actualizado_por IS NOT NULL))
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Contexto clinico longitudinal de una Persona DENTRO de una organizacion (M09, DP-03). NO es una lista de sesiones (RN-M09-001) ni contiene datos administrativos del paciente (RN-M09-003). Propietario: modulo clinical';


CREATE TABLE historia_clinica_antecedente
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT        NOT NULL COMMENT 'Tenant propietario. Redundante con el de la historia por la FK, y va igual: un indice sin la columna de tenant es un bug de aislamiento aunque hoy sea deducible',
    historia_clinica_id BIGINT        NOT NULL COMMENT 'Historia a la que pertenece el antecedente',

    tipo                VARCHAR(24)   NOT NULL COMMENT 'Clase de antecedente. Lista cerrada del producto, no del tenant: RF-M09-002 nombra los cuatro primeros y el resto sostiene la anamnesis basica sin obligar a texto libre',
    descripcion         VARCHAR(1000) NOT NULL COMMENT 'Contenido clinico del antecedente, tal como lo escribio el profesional',

    registrado_en       DATETIME(6)   NOT NULL COMMENT 'Instante UTC en que se registro (RN-M09-004)',
    registrado_por      BIGINT        NOT NULL COMMENT 'accountId del profesional que lo registro. Sin FK a cuenta, mismo motivo que en historia_clinica',

    active              TINYINT(1)    NOT NULL DEFAULT 1 COMMENT 'Vigencia del antecedente. Un antecedente que deja de aplicar se da de baja; NO se borra ni se sobreescribe (regla maestra 10)',
    deleted_at          DATETIME(6)   NULL COMMENT 'Instante UTC de la baja logica',
    deactivation_reason VARCHAR(280)  NULL COMMENT 'Motivo declarado de la baja. Obligatorio: un antecedente clinico que desaparece sin explicacion es exactamente lo que RN-M09-004 quiere impedir',

    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,

    CONSTRAINT pk_historia_clinica_antecedente PRIMARY KEY (id),

    CONSTRAINT fk_hc_antecedente_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_hc_antecedente_historia
        FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id),

    CONSTRAINT ck_hc_antecedente_tipo
        CHECK (tipo IN ('MEDICO', 'QUIRURGICO', 'ALERGIA', 'MEDICACION', 'FAMILIAR', 'HABITO', 'OTRO')),

    CONSTRAINT ck_hc_antecedente_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- La unica consulta de la etapa: los antecedentes de una historia, opcionalmente por tipo.
    -- Empieza por organization_id porque toda consulta de este modulo acota por tenant antes
    -- que por nada (ADR-0004).
    INDEX ix_hc_antecedente_historia (organization_id, historia_clinica_id, tipo)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Antecedente clinico de una Historia Clinica (M09, RF-M09-002). Filas y no columnas de texto: RN-M09-004 exige trazabilidad y un UPDATE sobre texto libre no la da. Propietario: modulo clinical';
