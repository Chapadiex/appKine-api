-- =====================================================================================
-- AKINE-02.02 — Espacios, boxes y capacidad fisica (M04).
--
-- Trazabilidad: RF-M04-001 (alta), RF-M04-002 (edicion), RF-M04-003 (consulta base de
-- disponibilidad), RF-M04-006 (baja logica conservando historicos), RF-M04-007 (tipo y
-- capacidad); RN-M04-001..004, RN-M04-005; ADR-0004 (persistencia multi-tenant),
-- ADR-0007 (expandir-migrar-contraer).
--
-- Propietario de la tabla: modulo `resource`. Ningun otro modulo la lee ni la escribe: lo
-- que otros necesiten sale por `com.akine.resource.spi` (AGENT.md seccion 4, regla 1).
--
-- ---------------------------------------------------------------------------------------
-- POR QUE ESTA MIGRACION ES UNA SOLA Y NO TRES
--
-- ADR-0007 exige expandir-migrar-contraer para CAMBIOS sobre datos existentes. Aca no hay
-- ninguno: la tabla nace vacia en esta release, no hay backfill que verificar y no existe
-- codigo viejo corriendo contra un esquema anterior. Las tres migraciones de 02.01 hicieron
-- falta porque `consultorio` YA tenia filas. Una tabla nueva se crea con sus constraints
-- definitivas puestas desde el minuto cero, que es la unica forma de que nunca exista una
-- ventana en la que un dato invalido entre.
--
-- ---------------------------------------------------------------------------------------
-- LA TRAMPA DE LOS NULL EN LOS UNIQUE DE MySQL, CUARTA VEZ EN ESTE REPOSITORIO
--
-- Lo que hay que proteger: RF-M04-002 pide nombre "contextual" y el caso real es que dos
-- espacios VIGENTES del mismo consultorio no se llamen igual — "Box 1" dos veces en la misma
-- sede es un error de carga que despues nadie distingue en una agenda.
--
-- Lo que hay que NO romper: RN-M04-003 y la regla maestra 10. La baja es logica y los
-- historicos conservan su nombre. Un unique sobre (organization_id, consultorio_id, name)
-- a secas condenaria el nombre "Box 1" para siempre en cuanto ese box se diera de baja.
--
-- El reflejo, y por que esta ROTO:
--     UNIQUE (organization_id, consultorio_id, name, deleted_at)
-- En MySQL —como en el estandar SQL— varios NULL NO colisionan en un indice unico. TODAS las
-- filas vigentes tienen deleted_at IS NULL, con lo que dos boxes activos homonimos dejarian
-- de colisionar. Es la inversion exacta de lo buscado: protege el historico y desprotege lo
-- vigente.
--
-- Este repositorio ya tiene las tres formas, y NO son intercambiables:
--   * V10 (membership.consultorio_scope)  CENTINELA: la columna generada nunca es NULL, asi
--     que TODAS las filas entran al unique.
--   * V12 (membership_grant.grant_activo) NULL A PROPOSITO: vale el discriminador mientras la
--     fila esta vigente y NULL cuando no, con lo que las filas historicas salen solas del
--     unique.
--   * V18 (consultorio.deleted_key)       CENTINELA DE FECHA: el instante de la baja, o
--     1970-01-01 mientras la fila este activa.
--
-- Aca se elige el CENTINELA DE FECHA de V18, y el motivo es el mismo que alli:
--   * con la forma de V12 el discriminador de las filas historicas se perderia entero, y lo
--     unico que distingue dos bajas homonimas del mismo box es el INSTANTE en que ocurrieron;
--   * dos espacios ACTIVOS de la misma sede comparten deleted_key = 1970-01-01, colisionan y
--     producen 409 espacio-name-taken. Es el caso que importa y queda protegido;
--   * un activo y uno dado de baja no colisionan: el nombre se puede reusar (RF-M04-006);
--   * dos bajas del mismo nombre solo colisionan si ocurrieron en el MISMO microsegundo.
--     DATETIME(6) lo hace practicamente imposible, y el desenlace seria un 409 en una baja,
--     recuperable reintentando.
--
-- Lo que NO se puede hacer, y ya se verifico en V18:
--   * usar `id` como discriminador: MySQL prohibe que una columna generada referencie una
--     columna AUTO_INCREMENT;
--   * un indice unico parcial (UNIQUE ... WHERE): no existe en MySQL 8.4, es de PostgreSQL.
--
-- El unique empieza por organization_id: el alcance tenant no se debilita (ADR-0004), aunque
-- consultorio_id ya lo determine por la FK. Un unique sin la columna de tenant es un bug de
-- aislamiento aunque sea redundante hoy (AGENT.md seccion 5).
--
-- IRREVERSIBILIDAD (riesgo, se anota y no se resuelve): una vez que exista un espacio dado de
-- baja y otro activo con el mismo nombre en la misma sede, volver al unique de tres columnas
-- es imposible sin renombrar uno de los dos.
--
-- ---------------------------------------------------------------------------------------
-- VIGENCIA — dos columnas, y por que no alcanza con active/deleted_at
--
-- La etapa pide "CRUD con vigencia" e "indices temporales futuros". Son DOS ejes distintos y
-- confundirlos es el error que esto evita:
--
--   * active / deleted_at  = CICLO DE VIDA ADMINISTRATIVO. "Este box ya no forma parte del
--     catalogo". Es irreversible, lleva motivo y lo decide una persona.
--   * valid_from / valid_until = VENTANA OPERATIVA. "Este box entra en servicio el 1 de
--     marzo" o "sale de servicio el 30 de junio por refaccion". Es un dato de planificacion,
--     no una baja, y es lo que RF-M04-003 necesita para responder si el recurso esta
--     disponible PARA UNA FECHA, que es distinto de si existe hoy.
--
-- Un espacio se ofrece para una reserva en el instante T si y solo si:
--     active = 1  AND  valid_from <= T  AND  (valid_until IS NULL OR T < valid_until)
--
-- valid_until nulable significa "sin fin previsto" y aca el NULL SI es correcto: no participa
-- de ningun unique, y "sin fecha de salida" es un estado real y no un valor faltante.
-- El limite superior es EXCLUSIVO (T < valid_until) para que dos ventanas consecutivas del
-- mismo recurso no se solapen en el microsegundo del borde.
--
-- ---------------------------------------------------------------------------------------
-- CAPACIDAD — RN-M04-005, y por que el CHECK es > 0 y no >= 0
--
-- "Todo espacio debe poseer capacidad operativa configurable; para un box individual
-- normalmente sera 1". Un espacio de capacidad cero no es un espacio con menos cupo: es un
-- espacio que no puede recibir a nadie, que es exactamente lo que la baja logica expresa, con
-- motivo y con auditoria. Permitir 0 daria dos formas de decir lo mismo, una de ellas sin
-- rastro de quien la decidio.
--
-- No hay tope superior en la base: cuantas personas entran en un gimnasio es un dato del
-- centro y no del esquema. El acotamiento operativo vive en la aplicacion, donde se puede
-- cambiar sin migracion.
--
-- ---------------------------------------------------------------------------------------
-- TIPO — CHECK con lista cerrada, no tabla de catalogo
--
-- RN-M04-007 pide explicitamente NO crear "roles o tipos rigidos por nombre". El tipo de aqui
-- es una clasificacion fisica del recurso —box, gimnasio, gabinete, sala grupal— y NO decide
-- que servicios se prestan ahi: eso lo resolvera la habilitacion por Oferta de Servicio
-- (RF-M04-008, modulo offering). Se modela como lista cerrada y no como tabla porque el
-- conjunto es del producto y no del tenant; el dia que un centro necesite su propia
-- taxonomia, la tabla se agrega sin tocar esta columna, que pasa a ser el fallback OTRO.
-- =====================================================================================

CREATE TABLE espacio
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT       NOT NULL COMMENT 'Tenant propietario. Toda tabla de negocio lo lleva (AGENT.md seccion 5) y todo unique e indice de esta tabla empieza por el',
    consultorio_id      BIGINT       NOT NULL COMMENT 'Sede a la que pertenece el espacio (RN-M04-001). NOT NULL: un espacio sin sede no existe, y permitir NULL abriria un recurso de alcance organizacion que ningun RF de M04 pide',

    name                VARCHAR(160) NOT NULL COMMENT 'Nombre operativo con el que la sede lo llama: Box 1, Gimnasio, Sala de Pilates. Unico entre los espacios VIGENTES de la sede',
    tipo                VARCHAR(32)  NOT NULL COMMENT 'Clasificacion fisica del recurso (RF-M04-007). Lista cerrada: no habilita servicios ni define roles (RN-M04-007)',
    capacidad           INT          NOT NULL COMMENT 'Cuantas personas admite simultaneamente (RN-M04-005). 1 para un box individual. Siempre mayor que cero: capacidad cero es una baja, y la baja tiene su propia columna con motivo',
    notes               VARCHAR(280) NULL COMMENT 'Observacion operativa libre. Dato del tenant, nunca contenido clinico',

    valid_from          DATETIME(6)  NOT NULL COMMENT 'Instante UTC desde el que el espacio esta en servicio. Ventana OPERATIVA, distinta del ciclo de vida administrativo de active/deleted_at',
    valid_until         DATETIME(6)  NULL COMMENT 'Instante UTC hasta el que esta en servicio, EXCLUSIVO. NULL significa sin fin previsto, que es un estado real y no un valor faltante',

    active              TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida administrativo. 0 tras la baja logica: el espacio deja de ofrecerse para reservas nuevas (RN-M04-002) y conserva intacta su historia (RN-M04-003)',
    deleted_at          DATETIME(6)  NULL COMMENT 'Instante UTC de la baja logica. NULL mientras el espacio este activo',
    deactivation_reason VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja (RF-M04-006). Obligatorio al dar de baja: sin el, la auditoria no responde por que seis meses despues',

    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique de nombre: el instante de la baja, o el centinela 1970-01-01 mientras el espacio este activo. Existe solo para que el unique funcione: en MySQL varios NULL no colisionan, asi que un unique sobre deleted_at a secas dejaria de proteger justo el caso que importa, dos espacios activos homonimos en la misma sede',

    version             BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. La edicion la reenvia y una version vieja produce 409 concurrent-modification en vez de pisar el cambio ajeno en silencio',
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,

    CONSTRAINT pk_espacio PRIMARY KEY (id),

    CONSTRAINT uk_espacio_sede_name_vigente
        UNIQUE (organization_id, consultorio_id, name, deleted_key),

    CONSTRAINT fk_espacio_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_espacio_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    -- RN-M04-005. Ver la cabecera: cero no es "menos cupo", es una baja sin motivo ni rastro.
    CONSTRAINT ck_espacio_capacidad_positiva
        CHECK (capacidad > 0),

    -- Lista cerrada del tipo fisico. OTRO es el fallback explicito y evita que aparezcan
    -- valores libres que despues nadie puede agrupar en un reporte.
    CONSTRAINT ck_espacio_tipo
        CHECK (tipo IN ('BOX', 'GIMNASIO', 'GABINETE', 'SALA_GRUPAL', 'PILETA', 'OTRO')),

    -- Coherencia temporal: una ventana que termina antes de empezar no es una ventana.
    -- El limite superior es exclusivo, asi que valid_until = valid_from tampoco es valido:
    -- seria un recurso disponible durante cero microsegundos.
    CONSTRAINT ck_espacio_vigencia_coherente
        CHECK (valid_until IS NULL OR valid_until > valid_from),

    -- Coherencia de la baja logica: los tres campos se mueven juntos o no se mueven.
    -- Sin esto es posible active = 0 con deleted_at NULL —un espacio dado de baja sin fecha,
    -- que ademas rompe el centinela del unique porque cae en 1970 junto con los activos— y
    -- tambien active = 1 con motivo de baja cargado, que es una contradiccion que nadie sabe
    -- resolver al leerla.
    CONSTRAINT ck_espacio_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- Listado de la pantalla de administracion y del selector de espacios: filtra por sede y
    -- estado, ordena por nombre. Con name adentro el ORDER BY sale del indice.
    INDEX ix_espacio_sede_estado (organization_id, consultorio_id, active, name),

    -- El indice TEMPORAL que pide la etapa. Lo consume la consulta base de disponibilidad
    -- (RF-M04-003) y lo consumira la agenda de F5, que pregunta siempre lo mismo: que
    -- recursos de esta sede estan en servicio en tal ventana.
    INDEX ix_espacio_vigencia (organization_id, consultorio_id, valid_from, valid_until)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Espacio fisico de una sede: box, gimnasio, gabinete, sala grupal (M04). Propietario: modulo resource. La asignacion de un espacio a un turno (RF-M04-004) y a una sesion (RF-M04-005) NO vive aca: son hechos de scheduling y clinical, y llegan en F5';
