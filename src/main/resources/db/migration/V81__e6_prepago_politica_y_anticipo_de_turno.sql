-- =====================================================================================
-- AKINE E-6 — Prepago de recepcion como anticipo (DP-06 / ADR-0013)
--
-- Diseno: docs/diseno/AKINE-E-6-prepago.md
--
-- 1. LA POLITICA VIVE EN LA OFERTA (M27). `oferta_servicio_consultorio` ya es "como presta ESTE
--    consultorio ESTE servicio", asi que una columna ahi es exactamente "configurable por
--    Consultorio y por Oferta". Default 0: ninguna oferta existente cambia de comportamiento.
--
-- 2. EL PREPAGO ES UN ANTICIPO DE F-3 ATADO A UN TURNO. `cobro.turno_id` dice que el anticipo se
--    tomo en la recepcion de ese turno; no crea deuda (ADR-0013: no se inventa una obligacion que
--    todavia no existe). Al cerrar la sesion de ese turno, billing lo imputa a la deuda del
--    paciente que el cierre devengo.
--
-- 3. UN SOLO PREPAGO VIGENTE POR TURNO. Lo sostiene la base con una columna generada que vale
--    `turno_id` mientras el cobro no esta anulado y NULL despues: varios NULL no colisionan en un
--    UNIQUE de MySQL, asi que anular el prepago libera el turno para cobrar otro. Mismo patron que
--    `turno_vigente_id` de V78. Sin FK a `turno`: es de otro modulo (scheduling) y billing guarda
--    la referencia, como `obligacion.sesion_id`.
-- =====================================================================================

ALTER TABLE oferta_servicio_consultorio
    ADD COLUMN exige_prepago TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'E-6: la recepcion alerta si el paciente no abono antes de ser atendido. Nunca bloquea la atencion (DP-06)'
        AFTER admite_obra_social;

ALTER TABLE cobro
    ADD COLUMN turno_id BIGINT NULL
        COMMENT 'E-6: turno en cuya recepcion se tomo este anticipo (prepago). NULL en cualquier otro cobro'
        AFTER persona_id,
    ADD COLUMN turno_prepago_vigente BIGINT
        AS (CASE WHEN deleted_at IS NULL THEN turno_id END) STORED
        COMMENT 'E-6: turno_id mientras el prepago esta vigente; NULL si se anulo o no es prepago',
    ADD CONSTRAINT uk_cobro_prepago_turno_vigente
        UNIQUE (organization_id, turno_prepago_vigente);
