-- =====================================================================================
-- AKINE-03.04 — Coberturas del paciente (M08).
--
-- Trazabilidad: RF-M08-001 (agregar cobertura), RF-M08-002 (editar), RF-M08-003
-- (finalizar vigencia), RF-M08-004 (seleccionar cobertura para atencion), RF-M08-005
-- (seleccionar Particular);
-- RN-M08-001 (PARTICULAR siempre disponible), RN-M08-002 (solo planes activos/vigentes se
-- ofrecen), RN-M08-003 (cambiar la cobertura actual no modifica atenciones anteriores),
-- RN-M08-004 (cobertura del paciente NO implica convenio del consultorio);
-- reglas maestras 9, 10 y 11.
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant).
--
-- Propietario de las dos tablas: modulo `person`, que AGENT.md §4 declara dueño de
-- M07-M08. `contracting` (M15) NO las lee ni las escribe: la relacion va en la otra
-- direccion, y por el `spi`.
--
-- ---------------------------------------------------------------------------------------
-- LO MAS IMPORTANTE: ESTA TABLA GUARDA UNA COPIA, NO UN PUNTERO
--
-- Las columnas financiador_codigo, financiador_nombre, financiador_tipo, plan_codigo,
-- plan_nombre, requeria_autorizacion, requeria_credencial, copago y moneda son una COPIA
-- CONGELADA de lo que el plan decia el dia en que la cobertura se firmo. No son
-- desnormalizacion por rendimiento: son el requisito.
--
-- Una cobertura firmada ayer NO PUEDE CAMBIAR porque hoy alguien edito el plan. Si esta
-- tabla guardara solo plan_id y resolviera el nombre al mostrar, renombrar "Plan 210" a
-- "Plan 210 Premium" reescribiria retroactivamente todas las coberturas firmadas bajo el
-- nombre viejo, y bajar el copago cambiaria liquidaciones ya presentadas. Es exactamente
-- lo que `obligacion` hace con el precio de la oferta en AKINE-07.01.
--
-- La copia la entrega `contracting.spi.CoberturaCatalogoDirectory#congelar`, que devuelve
-- un record de valores —ReferenciaDeCobertura— y no un puntero al agregado. Los ids
-- financiador_id y plan_id SI se guardan, y para dos cosas nada mas: trazabilidad y el
-- unique del numero de afiliado. NUNCA para releer el texto.
--
-- ---------------------------------------------------------------------------------------
-- PARTICULAR ES LA AUSENCIA DE PLAN, NO UNA FILA DE CATALOGO (RN-M08-001, RN-M15-004)
--
-- V41 declara por que PARTICULAR no es un financiador: sembrarlo lo volveria borrable,
-- renombrable y duplicable, y obligaria a que una decision del sistema dependa de un
-- NOMBRE. Aca se cierra esa decision: `tipo = 'PARTICULAR'` con las nueve columnas de la
-- referencia en NULL. Por eso "PARTICULAR siempre esta disponible" no necesita ninguna
-- fila para ser verdad: no hay nada que dar de baja.
--
-- ---------------------------------------------------------------------------------------
-- NINGUN UNIQUE EXPRESA SOLAPAMIENTO. LO HACE CUMPLIR UN LOCK
--
-- MySQL 8.4 no tiene exclusion constraints, y ningun UNIQUE puede decir que dos intervalos
-- se pisan: 2026-01-01..2026-06-30 y 2026-03-01..2026-12-31 no comparten ningun valor de
-- columna. Las dos reglas temporales de M08 son:
--
--   1. Un paciente no puede tener DOS coberturas activas del MISMO plan con vigencias
--      solapadas. Eso es un duplicado, no una segunda cobertura.
--   2. Un paciente no puede tener DOS coberturas activas marcadas PRINCIPAL con vigencias
--      solapadas. "La seleccion vigente es determinista" es el criterio de aceptacion de la
--      etapa, y con dos principales el mismo dia deja de serlo.
--
-- Las dos las verifica la aplicacion bajo el lock de `cobertura_persona_lock`, en
-- READ_COMMITTED. Con REPEATABLE READ el lock NO alcanza: InnoDB fija la foto en la primera
-- lectura consistente, que ocurre antes del lock, asi que la comprobacion leeria un estado
-- anterior a la fila que la otra transaccion acaba de insertar. Es la leccion que
-- AKINE-05.02 pago con `agenda_sede`.
--
-- LO QUE NO ES REGLA: dos coberturas de financiadores DISTINTOS solapadas son legitimas.
-- Un paciente con obra social y prepaga a la vez es el caso normal, no una anomalia; lo
-- que se elige para una atencion lo decide la marca `principal` y la seleccion explicita.
--
-- ---------------------------------------------------------------------------------------
-- VIGENCIA_HASTA ES INCLUSIVA
--
-- Mismo criterio que plan_cobertura (V41) y que oferta (V24, cuyo codigo la evalua
-- inclusiva aunque su cabecera diga otra cosa). `vigencia_hasta` es el ULTIMO dia en que la
-- cobertura vale, y ck_cobertura_vigencia_coherente admite hasta = desde: una cobertura de
-- un solo dia es un estado real.
--
-- ---------------------------------------------------------------------------------------
-- LA BAJA ES LOGICA Y NO BORRA NADA (regla maestra 10, RF-M08-003)
--
-- Finalizar la vigencia y dar de baja son DOS operaciones distintas, igual que en V41:
--   * Cerrar la vigencia es fijar vigencia_hasta. La cobertura queda ACTIVA y consultable,
--     y lo unico que cambia es que deja de aplicar despues de esa fecha.
--   * Dar de baja es active = 0 con motivo obligatorio: la cobertura sale del ciclo de vida
--     porque nunca debio cargarse, y sigue siendo legible.
-- Ninguna de las dos toca ningun hecho ya registrado: RN-M08-003.
-- =====================================================================================

CREATE TABLE cobertura_paciente
(
    id                        BIGINT         NOT NULL AUTO_INCREMENT,

    organization_id           BIGINT         NOT NULL COMMENT 'Tenant propietario. La persona pertenece a la ORGANIZACION y no a la sede (RF-M07-010, AKINE-03.01), asi que su cobertura tampoco lleva consultorio_id',
    persona_id                BIGINT         NOT NULL COMMENT 'Persona con perfil de paciente vigente. La cobertura es del PACIENTE: la aplicacion exige el perfil antes de insertar, porque una Persona no es un Paciente y la base no puede expresar esa diferencia sin duplicar el perfil aca',

    tipo                      VARCHAR(16)    NOT NULL COMMENT 'PARTICULAR o FINANCIADA. PARTICULAR es la AUSENCIA de plan y no una fila de catalogo (RN-M08-001, RN-M15-004): sus nueve columnas de referencia quedan en NULL',

    -- --- Referencia CONGELADA al plan. Copia, no puntero. Ver la cabecera. ---
    financiador_id            BIGINT         NULL COMMENT 'Identidad estable del financiador congelado. Se guarda para trazabilidad, NUNCA para releer su nombre: para eso estan las columnas de texto de al lado',
    financiador_codigo        VARCHAR(64)    NULL COMMENT 'COPIA del codigo del financiador al firmar. El codigo es inmutable en M15, asi que esta copia y la fila viva siempre coinciden; viaja igual para que la cobertura se explique sola',
    financiador_nombre        VARCHAR(160)   NULL COMMENT 'COPIA del nombre al firmar. SI puede diferir de la fila viva, y ese es el punto: renombrar el financiador manana no reescribe esta cobertura',
    financiador_tipo          VARCHAR(24)    NULL COMMENT 'COPIA de la clasificacion al firmar',
    plan_id                   BIGINT         NULL COMMENT 'Identidad estable del plan congelado. Se usa para trazabilidad y para el unique del numero de afiliado. NUNCA para releer el texto ni el copago',
    plan_codigo               VARCHAR(64)    NULL COMMENT 'COPIA del codigo del plan al firmar',
    plan_nombre               VARCHAR(160)   NULL COMMENT 'COPIA del nombre del plan al firmar. Puede diferir de la fila viva',
    requeria_autorizacion     TINYINT(1)     NULL COMMENT 'COPIA de plan.requiere_autorizacion al firmar. En pasado a proposito: dice lo que el plan exigia ese dia, no lo que exige hoy. Lo interpreta M17, que no existe',
    requeria_credencial       TINYINT(1)     NULL COMMENT 'COPIA de plan.requiere_credencial al firmar. Es lo que hace obligatorio el numero de afiliado en el alta, y por eso tiene que quedar congelado: cambiarlo en el plan no puede invalidar retroactivamente una cobertura ya cargada',
    copago                    DECIMAL(12, 2) NULL COMMENT 'COPIA del copago de referencia del plan al firmar. DECIMAL y jamas float (AGENT.md §5). NULL = no habia copago declarado, distinto de cero',
    moneda                    CHAR(3)        NULL COMMENT 'ISO 4217 del copago congelado. NULL si y solo si copago es NULL',
    referencia_capturada_el   DATETIME(6)    NULL COMMENT 'Instante UTC en que se congelo la referencia. Es lo que permite explicar, seis meses despues, por que esta copia dice algo distinto de lo que el plan dice hoy',

    -- --- Datos propios de la afiliacion ---
    numero_afiliado           VARCHAR(64)    NULL COMMENT 'Credencial del paciente ante el financiador. Obligatorio en el alta cuando el plan congelado requeria credencial; NULL siempre en PARTICULAR',
    credencial_vigencia_hasta DATE           NULL COMMENT 'Ultimo dia de validez impreso en la credencial, INCLUSIVO. Es METADATA: una credencial vencida NO invalida la cobertura, se informa como alerta. Vencerla automaticamente daria de baja coberturas reales por un dato que el mostrador copia a mano',

    vigencia_desde            DATE           NOT NULL COMMENT 'Primer dia en que la cobertura aplica',
    vigencia_hasta            DATE           NULL COMMENT 'ULTIMO dia en que aplica, INCLUSIVO. NULL = sin fin previsto. Fijarlo es "finalizar vigencia" (RF-M08-003) y NO es dar de baja',

    principal                 TINYINT(1)     NOT NULL DEFAULT 0 COMMENT 'Cobertura preferida del paciente. Como maximo una activa con vigencias solapadas, y eso lo hace cumplir un LOCK y no un indice: ver la cabecera',
    observaciones             VARCHAR(500)   NULL COMMENT 'Notas administrativas de la cobertura. NUNCA contenido clinico',

    active                    TINYINT(1)     NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida. 0 tras la baja logica: la cobertura sigue siendo legible y los hechos que la referencian no se tocan (RN-M08-003)',
    deleted_at                DATETIME(6)    NULL COMMENT 'Instante UTC de la baja logica',
    deactivation_reason       VARCHAR(280)   NULL COMMENT 'Motivo declarado de la baja. Obligatorio',

    deleted_key               DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique del numero de afiliado: el instante de la baja, o el centinela 1970 mientras la fila este activa. Mismo mecanismo y mismo motivo que en V41',

    version                   BIGINT         NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Una version vieja produce 409 en vez de pisar el cambio ajeno en silencio',
    created_at                DATETIME(6)    NOT NULL,
    updated_at                DATETIME(6)    NOT NULL,

    CONSTRAINT pk_cobertura_paciente PRIMARY KEY (id),

    -- El mismo numero de afiliado no se carga dos veces vigente bajo el MISMO plan. Los NULL
    -- ayudan y por eso el unique no lleva persona_id: dos personas con el mismo carnet del
    -- mismo plan es un error de carga, no dos coberturas. Las coberturas PARTICULAR tienen
    -- plan_id y numero_afiliado en NULL y por lo tanto nunca colisionan entre si.
    CONSTRAINT uk_cobertura_afiliado_vigente
        UNIQUE (organization_id, plan_id, numero_afiliado, deleted_key),

    CONSTRAINT fk_cobertura_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_cobertura_persona
        FOREIGN KEY (persona_id) REFERENCES persona (id),

    -- RESTRICT por defecto: el borrado fisico de un financiador o de un plan referenciado por
    -- una cobertura es imposible desde la base. En M15 las bajas son logicas, asi que la fila
    -- referenciada siempre resuelve (RN-M15-003).
    CONSTRAINT fk_cobertura_financiador
        FOREIGN KEY (financiador_id) REFERENCES financiador (id),
    CONSTRAINT fk_cobertura_plan
        FOREIGN KEY (plan_id) REFERENCES plan_cobertura (id),

    CONSTRAINT ck_cobertura_tipo
        CHECK (tipo IN ('PARTICULAR', 'FINANCIADA')),

    -- La copia congelada viaja ENTERA o no viaja. Media referencia —un plan_id sin el nombre
    -- de aquel momento— es exactamente el puntero que esta tabla existe para no ser.
    CONSTRAINT ck_cobertura_referencia_coherente
        CHECK ((tipo = 'FINANCIADA'
            AND financiador_id IS NOT NULL AND financiador_codigo IS NOT NULL
            AND financiador_nombre IS NOT NULL AND financiador_tipo IS NOT NULL
            AND plan_id IS NOT NULL AND plan_codigo IS NOT NULL AND plan_nombre IS NOT NULL
            AND requeria_autorizacion IS NOT NULL AND requeria_credencial IS NOT NULL
            AND referencia_capturada_el IS NOT NULL)
            OR (tipo = 'PARTICULAR'
            AND financiador_id IS NULL AND financiador_codigo IS NULL
            AND financiador_nombre IS NULL AND financiador_tipo IS NULL
            AND plan_id IS NULL AND plan_codigo IS NULL AND plan_nombre IS NULL
            AND requeria_autorizacion IS NULL AND requeria_credencial IS NULL
            AND copago IS NULL AND moneda IS NULL AND referencia_capturada_el IS NULL)),

    -- Una cobertura PARTICULAR no tiene credencial que exhibir: no hay financiador ante quien
    -- estar afiliado. Sin este CHECK la fila admitiria un numero de afiliado huerfano.
    CONSTRAINT ck_cobertura_particular_sin_credencial
        CHECK (tipo <> 'PARTICULAR'
            OR (numero_afiliado IS NULL AND credencial_vigencia_hasta IS NULL)),

    -- INCLUSIVA: una cobertura de un solo dia es un estado real. Ver la cabecera.
    CONSTRAINT ck_cobertura_vigencia_coherente
        CHECK (vigencia_hasta IS NULL OR vigencia_hasta >= vigencia_desde),

    -- Importe y moneda viajan juntos o no viajan, mismo criterio que plan_cobertura (V41).
    CONSTRAINT ck_cobertura_copago_con_moneda
        CHECK ((copago IS NULL AND moneda IS NULL)
            OR (copago IS NOT NULL AND moneda IS NOT NULL)),

    CONSTRAINT ck_cobertura_copago_no_negativo
        CHECK (copago IS NULL OR copago >= 0),

    CONSTRAINT ck_cobertura_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- El historial de un paciente y la resolucion de la cobertura del dia: filtra por tenant y
    -- persona, ordena por vigencia descendente desde el indice.
    INDEX ix_cobertura_persona (organization_id, persona_id, active, vigencia_desde)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Cobertura de un paciente (M08). Guarda una COPIA CONGELADA del plan al firmar, no un puntero: una cobertura firmada ayer no cambia porque hoy editen el plan. PARTICULAR es la ausencia de plan. Propietario: modulo person';


-- =====================================================================================
-- La fila que existe unicamente para ser bloqueada.
--
-- Bloquear las filas de `cobertura_paciente` que YA existen no impide que otra transaccion
-- INSERTE una nueva en el hueco, que es exactamente el caso a evitar, y MySQL 8.4 no tiene
-- exclusion constraints. Hace falta una fila que siempre exista y que todas las escrituras
-- de coberturas de esa persona se disputen. Es la misma solucion que `agenda_sede` (V30) y
-- que `consultorio_calendario` (V16), y NO se reusa ninguna de las dos: pertenecen a otros
-- modulos y ArchUnit rechaza que `person` las escriba.
--
-- Granularidad por PERSONA y no por organizacion: las dos reglas que serializa —el mismo
-- plan solapado y la segunda principal solapada— son invariantes de UN paciente. Un lock por
-- organizacion serializaria el padron entero para proteger algo que nunca cruza de paciente.
--
-- La fila se crea en una transaccion APARTE, con INSERT ... ON DUPLICATE KEY UPDATE. Crearla
-- perezosamente dentro de la transaccion que la bloquea produce deadlock entre las primeras N
-- escrituras concurrentes, y el try/catch no salva: atrapar una excepcion de persistencia no
-- des-marca la transaccion y Spring lanza UnexpectedRollbackException al commitear. Ya se
-- pago tres veces en este repositorio.
-- =====================================================================================

CREATE TABLE cobertura_persona_lock
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,

    organization_id BIGINT      NOT NULL COMMENT 'Tenant propietario. El lock nunca cruza de organizacion',
    persona_id      BIGINT      NOT NULL COMMENT 'Persona cuyas coberturas serializa esta fila',

    created_at      DATETIME(6) NOT NULL,

    CONSTRAINT pk_cobertura_persona_lock PRIMARY KEY (id),

    -- Es el unique que hace idempotente al INSERT ... ON DUPLICATE KEY UPDATE, y por lo tanto
    -- lo que evita el deadlock entre las primeras escrituras concurrentes.
    CONSTRAINT uk_cobertura_persona_lock UNIQUE (organization_id, persona_id),

    CONSTRAINT fk_cobertura_lock_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_cobertura_lock_persona
        FOREIGN KEY (persona_id) REFERENCES persona (id)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Una fila por persona cuyo unico proposito es ser bloqueada con FOR UPDATE. No guarda estado. Serializa las escrituras de coberturas de ese paciente: ningun unique puede expresar solapamiento de intervalos en MySQL 8.4. Propietario: modulo person';
