-- =====================================================================================
-- AKINE-06.01 — Agregado Sesion: inicio, contexto y autosave (M14).
--
-- RF-M14-001, RF-M14-002, RF-M14-009; RN-M14-001..003.
--
-- ---------------------------------------------------------------------------------------
-- RECABLEADO DE DP-10, Y HAY QUE LEERLO ANTES QUE EL RESTO
--
-- El plan escribe esta etapa contra AKINE-05.04 (check-in) y AKINE-04.05 (consumo de
-- autorizaciones), y ademas afirma que "una sesion pertenece a un Caso". Las tres cosas
-- estan FUERA del Paquete B: 05.04, 04.05 y 04.03 (Caso Clinico) quedaron cortadas.
--
-- Por eso, y de forma explicita:
--
--   * La Sesion cuelga de la HISTORIA CLINICA, no de un Caso. La HC existe desde 04.01, es
--     de la organizacion (DP-03) y es el contexto longitudinal del paciente. Cuando 04.03
--     traiga el Caso, se agrega `caso_id` NULLABLE y las sesiones viejas siguen siendo
--     legibles; hacerlo al reves —inventar hoy una tabla de casos vacia para que la FK
--     apunte a algo— seria construir la etapa cortada a medias.
--   * La Sesion arranca desde el TURNO, sin paso de recepcion. `turno_id` es opcional
--     porque RF-M14-002 admite atencion sin turno.
--
-- ---------------------------------------------------------------------------------------
-- LAS CUATRO COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. SESION NO ES TURNO, Y ESTA TABLA LO SOSTIENE. DP-05 les da maquinas de estado
--    independientes: el Turno dice que un lugar quedo tomado y la Sesion dice que hubo
--    atencion. Ninguna transicion de turno prueba por si sola que la prestacion ocurrio, y
--    por eso `turno_id` es NULLABLE y no hay ninguna columna de turno duplicada aca. La
--    sesion se guarda su propio profesional y su propia oferta.
--
-- 2. EL UNIQUE SOBRE turno_id ES LA IDEMPOTENCIA DEL DOBLE INICIO. RN-M14-001: un turno
--    produce como mucho una sesion. A diferencia del solapamiento de turnos —que ningun
--    unique puede expresar, ver V30— aca la regla SI es igualdad, asi que la hace cumplir
--    el motor y no la aplicacion. `deleted_key` entra en la clave para que una sesion dada
--    de baja libere su turno.
--
-- 3. EL BORRADOR ES OPACO A PROPOSITO. `borrador` es JSON y esta tabla NO valida su forma:
--    que campos tiene una evaluacion es asunto de 06.02 y 06.03, y 06.03 quedo fuera del
--    Paquete B. Darle esquema hoy seria fijar en la base un formulario que todavia no esta
--    decidido, y cambiarlo despues costaria una migracion sobre datos clinicos. Lo que si
--    garantiza esta etapa es que el borrador se guarda, se versiona y no se pierde.
--
-- 4. `version` NO ES DECORACION: ES EL AUTOSAVE. Dos pestanas del mismo profesional sobre
--    la misma sesion son el caso normal, no el raro. Sin control optimista, la segunda
--    pisa a la primera en silencio y el profesional pierde lo que escribio sin enterarse.
--    El cliente manda la version que leyo y un 409 le dice que recargue.
-- =====================================================================================

CREATE TABLE sesion
(
    id                        BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id           BIGINT       NOT NULL,
    consultorio_id            BIGINT       NOT NULL COMMENT 'Sede donde se atiende. La HC es de la organizacion; la atencion ocurre en una sede.',

    historia_clinica_id       BIGINT       NOT NULL COMMENT 'Ver el recableado de DP-10 en la cabecera.',

    -- NULLABLE: RF-M14-002 admite atencion sin turno. El UNIQUE de abajo tolera varios NULL
    -- —en MySQL varios NULL no colisionan en un unique— asi que N sesiones sin turno
    -- conviven, que es exactamente lo que se necesita.
    turno_id                  BIGINT       NULL,

    oferta_id                 BIGINT       NOT NULL COMMENT 'Que se presto. Congelado al iniciar, igual que el intervalo del turno.',
    profesional_membership_id BIGINT       NOT NULL COMMENT 'Quien atiende. Decide quien puede editar el borrador.',

    estado                    VARCHAR(16)  NOT NULL COMMENT 'BORRADOR en esta etapa. CERRADA llega en 06.05.',

    iniciada_en               DATETIME(6)  NOT NULL,
    iniciada_por_cuenta_id    BIGINT       NOT NULL,

    -- Ver el punto 3 de la cabecera.
    borrador                  JSON         NULL,
    borrador_guardado_en      DATETIME(6)  NULL,

    created_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- Baja LOGICA. Regla maestra 10 y ADR-0011: nada clinico se borra fisicamente.
    deleted_at                DATETIME(6)  NULL,
    deleted_key               DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL,

    version                   BIGINT       NOT NULL DEFAULT 0 COMMENT 'Ver el punto 4 de la cabecera.',

    -- Un turno produce como mucho UNA sesion viva. Ver el punto 2.
    CONSTRAINT uk_sesion_turno
        UNIQUE (organization_id, turno_id, deleted_key),

    CONSTRAINT fk_sesion_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_sesion_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_sesion_historia_clinica
        FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id),

    CONSTRAINT fk_sesion_turno
        FOREIGN KEY (turno_id) REFERENCES turno (id),

    CONSTRAINT fk_sesion_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Atencion real. DP-05: es una maquina de estados independiente del Turno y de la Recepcion.';


-- La lectura longitudinal del paciente: todas sus sesiones, en orden. Es lo que la ficha del
-- paciente y el timeline de 04.02 van a pedir.
CREATE INDEX ix_sesion_historia
    ON sesion (organization_id, historia_clinica_id, iniciada_en);

-- La agenda del profesional en curso: que sesiones tiene abiertas hoy.
CREATE INDEX ix_sesion_profesional
    ON sesion (organization_id, profesional_membership_id, estado, iniciada_en);
