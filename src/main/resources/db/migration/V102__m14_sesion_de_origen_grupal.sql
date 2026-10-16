-- =====================================================================================
-- AKINE-08.05 — Atencion clinica grupal por participante (M14, M28).
--
-- Trazabilidad: RF-M14-011, RF-M14-012, RF-M14-013; RN-M14-007, RN-M14-009, RN-M14-010.
-- Diseño: docs/diseno/AKINE-08.05-atencion-grupal.md.
--
-- POR QUE NO HAY TABLA NUEVA
--
-- La atencion de un participante de una clase clinica ES una Sesion: la misma maquina de
-- estados, el mismo borrador, la misma evaluacion, los mismos tratamientos y mediciones, el
-- mismo cierre con sus dos numeradores y la misma enmienda. El plan lo dice en una linea:
-- "no duplicar el agregado Sesion". Un agregado propio obligaria a reescribir —y despues a
-- mantener en paralelo— todo M14, y la numeracion dentro del Caso (RN-M14-010) tendria dos
-- implementaciones.
--
-- Lo que esta migracion agrega es el ORIGEN de la atencion, que hasta hoy solo podia ser un
-- turno o nada. Tres columnas en `sesion`, que es de `encounter`, y ninguna otra tabla tocada.
--
-- ---------------------------------------------------------------------------------------
-- LAS TRES COLUMNAS, Y POR QUE TRES
--
--   * `derivacion_id` — la derivacion clinica (08.04, V63) de la que cuelga la atencion. Es
--     el ancla: dice de que participacion, de que persona y de que Caso. FK a
--     `derivacion_clinica`, que es de `clinical`: va en la MISMA direccion que la
--     dependencia de codigo (`encounter -> clinical.spi`), igual que `fk_sesion_historia_clinica`
--     de V33. El motor impide asi que la Sesion apunte a una derivacion que no existe.
--   * `participacion_id` — `asistencia_actividad.id`, redundante con la derivacion y NO sobra:
--     el panel de la clase (activity) pregunta "que atenciones hay de estas asistencias" y la
--     respuesta sale de esta tabla sin pasar por `clinical`, que audita cada lectura. SIN FK:
--     `asistencia_actividad` es de `activity`, y `activity -> encounter.spi` es la dependencia
--     de codigo que esta etapa introduce. Una FK hacia alla iria en contra (mismo argumento que
--     V63 punto 4 con `derivacion_clinica.participacion_id`).
--   * `clase_id` — la clase programada, sin FK por el mismo motivo. Es el filtro del panel y
--     lo que el hecho del cierre lleva para que `billing` sepa que la atencion es de origen
--     grupal (ver el diseño §7: hoy no devenga).
--
-- ---------------------------------------------------------------------------------------
-- UNA SESION POR DERIVACION, O SEA POR PARTICIPANTE Y CASO
--
-- `uk_sesion_derivacion (organization_id, derivacion_id, deleted_key)`. La derivacion ya es
-- unica por participacion y Caso vigente (`uk_derivacion_destino`, V63), asi que una Sesion
-- viva por derivacion es exactamente "una Sesion por participante/caso" del plan. Es el
-- RESPALDO de la idempotencia, no el mecanismo: el inicio serializa sobre la fila de la
-- asistencia (FOR UPDATE, READ_COMMITTED) y relee antes de insertar, como la derivacion. MySQL
-- no aplica el unique con `derivacion_id` NULL, asi que las sesiones de turno no se estorban.
--
-- ---------------------------------------------------------------------------------------
-- LOS DOS CHECK
--
--   * `ck_sesion_origen_grupal_completo`: las tres columnas van juntas o no va ninguna. Una
--     Sesion con clase y sin derivacion seria una atencion grupal sin la puerta clinica que
--     08.04 exige, y una con derivacion y sin clase dejaria al panel sin poder encontrarla.
--   * `ck_sesion_origen_grupal_sin_turno_con_caso`: una atencion de origen grupal no tiene
--     turno —Turno != Clase (DP-05)— y SIEMPRE tiene Caso: derivar es elegir el Caso, y la
--     Sesion se numera dentro de el (RN-M14-001, RN-M14-010). La aplicacion impide cambiarlo
--     (409 `caso-de-atencion-grupal`); el CHECK es la red si algun camino lo olvida.
--
-- Nombres con la tabla adelante: en MySQL los nombres de FK y de CHECK son unicos POR
-- ESQUEMA (la leccion de V64).
--
-- LO QUE NO HACE: no toca `derivacion_clinica` ni `asistencia_actividad`, no hace backfill (no
-- hay ninguna Sesion de origen grupal anterior a esta etapa) y no agrega estado nuevo.
-- =====================================================================================

ALTER TABLE sesion
    ADD COLUMN derivacion_id BIGINT NULL
        COMMENT 'AKINE-08.05. Derivacion clinica (V63) de la que cuelga una atencion de origen grupal. NULL = atencion de turno o sin turno.'
        AFTER turno_id,
    ADD COLUMN participacion_id BIGINT NULL
        COMMENT 'AKINE-08.05. asistencia_actividad.id, sin FK: es de activity. Redundante con la derivacion, para el panel de la clase.'
        AFTER derivacion_id,
    ADD COLUMN clase_id BIGINT NULL
        COMMENT 'AKINE-08.05. clase_programada.id, sin FK: es de activity. Filtro del panel y marca de origen grupal del cierre.'
        AFTER participacion_id,

    ADD CONSTRAINT uk_sesion_derivacion
        UNIQUE (organization_id, derivacion_id, deleted_key),

    ADD CONSTRAINT fk_sesion_derivacion_clinica
        FOREIGN KEY (derivacion_id) REFERENCES derivacion_clinica (id),

    ADD CONSTRAINT ck_sesion_origen_grupal_completo
        CHECK ((derivacion_id IS NULL AND participacion_id IS NULL AND clase_id IS NULL)
            OR (derivacion_id IS NOT NULL AND participacion_id IS NOT NULL AND clase_id IS NOT NULL)),

    ADD CONSTRAINT ck_sesion_origen_grupal_sin_turno_con_caso
        CHECK (derivacion_id IS NULL OR (turno_id IS NULL AND caso_id IS NOT NULL));

-- El panel de la clase: las atenciones de una clase, en la sede.
CREATE INDEX ix_sesion_clase
    ON sesion (organization_id, consultorio_id, clase_id);
