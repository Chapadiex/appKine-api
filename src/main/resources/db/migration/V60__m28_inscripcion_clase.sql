-- =====================================================================================
-- AKINE-08.02 — Inscripciones, cupos y lista de espera (M28 / M12 / M26).
--
-- V60 y no V51..V59: V51-V58 estan tomadas por etapas en vuelo en otros worktrees y V59
-- esta reservada por 07.06. El numero se reserva ANTES de escribir codigo porque V26
-- quedo vacia para siempre cuando 02.07 y 03.01 nacieron las dos como V26: Flyway no
-- arranca con versiones duplicadas.
--
-- RF-M28-002..004; RF-M12-010; RF-M26-006..007; RN-M28-003..005, RN-M28-009.
--
-- ---------------------------------------------------------------------------------------
-- LAS CUATRO COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. NINGUN UNIQUE PUEDE EXPRESAR "QUEDAN VACANTES", Y NO ES UN OLVIDO. Un unique compara
--    igualdad; "hay lugar" es un CONTEO contra un tope. La tentacion es contar en el
--    servicio y despues insertar, y entre esas dos sentencias hay una ventana que ES el
--    bug: dos transacciones cuentan 7 de 8, las dos insertan, la clase queda con 9
--    personas en 8 lugares Y NADA FALLA. Releer no salva: con REPEATABLE READ la segunda
--    lectura devuelve la misma foto.
--
--    LO QUE HACE CUMPLIR LA REGLA ES EL UPDATE CONDICIONAL SOBRE clase_programada:
--
--        UPDATE clase_programada SET cupo_ocupado = cupo_ocupado + 1
--         WHERE id = ? AND organization_id = ? AND deleted_at IS NULL
--           AND estado = 'PROGRAMADA'
--           AND cupo_ocupado < LEAST(capacidad, :capacidadEfectiva)
--
--    Una fila afectada es "tenes el lugar". Cero filas es "no hay lugar". Una sola
--    sentencia, asi que no hay ventana: el UPDATE toma el lock X de la fila y lee la
--    version commiteada mas reciente —current read—, de modo que la segunda transaccion
--    evalua su predicado contra lo que la primera ya escribio. Es el mismo patron que
--    07.02 al imputar, 04.05 con la ultima unidad de una autorizacion, 07.03 con el saldo
--    de caja y 07.04 con el del lote.
--
-- 2. cupo_ocupado NO ES UNA CACHE DE ESTA TABLA: ES QUIEN OTORGA EL LUGAR. La fila de
--    inscripcion_clase es el RECIBO de un lugar ya otorgado, no la fuente. Por eso esta
--    columna revierte lo que V58 habia escrito —que la ocupacion se contaria al leer—:
--    sin columna, la regla no se puede expresar en la base, y un if del servicio no es
--    una regla. Es exactamente la forma de autorizacion.cantidad_consumida mas su ledger
--    (V50), con el mismo invariante verificable:
--
--        cupo_ocupado == COUNT(inscripciones de esa clase en un estado que consume cupo)
--
--    Ese invariante NO se comprobo todavia contra un motor. Esta anotado en
--    docs/tests-diferidos.md, igual que el gemelo de 04.05.
--
-- 3. LAS POSICIONES DE LA COLA SE ASIGNAN CON +1 SOBRE UN CONTADOR, NUNCA CON MAX+1.
--    ultima_posicion_espera solo sube, y no baja cuando alguien cancela: que la cola
--    tenga huecos es correcto, que dos personas tengan la posicion 4 no lo es. Es la
--    misma regla de los correlativos de sesion, de caso y de comprobante.
--
-- 4. LA BAJA ES LOGICA Y USA LA FORMA DE V30/V58, NO EL CUARTETO COMPLETO. Hay estado +
--    deleted_at + deleted_key + motivo_cancelacion, y NO hay `active`: una inscripcion
--    tiene una maquina de estados impuesta por RN-M28-004 y `active` diria lo mismo que
--    `estado`. deleted_key SI hace falta, y no por simetria: es lo que hace funcionar
--    uk_inscripcion_clase_persona, que permite que alguien que cancelo se vuelva a anotar
--    sin que su fila vieja estorbe NI DESAPAREZCA. Varios NULL no colisionan en MySQL, de
--    ahi el centinela.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- clase_programada — las dos columnas del asignador. Ver los puntos 2 y 3.
--
-- Se hace por ALTER y NO editando V58, aunque V58 nunca haya corrido contra un motor: una
-- migracion publicada no se toca, y la regla no admite excepciones comodas.
--
-- No hay backfill porque no hay una sola fila: V58 nunca se aplico. Si alguna vez llegara
-- a un motor antes que esta, el backfill seria
--   UPDATE clase_programada c SET cupo_ocupado =
--     (SELECT COUNT(*) FROM inscripcion_clase i WHERE i.clase_id = c.id
--       AND i.estado IN ('RESERVADA','CONFIRMADA','ASISTIO','AUSENTE'));
-- -------------------------------------------------------------------------------------
ALTER TABLE clase_programada
    ADD COLUMN cupo_ocupado           INT NOT NULL DEFAULT 0
        COMMENT 'Lugares OTORGADOS. No es un resumen de inscripcion_clase: es quien otorga el lugar, y el UPDATE condicional que lo incrementa es lo unico que impide la sobreventa. Ver el punto 1.',
    ADD COLUMN ultima_posicion_espera INT NOT NULL DEFAULT 0
        COMMENT 'Correlativo de la lista de espera. Solo sube, nunca con MAX+1. Ver el punto 3.';

-- Red de contencion, NO la regla. La regla es el WHERE del UPDATE. Esto convierte una
-- sobreventa que se escapara por cualquier camino futuro en un fallo ruidoso en vez de en
-- una clase con nueve personas en ocho lugares.
ALTER TABLE clase_programada
    ADD CONSTRAINT ck_clase_cupo_ocupado
        CHECK (cupo_ocupado >= 0 AND cupo_ocupado <= capacidad);


-- -------------------------------------------------------------------------------------
-- inscripcion_clase — un participante en una clase (RN-M28-003).
--
-- NO ES UN TURNO y ninguna fila de `turno` la acompana: CA-M28-001-06, la clase existe una
-- sola vez cualquiera sea el numero de participantes.
--
-- APUNTA A UNA PERSONA, NO A UN PACIENTE. Anotarse en una clase de yoga no convierte a
-- nadie en paciente ni crea un perfil_paciente: eso lo hace PerfilPacienteService y solo
-- el (RF-M07-010). La derivacion al circuito clinico es 08.04 y tiene etapa propia.
-- -------------------------------------------------------------------------------------
CREATE TABLE inscripcion_clase
(
    id                       BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id          BIGINT       NOT NULL,
    consultorio_id           BIGINT       NOT NULL COMMENT 'Va aunque sea derivable de la clase: derivarlo obliga a un join para filtrar por tenant y habilita la consulta sin join que ningun test de una etapa detecta, porque los tests de una etapa usan un solo tenant.',
    clase_id                 BIGINT       NOT NULL,

    persona_id               BIGINT       NOT NULL COMMENT 'Persona del padron (M07). NO se exige perfil de paciente vigente.',

    estado                   VARCHAR(16)  NOT NULL COMMENT 'Los seis de RN-M28-004: RESERVADA, CONFIRMADA, ASISTIO, AUSENTE, CANCELADA, LISTA_ESPERA. ASISTIO y AUSENTE los escribe 08.03.',

    posicion_espera          INT          NULL COMMENT 'Posicion de ingreso a la cola. SE CONSERVA despues de promover: es la prueba de que la prioridad se respeto (CA-M28-004-06 pide que sea reproducible).',

    idempotency_key          VARCHAR(80)  NULL,
    request_hash             CHAR(64)     NULL COMMENT 'SHA-256 del pedido. La clave sola no alcanza: reusarla con otro cuerpo tiene que ser un 409 explicito y no devolver en silencio la inscripcion anterior.',

    inscripto_por_cuenta_id  BIGINT       NOT NULL,
    inscripto_en             DATETIME(6)  NOT NULL,

    confirmada_en            DATETIME(6)  NULL,
    promovida_en             DATETIME(6)  NULL COMMENT 'Instante en que salio de la lista de espera hacia un lugar real.',

    motivo_cancelacion       VARCHAR(300) NULL,
    cancelada_en             DATETIME(6)  NULL,
    cancelada_por_cuenta_id  BIGINT       NULL,

    created_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    deleted_at               DATETIME(6)  NULL,
    deleted_key              DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL,

    version                  BIGINT       NOT NULL DEFAULT 0,

    -- Alcance ORGANIZACION y no clase, igual que uk_clase_idempotencia y uk_turno_idempotencia:
    -- una clave reusada apuntando a otra clase sigue siendo la misma clave.
    CONSTRAINT uk_inscripcion_idempotencia
        UNIQUE (organization_id, idempotency_key),

    -- LA UNICIDAD CLASE-PERSONA ACTIVA. Con deleted_key adentro, quien cancelo puede volver
    -- a anotarse y su fila anterior queda: el unique protege lo vigente y desprotege el
    -- historico, que es exactamente lo que se quiere.
    CONSTRAINT uk_inscripcion_clase_persona
        UNIQUE (organization_id, clase_id, persona_id, deleted_key),

    CONSTRAINT fk_inscripcion_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_inscripcion_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_inscripcion_clase
        FOREIGN KEY (clase_id) REFERENCES clase_programada (id),

    CONSTRAINT fk_inscripcion_persona
        FOREIGN KEY (persona_id) REFERENCES persona (id),

    -- Sin este CHECK, una fila con clave y sin hash pasaria el control de reuso sin comparar
    -- nada — el agujero del escenario diferido 7b de 01.01.
    CONSTRAINT ck_inscripcion_idempotencia_completa
        CHECK ((idempotency_key IS NULL AND request_hash IS NULL)
            OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL)),

    -- RF-M28-003: cancelar conserva historial y declara por que.
    CONSTRAINT ck_inscripcion_cancelacion_completa
        CHECK ((deleted_at IS NULL AND motivo_cancelacion IS NULL)
            OR (deleted_at IS NOT NULL AND motivo_cancelacion IS NOT NULL)),

    CONSTRAINT ck_inscripcion_posicion
        CHECK (posicion_espera IS NULL OR posicion_espera > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Un participante en una clase (M28, RN-M28-003). El cupo NO se hace cumplir aca: ver el punto 1 de la cabecera de V60.';


-- LA COLA. Es la consulta que decide la prioridad, y por eso ordena por (estado,
-- posicion_espera, id) con el tenant y la clase adelante. El id al final desempata cualquier
-- empate imposible y hace el orden total, que es lo que "reproducible" significa.
CREATE INDEX ix_inscripcion_cola
    ON inscripcion_clase (organization_id, clase_id, estado, posicion_espera, id);

-- "En que clases esta esta persona". La usa la ficha 360 y la usara 08.03.
CREATE INDEX ix_inscripcion_persona
    ON inscripcion_clase (organization_id, persona_id, id);
