-- =====================================================================================
-- AKINE-03.01 — Persona y PerfilPaciente (M07).
--
-- POR QUE V27 Y POR QUE V26 QUEDA VACIA
--
-- AKINE-02.07 (habilitaciones de oferta) se escribio en paralelo a esta etapa y en el mismo
-- arbol de trabajo. Nacio como V26; esta nacio como V26 tambien, y Flyway rechaza dos
-- migraciones con la misma version —"Found more than one migration with version 26"— con lo
-- que la aplicacion directamente no arranca. Esta se corrio a V27 para destrabarlo, y 02.07
-- termino commiteandose como V28.
--
-- El hueco en V26 se deja como esta: renumerar una migracion ya aplicada en cualquier entorno
-- es peor que un numero sin usar. Flyway no exige que las versiones sean contiguas.
--
-- Trazabilidad: RF-M07-001 (buscar), RF-M07-002 (crear evitando duplicados), RF-M07-003
-- (editar), RF-M07-007 (alta de persona consumidora SIN perfil clinico), RF-M07-008
-- (activar PerfilPaciente sobre una Persona existente), RF-M07-010 (ningun consumo no
-- clinico crea artefactos clinicos); RN-M07-001..004 y RN-M07-005..008; regla maestra 13.
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0007 (expandir-migrar-contraer), ADR-0018 (anti-enumeracion uniforme).
--
-- Propietario de las dos tablas: modulo `person`, que nace en esta etapa. Ningun otro modulo
-- las lee ni las escribe (AGENT.md seccion 4, regla 1). `person` depende de
-- `organization.spi` y de `platform.spi.audit`, y nadie depende de `person` todavia.
--
-- Migracion unica y no expandir-migrar-contraer, mismo motivo que V19 y V24: ADR-0007 exige
-- el ciclo de tres para CAMBIOS sobre datos existentes. Las dos tablas nacen vacias, no hay
-- backfill que verificar y no hay codigo viejo corriendo contra un esquema anterior.
--
-- ---------------------------------------------------------------------------------------
-- LA DECISION CENTRAL: PERSONA Y PACIENTE SON DOS FILAS, NO UNA
--
-- El UML de 2019 une Persona y Paciente en una sola entidad. RN-M07-005 exige separarlas:
-- "la identidad base debe evolucionar hacia Persona; Paciente representa un perfil clinico de
-- esa persona". No es un refinamiento estetico. Con una sola tabla, dar de alta a alguien que
-- viene a una clase de pilates lo convierte en paciente, y a partir de ahi todo el sistema
-- clinico tiene una ficha que no deberia existir: es exactamente el error estructural 2 del
-- plan (`Paciente -> Sesion` como atajo suficiente) entrando por la puerta del alta.
--
-- Por eso `perfil_paciente` es una tabla SEPARADA y OPCIONAL, y no una columna
-- `es_paciente TINYINT` en `persona`. Una columna booleana no puede llevar quien activo el
-- perfil, cuando, ni sostener manana la vigencia y la baja que RF-M07-005 le va a pedir; y
-- sobre todo, un `UPDATE persona SET es_paciente = 1` es una linea que cualquier camino puede
-- escribir sin querer, mientras que insertar en otra tabla es un acto deliberado. RF-M07-010
-- pide precisamente que ese acto no ocurra por accidente.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE PERSONA ES DE LA ORGANIZACION Y NO DE LA SEDE
--
-- `persona` lleva organization_id NOT NULL y NO lleva consultorio_id. Dos motivos:
--
--   1. DP-03 (ADR-0010) ya fijo que la Historia Clinica pertenece a la ORGANIZACION. Una
--      identidad de alcance sede produciria dos personas para el mismo ser humano cuando el
--      centro tiene dos sedes, y despues dos historias clinicas para una sola persona dentro
--      de la misma organizacion, que es lo que DP-03 decidio que no pasara.
--   2. El caso borde "duplicado entre sedes" que la etapa lista se resuelve por construccion:
--      con el unique de documento a nivel organizacion, el duplicado entre sedes NO SE PUEDE
--      CREAR. No hay deteccion que escribir para ese caso, hay un unique.
--
-- Que la persona se haya registrado en una sede concreta es un dato de auditoria y de la
-- inscripcion/turno, no de la identidad. Cuando F5 y F9 existan, la sede vivira en el hecho
-- (turno, inscripcion), nunca aca.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE EL DOCUMENTO ES NULLABLE Y POR QUE EL UNIQUE IGUAL SIRVE
--
-- "Persona sin DNI" es un caso borde declarado de la etapa, y es real: un menor sin documento
-- todavia, un extranjero recien llegado, una urgencia. Un NOT NULL obligaria a inventar un
-- documento falso, que es la peor de las salidas: contamina el padron con claves que despues
-- chocan de verdad.
--
-- El unique uk_persona_documento_vigente lleva (organization_id, tipo_documento,
-- documento_clave, deleted_key). En MySQL varios NULL NO colisionan en un unique, y esa
-- propiedad —que en otras tablas es la trampa que obliga al centinela deleted_key— aca es
-- exactamente el comportamiento que se necesita: N personas sin documento conviven sin
-- chocar, y en cuanto una declara documento entra en la unicidad. No hay ninguna rama de
-- codigo que escribir para eso.
--
-- `documento_clave` y no `numero_documento` a secas: "12.345.678" y "12345678" son el mismo
-- documento y un unique sobre el texto crudo los dejaria entrar a los dos. La clave la
-- normaliza la aplicacion (ClaveDeBusqueda) y se guarda materializada porque es tambien la
-- columna por la que se busca; una funcion en el WHERE no usaria indice.
--
-- Es columna comun y no GENERATED: la normalizacion quita acentos y esa transformacion no se
-- expresa en una expresion generada de MySQL sin encadenar diez REPLACE ilegibles. La
-- contrapartida —que la aplicacion se puede olvidar de escribirla— la cierra el dominio:
-- Persona recalcula las tres claves en el constructor y en cada update, no hay setter suelto.
--
-- ---------------------------------------------------------------------------------------
-- deleted_key, EL CENTINELA DE SIEMPRE
--
-- RN-M07-004 prohibe eliminar fisicamente a una persona con historia. La baja logica libera
-- su documento para una persona nueva —el mismo documento de una ficha dada de baja se puede
-- reusar— y eso solo funciona si el unique lleva un discriminador que NO sea NULL en las
-- filas vigentes. El reflejo `UNIQUE (..., deleted_at)` esta ROTO: todas las vigentes tienen
-- deleted_at NULL, varios NULL no colisionan, y el unique terminaria protegiendo el historico
-- y desprotegiendo lo vigente. Mismo centinela de fecha que V18, V19, V20 y V24.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE NO SE MODELA, Y POR QUE
--
--   * Ninguna FK hacia `cuenta` (M02). RN-M07-002 lo dice: paciente y usuario de portal no
--     son necesariamente la misma entidad. Vincular una persona con una cuenta es de la etapa
--     de autoservicio, y ponerle la columna hoy invitaria a que alguien la use como si el
--     vinculo estuviera resuelto.
--   * Ninguna cobertura, ningun adjunto, ninguna deuda: son 03.02, 03.03 y 03.04.
--   * Ningun artefacto clinico. `perfil_paciente` NO tiene historia_clinica_id: la HC es de
--     M09 y la crea `clinical` cuando exista. Esta tabla declara que la persona ES paciente,
--     no que tenga historia. Regla maestra 1.
--   * La baja logica de `persona` y de `perfil_paciente` tiene columnas pero NO tiene endpoint
--     en esta etapa: RF-M07-005 es 03.02. Las columnas estan desde ahora porque agregarlas
--     despues obligaria a rehacer los dos uniques, que es la unica parte cara de esta
--     migracion.
-- =====================================================================================

CREATE TABLE persona
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT       NOT NULL COMMENT 'Tenant propietario. Toda tabla de negocio lo lleva (AGENT.md seccion 5) y todo unique e indice de esta tabla empieza por el. Sin consultorio_id a proposito: la identidad es de la organizacion, ver la cabecera',

    tipo_documento      VARCHAR(16)  NULL COMMENT 'Tipo del documento declarado. NULL junto con numero_documento cuando la persona no tiene documento (caso borde declarado de la etapa)',
    numero_documento    VARCHAR(32)  NULL COMMENT 'Documento tal como lo tipeo el operador, con puntos o espacios si asi vino. Se muestra; NO se busca ni se compara por esta columna',
    documento_clave     VARCHAR(32)  NULL COMMENT 'Documento normalizado (mayusculas, sin acentos, sin separadores). Es la columna que participa del unique y de la busqueda. La escribe el dominio, nunca un UPDATE suelto',

    apellido            VARCHAR(120) NOT NULL COMMENT 'Apellido tal como se muestra',
    nombre              VARCHAR(120) NOT NULL COMMENT 'Nombre tal como se muestra',
    apellido_clave      VARCHAR(120) NOT NULL COMMENT 'Apellido normalizado para buscar y para detectar coincidencias. Perez y Perez con acento son la misma clave',
    nombre_clave        VARCHAR(120) NOT NULL COMMENT 'Nombre normalizado, mismo criterio que apellido_clave',

    fecha_nacimiento    DATE         NULL COMMENT 'Fecha de nacimiento. NULL es un estado real: un alta de mostrador puede no tenerla. Es ademas el desempate de la deteccion de posibles duplicados por nombre',

    email               VARCHAR(320) NULL COMMENT 'Correo de contacto administrativo. NO es una credencial: la identidad de acceso vive en cuenta (M02) y RN-M07-002 las separa',
    telefono            VARCHAR(40)  NULL COMMENT 'Telefono de contacto tal como se tipeo',
    telefono_clave      VARCHAR(40)  NULL COMMENT 'Telefono reducido a sus digitos, para buscar por telefono sin que +54 11 5555-0000 y 1155550000 sean cosas distintas',

    notas               VARCHAR(500) NULL COMMENT 'Observaciones ADMINISTRATIVAS. RN-M07-003: la informacion clinica vive en los modulos clinicos y no entra aca. No hay forma de impedirlo por esquema; esta escrito para que el que agregue una pantalla lo lea',

    active              TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida administrativo. La baja logica es RF-M07-005 (etapa 03.02); la columna existe desde ahora porque el unique la necesita',
    deleted_at          DATETIME(6)  NULL COMMENT 'Instante UTC de la baja logica. NULL mientras la persona este activa',
    deactivation_reason VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja',

    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique de documento: el instante de la baja, o el centinela 1970-01-01 mientras la persona este activa. Ver la cabecera: sin el, el unique protegeria el historico y desprotegeria lo vigente',

    version             BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Una version vieja produce 409 en vez de pisar el cambio ajeno en silencio',
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,

    CONSTRAINT pk_persona PRIMARY KEY (id),

    -- El unico invariante duro de identidad del padron. Ver la cabecera para por que los NULL
    -- de documento no chocan y por que eso es lo buscado, no un agujero.
    CONSTRAINT uk_persona_documento_vigente
        UNIQUE (organization_id, tipo_documento, documento_clave, deleted_key),

    -- Lista cerrada del producto, no del tenant: mismo criterio que espacio.tipo (V19) y
    -- servicio.naturaleza (V24). OTRO existe para no bloquear un alta por un tipo que este
    -- catalogo no previo; lo que NO existe es un tipo que signifique "sin documento", porque
    -- eso ya se expresa con NULL.
    CONSTRAINT ck_persona_tipo_documento
        CHECK (tipo_documento IS NULL
            OR tipo_documento IN ('DNI', 'LC', 'LE', 'CI', 'PASAPORTE', 'OTRO')),

    -- Tipo y numero viajan juntos o no viajan. Un tipo sin numero no identifica nada y un
    -- numero sin tipo no se puede comparar entre paises.
    CONSTRAINT ck_persona_documento_completo
        CHECK ((tipo_documento IS NULL AND numero_documento IS NULL AND documento_clave IS NULL)
            OR (tipo_documento IS NOT NULL AND numero_documento IS NOT NULL AND documento_clave IS NOT NULL)),

    CONSTRAINT ck_persona_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- Los tres caminos de RF-M07-001. Cada uno empieza por organization_id porque toda
    -- consulta de este modulo filtra por tenant antes que por nada (ADR-0004).
    INDEX ix_persona_documento (organization_id, documento_clave),
    INDEX ix_persona_apellido (organization_id, apellido_clave, nombre_clave),
    INDEX ix_persona_telefono (organization_id, telefono_clave)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Identidad administrativa de una persona dentro de una organizacion (M07). NO implica ser paciente: eso es perfil_paciente. Propietario: modulo person';


CREATE TABLE perfil_paciente
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT       NOT NULL COMMENT 'Tenant propietario. Redundante con el de la persona por la FK, y va igual: un unique sin la columna de tenant es un bug de aislamiento aunque hoy sea deducible',
    persona_id          BIGINT       NOT NULL COMMENT 'Persona que este perfil convierte en paciente (RN-M07-007: se reutiliza la identidad, no se duplica). FK RESTRICT: no hay borrado fisico de una persona con perfil',

    activado_en         DATETIME(6)  NOT NULL COMMENT 'Instante UTC en que la persona paso a tener perfil clinico. Es el hecho que RF-M07-008 registra',
    activado_por        BIGINT       NOT NULL COMMENT 'accountId del actor que activo el perfil. Sin FK a cuenta: cuenta es de identity y una FK cruzaria la propiedad de datos entre modulos (AGENT.md seccion 4, regla 1)',
    motivo              VARCHAR(280) NULL COMMENT 'Motivo declarado de la activacion, opcional. RF-M07-008 no lo exige',

    active              TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida del perfil. La baja es RF-M07-005 (etapa 03.02); la columna existe desde ahora porque el unique la necesita',
    deleted_at          DATETIME(6)  NULL COMMENT 'Instante UTC de la baja logica del perfil',
    deactivation_reason VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja del perfil',

    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique de persona. Mismo centinela y mismo motivo que en persona',

    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,

    CONSTRAINT pk_perfil_paciente PRIMARY KEY (id),

    -- UNA persona tiene A LO SUMO UN perfil vigente en la organizacion. Este unique es lo que
    -- hace que la activacion sea IDEMPOTENTE sin ventana de carrera: dos requests simultaneos
    -- no producen dos perfiles, el segundo choca y la aplicacion responde con el que ya existe
    -- (RF-M07-008). Un pre-chequeo en la aplicacion no daria esa garantia.
    CONSTRAINT uk_perfil_paciente_persona UNIQUE (organization_id, persona_id, deleted_key),

    CONSTRAINT fk_perfil_paciente_persona FOREIGN KEY (persona_id) REFERENCES persona (id),

    CONSTRAINT ck_perfil_paciente_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL))
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Perfil clinico OPCIONAL de una Persona (M07, RN-M07-005). Su existencia significa que esta persona es paciente; NO significa que tenga Historia Clinica, que es de M09 y de otro modulo. Propietario: modulo person';
