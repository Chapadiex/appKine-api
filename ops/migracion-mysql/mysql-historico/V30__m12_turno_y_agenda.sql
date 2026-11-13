-- =====================================================================================
-- AKINE-05.02 — Reserva y confirmacion de Turno (M12).
--
-- V30 y no V29: DP-10 reservo V29 para AKINE-05.01, que termino sin crear ninguna tabla
-- —los slots se calculan al leer y no se persisten—. La version reservada queda sin usar a
-- proposito. Flyway no exige versiones contiguas y renumerar algo ya planificado vale menos
-- que un numero libre; es la misma decision que dejo V26 vacia.
--
-- RF-M12-002, RF-M12-003, RF-M04-004, RN-M12-001 y RN-M12-004.
--
-- ---------------------------------------------------------------------------------------
-- LAS CINCO COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. `agenda_sede` NO GUARDA NADA. Es una fila por sede cuyo unico proposito es ser tomada
--    con SELECT ... FOR UPDATE antes de leer un solo turno. MySQL 8.4 no tiene exclusion
--    constraints —son de PostgreSQL— asi que el solapamiento de dos turnos se valida en
--    aplicacion, y bloquear las filas que YA existen no impide que otra transaccion INSERTE
--    una en el hueco, que es exactamente el caso a evitar. Es la misma solucion que
--    `consultorio_calendario` le dio a la disponibilidad en 02.04, y no se reusa aquella
--    fila porque pertenece al modulo `resource`: un modulo no escribe ni bloquea las tablas
--    de otro.
--
--    EL LOCK SE TOMA ANTES DE LEER NADA. Leer primero y bloquear despues es una escalada
--    S -> X sobre las mismas filas y produce un deadlock entre dos reservas concurrentes.
--    Esta escrito en el diseno de 02.04 y vuelve a valer aca sin cambios.
--
--    GRANULARIDAD: una fila por SEDE, no por profesional. Serializa todas las reservas de
--    una sede entre si, que para un centro de kinesiologia son unas pocas por minuto. Se
--    eligio correccion sobre paralelismo: con una fila por recurso habria que tomar DOS
--    locks —profesional y espacio— y dos locks exigen un orden total para no deadlockear.
--    Si algun dia el volumen lo pide, la tabla admite filas por recurso sin migrar los
--    turnos.
--
-- 2. NO HAY NINGUN UNIQUE QUE IMPIDA EL SOLAPAMIENTO, Y NO PUEDE HABERLO. Un unique compara
--    igualdad, y dos turnos se pisan cuando sus INTERVALOS se cruzan: un turno de 09:00 a
--    10:00 y otro de 09:30 a 10:00 no comparten ningun valor de columna. Distintas ofertas
--    tienen distintas duraciones, asi que sus slots no caen en la misma grilla y el caso es
--    real, no teorico. Lo unico que hace cumplir la regla es la validacion bajo el lock del
--    punto 1. Quien toque este archivo buscando "el unique que falta" tiene que leer esto
--    antes.
--
-- 3. inicio Y fin SE GUARDAN LOS DOS, aunque `fin` sea derivable de la duracion de la
--    oferta. La duracion de una oferta se puede editar despues de reservado el turno, y un
--    turno ya reservado no cambia de horario porque alguien corrigio el catalogo. El turno
--    guarda su propio intervalo, congelado al reservar. Es el mismo criterio que M18 va a
--    usar con el importe.
--
-- 4. IDEMPOTENCIA CON HASH DEL PEDIDO, DESDE EL PRIMER DIA. `idempotency_key` sola no
--    alcanza: reusar la misma clave con un cuerpo distinto tiene que ser un conflicto
--    explicito y no devolver en silencio el turno anterior. AKINE-01.01 dejo ese escenario
--    diferido —el 7b— justamente porque `onboarding_registro` no guarda el hash y agregarlo
--    era migracion mas cambio de API. Aca se guarda de entrada para no repetir la deuda.
--
-- 5. EL TURNO CUELGA DE `persona`, NO DE `perfil_paciente`. RF-M07-010 y 03.01: Persona y
--    Paciente son cosas distintas y la unica forma de ser paciente es tener perfil. La FK
--    apunta a `persona` porque es la identidad, y que esa persona tenga perfil vigente lo
--    valida la aplicacion contra `person.spi.PacienteDirectory`. Poner la FK contra
--    `perfil_paciente` haria que dar de baja el perfil rompiera turnos historicos.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- agenda_sede — punto de serializacion. Ver el punto 1 de la cabecera.
-- -------------------------------------------------------------------------------------
CREATE TABLE agenda_sede
(
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id BIGINT       NOT NULL,
    consultorio_id  BIGINT       NOT NULL,

    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_agenda_sede
        UNIQUE (organization_id, consultorio_id),

    CONSTRAINT fk_agenda_sede_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_agenda_sede_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Fila por sede que sirve unicamente para serializar las reservas con FOR UPDATE. No guarda estado.';


-- -------------------------------------------------------------------------------------
-- turno — la reserva. NO es la prestacion: DP-05 separa Turno, Recepcion y Sesion, y
-- ninguna transicion administrativa prueba por si sola que la atencion ocurrio.
-- -------------------------------------------------------------------------------------
CREATE TABLE turno
(
    id                        BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id           BIGINT       NOT NULL,
    consultorio_id            BIGINT       NOT NULL,
    oferta_id                 BIGINT       NOT NULL,
    persona_id                BIGINT       NOT NULL COMMENT 'Identidad del paciente. Ver el punto 5 de la cabecera.',

    -- Nullable porque la oferta puede no requerir profesional (requiere_profesional = 0).
    profesional_membership_id BIGINT       NULL,

    -- Nullable por lo mismo, y ademas porque AKINE-05.01 NO lo elige: el motor de slots solo
    -- verifica que exista un espacio habilitado. Quien lo clava es esta etapa, dentro de la
    -- transaccion que crea el turno y bajo el lock de agenda_sede.
    espacio_id                BIGINT       NULL,

    inicio                    DATETIME(6)  NOT NULL COMMENT 'UTC. Congelado al reservar; ver el punto 3.',
    fin                       DATETIME(6)  NOT NULL COMMENT 'UTC, EXCLUSIVO.',

    estado                    VARCHAR(16)  NOT NULL COMMENT 'RESERVADO o CONFIRMADO en esta etapa. El resto del ciclo llega en 05.03.',

    idempotency_key           VARCHAR(80)  NULL,
    request_hash              CHAR(64)     NULL COMMENT 'SHA-256 del pedido. Ver el punto 4.',

    reservado_por_cuenta_id   BIGINT       NOT NULL,
    reservado_en              DATETIME(6)  NOT NULL,
    confirmado_en             DATETIME(6)  NULL,

    created_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- Baja LOGICA. RN-M12 y ADR-0011: un turno cancelado conserva su fila y su historia; la
    -- cancelacion en si —con motivo y actor— es de 05.03. Aca la columna existe para que el
    -- unique de idempotencia y las consultas de solapamiento ya la contemplen, y para que
    -- 05.03 no tenga que migrar la tabla.
    deleted_at                DATETIME(6)  NULL,
    deleted_key               DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL,

    version                   BIGINT       NOT NULL DEFAULT 0,

    -- Misma clave, mismo tenant, un solo turno. El reintento de un doble click devuelve el
    -- turno ya creado en vez de crear el segundo.
    CONSTRAINT uk_turno_idempotencia
        UNIQUE (organization_id, idempotency_key),

    CONSTRAINT fk_turno_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_turno_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_turno_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),

    CONSTRAINT fk_turno_persona
        FOREIGN KEY (persona_id) REFERENCES persona (id),

    CONSTRAINT fk_turno_espacio
        FOREIGN KEY (espacio_id) REFERENCES espacio (id),

    CONSTRAINT ck_turno_intervalo
        CHECK (fin > inicio),

    -- Un turno con clave de idempotencia guarda el hash de su pedido, y uno sin clave no
    -- guarda ninguno. Sin este CHECK, una fila con clave y sin hash pasaria el control de
    -- reuso sin comparar nada — que es precisamente el agujero del escenario diferido 7b.
    CONSTRAINT ck_turno_idempotencia_completa
        CHECK ((idempotency_key IS NULL AND request_hash IS NULL)
            OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Reserva de un slot. No es la prestacion: DP-05 separa Turno, Recepcion y Sesion.';


-- El indice que sostiene la consulta de solapamiento, que es la que decide la correctitud de
-- toda la etapa: "que turnos vivos de este profesional cruzan este intervalo". Lleva
-- deleted_key para que los cancelados no entren y no haya que filtrarlos despues.
CREATE INDEX ix_turno_profesional_intervalo
    ON turno (organization_id, profesional_membership_id, deleted_key, inicio, fin);

-- La misma consulta para el espacio.
CREATE INDEX ix_turno_espacio_intervalo
    ON turno (organization_id, espacio_id, deleted_key, inicio, fin);

-- Cupo de un slot grupal: cuantos turnos vivos hay para esta oferta a esta hora.
CREATE INDEX ix_turno_oferta_inicio
    ON turno (organization_id, oferta_id, inicio, deleted_key);

-- La agenda del paciente, que es la lectura que 05.03 y la pantalla van a pedir.
CREATE INDEX ix_turno_persona
    ON turno (organization_id, persona_id, inicio);
