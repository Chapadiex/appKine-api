-- =====================================================================================
-- U-39 — La deuda de una clase POR_CLASE nace con la asistencia (RF-M18-008, RF-M19-009,
-- RN-M18-006, RN-M18-008, RF-M28-006 paso 7).
--
-- Decision del usuario del 17/10/2026: en una oferta POR_CLASE la deuda nace cuando la persona
-- ESTUVO (PRESENTE o PRESENTE_TARDE), y la ausencia no devenga (devenga_no_show = 0, el default
-- de V64). La oferta ya admitia esquema_cobro = 'POR_CLASE' con momento_devengo = 'ASISTENCIA'
-- en ck_oferta_politica_devengo (V64): lo que faltaba era el origen en obligacion.
--
-- QUE AGREGA
--
--   1. obligacion.asistencia_id y obligacion.clase_id, anulables, con FK. La deuda queda
--      "vinculada a la participacion" (RF-M18-008 paso 7) y la cancelacion de la clase encuentra
--      sus deudas sin pasar por activity (RF-M28-006 paso 7).
--   2. asistencia_viva_id, columna generada: el asistencia_id mientras la deuda esta viva (no
--      ANULADA y sin baja logica), NULL si no. Es lo que hace cumplir "no crear mas de una
--      obligacion por la misma participacion y concepto" (RF-M18-008) SIN impedir el re-devengo:
--      PRESENTE -> AUSENTE anula la deuda (no la borra) y AUSENTE -> PRESENTE devenga OTRA. Un
--      UNIQUE sobre asistencia_id a secas haria chocar la segunda contra la anulada; con la
--      columna generada, varias anuladas y una sola viva por asistencia. NULL no colisiona en
--      MySQL, que es justo lo que se quiere para las anuladas y para todo origen que no es una
--      asistencia.
--   3. El origen ASISTENCIA_CLASE en ck_obligacion_origen.
--
-- EL DROP CHECK, Y POR QUE NO PIERDE NADA (la leccion de V57)
--
-- ck_obligacion_origen nacio en V64 y ninguna migracion posterior lo toco (V101 y V103 solo lo
-- mencionan en comentarios). Reemplazarlo NO es aditivo: un CHECK nuevo que olvide un valor
-- deja afuera filas que hoy existen, como V57 le borro a V56 el valor que acababa de agregar.
-- Por eso el CHECK nuevo repite LITERALMENTE las dos ramas de V64 —SESION y VENTA_PASE/
-- VENTA_ABONO— y suma la tercera. Las dos ramas viejas ganan "asistencia_id IS NULL AND
-- clase_id IS NULL", que toda fila existente cumple porque las columnas nacen NULL en esta
-- misma migracion. Lo verifica MigracionDeObligacionPorClaseIT contra el motor.
--
-- Va en UN solo ALTER: MySQL no tiene DDL transaccional, y con dos sentencias un fallo entre
-- el DROP y el ADD dejaria la tabla sin el CHECK.
--
-- Propietario: billing (obligacion). Las FK apuntan a tablas de activity, como
-- fk_obligacion_pase (V64): billing no las lee ni las escribe, solo guarda la referencia.
-- =====================================================================================

ALTER TABLE obligacion
    ADD COLUMN asistencia_id BIGINT NULL
        COMMENT 'La asistencia a clase que devengo la deuda (origen = ASISTENCIA_CLASE, RF-M18-008). NULL en todo otro origen.'
        AFTER pase_id,
    ADD COLUMN clase_id BIGINT NULL
        COMMENT 'La clase de esa asistencia. Desnormalizada para que la cancelacion de la clase (RF-M28-006 paso 7) encuentre sus deudas sin preguntarle a activity.'
        AFTER asistencia_id;

ALTER TABLE obligacion
    ADD COLUMN asistencia_viva_id BIGINT
        AS (CASE WHEN estado <> 'ANULADA' AND deleted_at IS NULL THEN asistencia_id END) STORED
        COMMENT 'asistencia_id mientras la deuda esta viva; NULL anulada, dada de baja o de otro origen. Solo existe para uk_obligacion_asistencia_viva: una deuda viva por asistencia, re-devengable despues de anulada.'
        AFTER clase_id;

ALTER TABLE obligacion
    ADD CONSTRAINT fk_obligacion_asistencia
        FOREIGN KEY (asistencia_id) REFERENCES asistencia_actividad (id),
    ADD CONSTRAINT fk_obligacion_clase
        FOREIGN KEY (clase_id) REFERENCES clase_programada (id);

-- La idempotencia del devengo por clase, gemela de uk_obligacion_prestacion (V36) y de
-- uk_obligacion_venta (V64). Es el cinturon del lado de billing: aunque dos registros de la
-- misma asistencia llegaran a devengar a la vez, el segundo choca aca.
ALTER TABLE obligacion
    ADD CONSTRAINT uk_obligacion_asistencia_viva
        UNIQUE (organization_id, asistencia_viva_id, responsable);

-- Las deudas de una clase que se cancela.
CREATE INDEX ix_obligacion_clase
    ON obligacion (organization_id, clase_id);

ALTER TABLE obligacion
    DROP CHECK ck_obligacion_origen,
    ADD CONSTRAINT ck_obligacion_origen
        CHECK (
            (origen = 'SESION' AND sesion_id IS NOT NULL AND pase_id IS NULL
                AND asistencia_id IS NULL AND clase_id IS NULL)
                OR (origen IN ('VENTA_PASE', 'VENTA_ABONO') AND sesion_id IS NULL AND pase_id IS NOT NULL
                    AND asistencia_id IS NULL AND clase_id IS NULL)
                OR (origen = 'ASISTENCIA_CLASE' AND sesion_id IS NULL AND pase_id IS NULL
                    AND asistencia_id IS NOT NULL AND clase_id IS NOT NULL
                    AND responsable = 'PACIENTE' AND concepto = 'PARTICULAR')
            );
