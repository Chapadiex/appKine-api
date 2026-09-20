-- =====================================================================================
-- AKINE-08.01 — Clases programadas y agenda unificada (M28 / M12).
--
-- V58 y no V51..V57: esas versiones estan reservadas por etapas en vuelo en otros
-- worktrees (06.03, 06.06, 07.03, 06.04, 07.04 y 07.05). El numero se reserva ANTES de
-- escribir codigo porque V26 quedo vacia para siempre cuando 02.07 y 03.01 nacieron las
-- dos como V26: Flyway no arranca con versiones duplicadas.
--
-- RF-M12-009, RF-M12-011..013; RF-M28-001, RF-M28-005..006; RN-M28-001, RN-M28-002,
-- RN-M28-009.
--
-- ---------------------------------------------------------------------------------------
-- LAS CUATRO COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. NO HAY NINGUN UNIQUE QUE IMPIDA EL SOLAPAMIENTO, Y NO PUEDE HABERLO. Es la misma
--    advertencia que encabeza V30 y vale identica aca: un unique compara igualdad, y dos
--    eventos se pisan cuando sus INTERVALOS se cruzan. Una clase de 09:00 a 10:00 y un
--    turno de 09:30 a 10:00 no comparten un solo valor de columna, y MySQL 8.4 no tiene
--    exclusion constraints —son de PostgreSQL—.
--
--    LO QUE HACE CUMPLIR LA REGLA ES EL LOCK DE `agenda_sede`, LA MISMA FILA QUE USAN LOS
--    TURNOS. No se crea un segundo punto de serializacion: si la clase se serializara
--    contra una fila propia, las dos transacciones no se verian, las dos ganarian, y el box
--    quedaria doblemente vendido SIN QUE NADA FALLE. Que `agenda_sede` pertenezca a
--    `scheduling` y esta tabla a `activity` es acoplamiento deliberado, y viaja explicito
--    por `scheduling.spi.AgendaDeSede` en vez de escondido.
--
--    El lock se toma ANTES de leer nada y la transaccion va en READ_COMMITTED. Leer primero
--    y bloquear despues es una escalada S -> X y produce deadlock; y con REPEATABLE READ
--    InnoDB fija la foto en la primera lectura consistente —anterior al lock— y el lock deja
--    de servir para lo unico que sirve.
--
-- 2. UNA CLASE NO ES N TURNOS. CA-M28-001-06: la clase existe una sola vez cualquiera sea
--    el numero de participantes. Ninguna fila de `turno` la representa, y 08.02 NO va a
--    crear un turno por inscripto. Lo unico que comparten clase y turno es el lock.
--
-- 3. inicio Y fin SE GUARDAN LOS DOS, aunque `fin` sea derivable de la duracion de la
--    oferta. Editar el catalogo no puede mover una clase ya programada. Mismo criterio que
--    el punto 3 de V30 y que el importe congelado de M18.
--
-- 4. NO HAY COLUMNA `active` NI `cupo_ocupado`, Y LAS DOS AUSENCIAS SON DELIBERADAS.
--    `active` seria una segunda fuente de verdad sobre lo mismo que dice `estado`. Y la
--    ocupacion se cuenta al leer desde las inscripciones de 08.02: materializarla es una
--    segunda copia de la verdad, la misma decision que el timeline clinico de 04.02 y la
--    disponibilidad efectiva de 02.04.
--
--    La baja logica de esta tabla es `deleted_at` + `deleted_key` + `motivo_cancelacion`,
--    que es exactamente la forma que V30 le dio a `turno`. Una clase es un evento de
--    agenda, y es mas importante que los dos eventos de la misma grilla se lean igual entre
--    si que que una clase se lea como un espacio.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- clase_programada — el evento grupal unico. Ver los puntos 1, 2 y 4 de la cabecera.
-- -------------------------------------------------------------------------------------
CREATE TABLE clase_programada
(
    id                        BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id           BIGINT       NOT NULL,
    consultorio_id            BIGINT       NOT NULL COMMENT 'RN-M28-001: la clase pertenece a un consultorio.',

    oferta_id                 BIGINT       NOT NULL COMMENT 'RN-M28-001: pertenece a una Oferta GRUPAL. Que la modalidad sea GRUPAL lo valida la aplicacion contra offering.spi: la modalidad vive en otra tabla y un CHECK no puede leerla.',

    -- Nullables por lo mismo que en `turno`: la oferta puede no requerir profesional ni
    -- espacio (requiere_profesional / requiere_espacio de V24).
    profesional_membership_id BIGINT       NULL,
    espacio_id                BIGINT       NULL,

    titulo                    VARCHAR(120) NULL COMMENT 'Rotulo opcional de ESTA ocurrencia. El nombre comercial lo da la oferta; esto distingue "Pilates — grupo avanzado" de "Pilates — inicial".',

    inicio                    DATETIME(6)  NOT NULL COMMENT 'UTC. Congelado al programar; ver el punto 3.',
    fin                       DATETIME(6)  NOT NULL COMMENT 'UTC, EXCLUSIVO.',

    capacidad                 INT          NOT NULL COMMENT 'Capacidad PROPIA de la clase (RN-M28-002), congelada al programar. La efectiva se calcula al leer como el minimo entre esta, la de la oferta y la del espacio: ver el punto 4.',

    estado                    VARCHAR(16)  NOT NULL COMMENT 'PROGRAMADA o CANCELADA en esta etapa. Los estados operativos llegan en 08.03; agregar valores es aditivo.',

    idempotency_key           VARCHAR(80)  NULL,
    request_hash              CHAR(64)     NULL COMMENT 'SHA-256 del pedido. La clave sola no alcanza: reusarla con otro cuerpo tiene que ser un 409 explicito y no devolver en silencio la clase anterior.',

    programado_por_cuenta_id  BIGINT       NOT NULL,
    programado_en             DATETIME(6)  NOT NULL,
    reprogramado_en           DATETIME(6)  NULL,

    motivo_cancelacion        VARCHAR(300) NULL,
    cancelado_en              DATETIME(6)  NULL,
    cancelado_por_cuenta_id   BIGINT       NULL,

    created_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- Baja LOGICA (RN-M28-009 y la regla maestra 10). Cancelar conserva la fila y su
    -- historia. `deleted_key` existe porque varios NULL no colisionan en MySQL: con el
    -- centinela, un unique que lo incluya protege el historico y desprotege lo vigente.
    deleted_at                DATETIME(6)  NULL,
    deleted_key               DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL,

    version                   BIGINT       NOT NULL DEFAULT 0,

    -- Alcance ORGANIZACION y no sede, igual que uk_turno_idempotencia: una clave reusada
    -- apuntando a otra sede sigue siendo la misma clave.
    CONSTRAINT uk_clase_idempotencia
        UNIQUE (organization_id, idempotency_key),

    CONSTRAINT fk_clase_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_clase_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_clase_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),

    CONSTRAINT fk_clase_espacio
        FOREIGN KEY (espacio_id) REFERENCES espacio (id),

    CONSTRAINT ck_clase_intervalo
        CHECK (fin > inicio),

    -- Mismo criterio que espacio.capacidad (V19) y oferta.capacidad (V24): capacidad cero
    -- no es "menos cupo", es una baja, y la baja tiene su propia columna con motivo.
    -- Mayor a 1 porque una clase de capacidad 1 es un turno individual mal rotulado: es la
    -- misma regla que ck_oferta_grupal_capacidad.
    CONSTRAINT ck_clase_capacidad
        CHECK (capacidad > 1),

    -- Sin este CHECK, una fila con clave y sin hash pasaria el control de reuso sin comparar
    -- nada — que es precisamente el agujero del escenario diferido 7b de 01.01.
    CONSTRAINT ck_clase_idempotencia_completa
        CHECK ((idempotency_key IS NULL AND request_hash IS NULL)
            OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL)),

    -- Una clase cancelada declara por que. RN-M28-009: cancelaciones conservan trazabilidad.
    CONSTRAINT ck_clase_cancelacion_completa
        CHECK ((deleted_at IS NULL AND motivo_cancelacion IS NULL)
            OR (deleted_at IS NOT NULL AND motivo_cancelacion IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Evento grupal unico de agenda (M28). NO es N turnos: ver el punto 2 de la cabecera de V58.';


-- Los dos indices que sostienen la consulta de solapamiento, que es la que decide la
-- correctitud de toda la etapa. Llevan deleted_key para que las canceladas no entren y no
-- haya que filtrarlas despues. Son gemelos de ix_turno_profesional_intervalo y
-- ix_turno_espacio_intervalo, a proposito: la pregunta es la misma.
CREATE INDEX ix_clase_profesional_intervalo
    ON clase_programada (organization_id, profesional_membership_id, deleted_key, inicio, fin);

CREATE INDEX ix_clase_espacio_intervalo
    ON clase_programada (organization_id, espacio_id, deleted_key, inicio, fin);

-- La agenda del dia de la sede, que es lo que alimenta la proyeccion unificada de M12.
CREATE INDEX ix_clase_sede_inicio
    ON clase_programada (organization_id, consultorio_id, inicio);


-- -------------------------------------------------------------------------------------
-- clase_evento — historial APPEND-ONLY (RN-M28-009).
--
-- Sin `version`, sin `updated_at` y sin baja. Su puerto en Java tampoco declara `update`
-- ni `delete`: la ausencia de esas operaciones es la unica garantia real de que el
-- historial sea inmutable. Misma forma que turno_evento, caso_evento y
-- autorizacion_movimiento.
-- -------------------------------------------------------------------------------------
CREATE TABLE clase_evento
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id     BIGINT       NOT NULL,
    consultorio_id      BIGINT       NOT NULL,
    clase_id            BIGINT       NOT NULL,

    tipo                VARCHAR(20)  NOT NULL COMMENT 'CREACION, REPROGRAMACION o CANCELACION.',

    estado_anterior     VARCHAR(16)  NULL COMMENT 'NULL en la creacion: no habia estado previo.',
    estado_nuevo        VARCHAR(16)  NOT NULL,

    motivo              VARCHAR(300) NULL,

    -- El intervalo y los recursos ANTERIORES y NUEVOS. Es lo que hace que reprogramar sea
    -- trazable sin recrear la clase: CA-M12-012-06 y CA-M28-005-06.
    inicio_anterior     DATETIME(6)  NULL,
    fin_anterior        DATETIME(6)  NULL,
    inicio_nuevo        DATETIME(6)  NULL,
    fin_nuevo           DATETIME(6)  NULL,
    capacidad_anterior  INT          NULL,
    capacidad_nueva     INT          NULL,

    actor_cuenta_id     BIGINT       NULL,
    ocurrido_en         DATETIME(6)  NOT NULL,

    CONSTRAINT fk_clase_evento_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_clase_evento_clase
        FOREIGN KEY (clase_id) REFERENCES clase_programada (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Historial append-only de una clase programada (RN-M28-009). No se actualiza ni se borra.';


CREATE INDEX ix_clase_evento_clase
    ON clase_evento (organization_id, clase_id, ocurrido_en);
