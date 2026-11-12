-- =====================================================================================
-- AKINE-04.03 — El gancho del Caso en la Sesion (M14).
--
-- POR QUE ESTA MIGRACION EXISTE APARTE DE V47
--
-- `sesion` es propiedad del modulo `encounter` y las cinco tablas del Caso son de
-- `clinical`. La regla 1 de AGENT.md seccion 4 —cada tabla tiene UN modulo propietario y
-- ningun otro la toca— no admite que la migracion de un modulo altere la tabla de otro, asi
-- que esta etapa deja DOS migraciones y no una. Las dos versiones se reservaron antes de
-- escribir codigo (diseño seccion 10), que es la leccion de V26.
--
-- Trazabilidad: regla maestra 3 (las sesiones se numeran dentro del Caso), RF-M10-003,
-- RF-M14-002, ADR-0011, DP-10. V33 ya dejo este gancho anunciado en su cabecera: "cuando
-- 04.03 traiga el Caso, se agrega `caso_id` NULLABLE y las sesiones viejas siguen siendo
-- legibles".
--
-- ---------------------------------------------------------------------------------------
-- DOS CORRELATIVOS QUE CONVIVEN, Y NINGUN BACKFILL
--
-- `numero_sesion` —el correlativo por Historia Clinica que 06.05 asigna— NO se toca, no se
-- renumera y no se retira. Esta impreso en informes y visto por usuarios, y ademas es el
-- unico correlativo que existe para una sesion SIN caso, que RF-M14-002 admite.
--
-- `numero_en_caso` se agrega AL LADO. Un backfill tendria que inventar a que caso pertenece
-- cada sesion ya cerrada, y no hay dato que lo decida: el caso no existia cuando se
-- cerraron. Inventarlo es peor que no tenerlo, porque produce un numero que PARECE clinico y
-- no lo es. Las sesiones anteriores a esta etapa quedan sin caso y sin numero de caso, que
-- es exactamente lo que fueron.
--
-- CONSECUENCIA QUE HAY QUE SABER LEER: a partir de aca "la sesion 8" es ambigua si no se
-- dice de que. Los DTO devuelven los dos numeros con nombres distintos y ninguna pantalla
-- puede mostrar uno solo sin decir cual es.
--
-- Y una desviacion declarada, no un descuido: la regla maestra 3 se cumple SOLO para las
-- sesiones que tienen caso. Para las que no, no hay caso dentro del cual numerar. Su causa
-- es DP-10 —06.05 se ejecuto sin 04.03— y su alcance esta acotado a lo previo a esta etapa
-- (challenge seccion 4).
--
-- ---------------------------------------------------------------------------------------
-- EL NUMERO NO SE CALCULA ACA, SE PIDE
--
-- `encounter` obtiene `numero_en_caso` llamando a `clinical.spi.CasoDirectory` DENTRO de su
-- transaccion de cierre, y NO con un `SELECT MAX + 1` propio: el numerador vive en
-- `caso_sesion_numerador` (V47), que es de `clinical`, por el mismo motivo por el que el
-- ciclo de vida del caso no se exporta (challenge seccion 1).
--
-- Y hay un riesgo nuevo que ninguna etapa anterior tuvo: el cierre pasa a tomar DOS
-- numeradores en la misma transaccion —el de la historia (V35) y el del caso—. El orden
-- tiene que ser SIEMPRE el mismo, historia primero y caso despues, o dos cierres
-- concurrentes de sesiones de casos cruzados se bloquean mutuamente. Queda fijado en el
-- javadoc de `SesionService#cerrar` (challenge seccion 8.4).
--
-- ---------------------------------------------------------------------------------------
-- LAS DOS COLUMNAS VAN ATADAS, Y EL CHECK ES LO QUE LAS ATA
--
-- `ck_sesion_numero_en_caso` impide `numero_en_caso` sin `caso_id`. Un numero de caso
-- huerfano seria un correlativo que no numera dentro de nada: aparece en un informe, alguien
-- lo lee como "la octava sesion de su tratamiento" y no hay tratamiento al cual pertenezca.
--
-- El unique `(organization_id, caso_id, numero_en_caso)` es el RESPALDO del numerador, no el
-- mecanismo — mismo reparto que `uk_sesion_numero` en V35. MySQL no aplica el unique cuando
-- alguna columna es NULL, asi que las sesiones sin caso no se estorban entre si: pueden ser
-- miles con las dos columnas en NULL y ninguna choca.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE ESTA MIGRACION NO HACE
--
--   * NO pone `caso_id` NOT NULL. Exigir caso al reservar un turno o al abrir una sesion es
--     RF-M10-007, y HOY TODAS las sesiones del sistema no tienen caso: encender esa
--     validacion rompe la vertical que funciona. El gate es una etapa propia con su ventana
--     de migracion de datos (diseño seccion 8).
--   * NO agrega una FK hacia `caso_sesion_numerador` ni copia el contador a `sesion`.
-- =====================================================================================

ALTER TABLE sesion
    ADD COLUMN caso_id        BIGINT NULL
        COMMENT 'Caso Clinico al que pertenece la atencion. NULL = sesion sin caso, que RF-M14-002 admite y que es lo que son TODAS las sesiones anteriores a 04.03.'
        AFTER historia_clinica_id,

    ADD COLUMN numero_en_caso INT    NULL
        COMMENT 'Correlativo de la sesion DENTRO del caso (regla maestra 3). NULL cuando la sesion no tiene caso. Convive con numero_sesion, que es el correlativo por historia y no se renumera.'
        AFTER numero_sesion,

    -- El caso es de `clinical` y la FK cruza modulos, igual que las de V33 hacia
    -- `historia_clinica`, `turno` y `oferta_servicio_consultorio`: lo que la regla 1 prohibe
    -- es que `encounter` LEA o ESCRIBA esa tabla, no que el motor proteja la integridad.
    ADD CONSTRAINT fk_sesion_caso
        FOREIGN KEY (caso_id) REFERENCES caso_clinico (id),

    -- El respaldo del numerador. Ver la cabecera.
    ADD CONSTRAINT uk_sesion_numero_en_caso
        UNIQUE (organization_id, caso_id, numero_en_caso),

    -- No hay numero de caso sin caso. Ver la cabecera.
    ADD CONSTRAINT ck_sesion_numero_en_caso
        CHECK (numero_en_caso IS NULL
            OR (caso_id IS NOT NULL AND numero_en_caso > 0));


-- Las sesiones de un caso, en orden. Es la lectura que la ficha del caso va a necesitar en
-- 04.04 y la que hoy sostiene el filtro por caso del timeline clinico.
CREATE INDEX ix_sesion_caso
    ON sesion (organization_id, caso_id, cerrada_en);
