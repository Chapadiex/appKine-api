-- =====================================================================================
-- AKINE F-4 — Obligacion del financiador, coseguro y snapshot de convenio (M16, M18, M21).
--
-- RF-M18-001, RF-M18-002; RN-M18-002; RN-M16-003, RN-M16-004, RN-M16-005, RN-M16-008;
-- RF-M21-001, RF-M21-003. Diseno: docs/diseno/AKINE-F-4-obligacion-financiador.md.
--
-- Reservada como V72; quedo por debajo de V76 (ya en main) y Flyway corre sin outOfOrder,
-- asi que entra como V77 y V72 queda vacia.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE ESTA MIGRACION CONECTA
--
-- V36 reservo `snapshot_convenio_id` y `snapshot_arancel_id` en NULL ("es donde 03.05 se
-- enchufa") y V56 agrego `financiador_id`. Nadie los escribio nunca: ObligacionDevengador
-- devengaba una sola deuda, a nombre del paciente, por el precio de la oferta (DP-10). F-4
-- recablea el devengado y esta migracion agrega lo que faltaba para COPIAR el arancel:
--
--   * `concepto`: que parte es la fila. PARTICULAR (sin cobertura: el precio de la oferta,
--     como desde 07.01), FINANCIADOR (la parte del arancel que paga el financiador) o
--     COSEGURO (la parte que paga el paciente bajo el convenio).
--   * El snapshot del convenio y del arancel ENTERO: identidad, texto, los tres importes y
--     los tres requisitos documentales que RF-M21-003 necesita para validar un lote.
--
-- No se toca `uk_obligacion_prestacion (sesion_id, responsable, deleted_key)`: las dos filas
-- de un convenio tienen responsables distintos, y PARTICULAR y COSEGURO comparten PACIENTE,
-- asi que el unique impide ademas que una sesion quede devengada de las dos formas a la vez.
--
-- ---------------------------------------------------------------------------------------
-- LAS COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. EL SNAPSHOT ES UNA COPIA, NO UNA REFERENCIA (RN-M16-003). Por eso no hay FK sobre las
--    columnas `snapshot_*`, igual que V36: lo que importa es que sigan diciendo lo mismo
--    aunque el convenio o el arancel se den de baja manana.
--
-- 2. LA FILA INCOHERENTE ES IMPOSIBLE, NO IMPROBABLE. Cinco CHECK: concepto cerrado;
--    concepto FINANCIADOR si y solo si responsable FINANCIADOR; PARTICULAR sin snapshot y los
--    otros dos con el snapshot ENTERO; la invariante del arancel tambien en la copia; y el
--    importe de la fila igual a la parte que le toca.
--
-- 3. LO YA DEVENGADO QUEDA COMO ESTA. Todas las filas existentes son PACIENTE con snapshot
--    NULL: reciben concepto PARTICULAR por el DEFAULT y cumplen las cinco CHECK. No se
--    re-devenga ninguna sesion cerrada (ver el diseno, seccion 8).
--
-- 4. DECIMAL, NUNCA FLOAT. Los tres importes copiados son DECIMAL(12,2), igual que en
--    convenio_arancel (V43): la suma de dos DECIMAL es exacta y no hay nada que redondear.
--
-- 5. NOMBRES DE CONSTRAINT CON LA TABLA ADELANTE. En MySQL los CHECK son unicos por
--    esquema, no por tabla (la leccion de V64).
-- =====================================================================================

ALTER TABLE obligacion
    ADD COLUMN concepto VARCHAR(16) NOT NULL DEFAULT 'PARTICULAR'
        COMMENT 'PARTICULAR (sin cobertura, precio de la oferta), FINANCIADOR (parte del arancel que paga el financiador) o COSEGURO (parte del arancel que paga el paciente)'
        AFTER financiador_id,

    ADD COLUMN practica_id BIGINT NULL
        COMMENT 'Practica facturada (DP-11: la realizada; la principal de la oferta si la sesion cerro sin tratamientos). NULL si no se pudo determinar'
        AFTER concepto,

    ADD COLUMN cobertura_id BIGINT NULL
        COMMENT 'Cobertura del paciente que se aplico. NULL en PARTICULAR'
        AFTER practica_id,

    ADD COLUMN alerta_practica_no_habilitada TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'DP-11: la practica facturada no esta entre las que la oferta declara. Alerta, nunca rechazo'
        AFTER cobertura_id,

    ADD COLUMN snapshot_plan_id BIGINT NULL
        COMMENT 'Plan del convenio aplicado, congelado'
        AFTER snapshot_arancel_id,

    ADD COLUMN snapshot_convenio_codigo VARCHAR(64) NULL
        COMMENT 'Codigo del convenio, inmutable: lo que explica bajo que acuerdo se devengo'
        AFTER snapshot_plan_id,

    ADD COLUMN snapshot_convenio_nombre VARCHAR(160) NULL
        COMMENT 'Nombre del convenio el dia de la prestacion'
        AFTER snapshot_convenio_codigo,

    ADD COLUMN snapshot_importe_total DECIMAL(12, 2) NULL
        COMMENT 'Lo que valia la practica bajo el convenio ese dia'
        AFTER snapshot_convenio_nombre,

    ADD COLUMN snapshot_importe_financiador DECIMAL(12, 2) NULL
        COMMENT 'La parte del financiador ese dia'
        AFTER snapshot_importe_total,

    ADD COLUMN snapshot_coseguro DECIMAL(12, 2) NULL
        COMMENT 'La parte del paciente ese dia'
        AFTER snapshot_importe_financiador,

    ADD COLUMN snapshot_requeria_orden TINYINT(1) NULL
        COMMENT 'RF-M21-003: el convenio exigia orden medica el dia de la prestacion'
        AFTER snapshot_coseguro,

    ADD COLUMN snapshot_requeria_autorizacion TINYINT(1) NULL
        COMMENT 'RF-M21-003: el convenio exigia autorizacion previa el dia de la prestacion'
        AFTER snapshot_requeria_orden,

    ADD COLUMN snapshot_requeria_credencial TINYINT(1) NULL
        COMMENT 'RF-M21-003: el convenio exigia credencial el dia de la prestacion'
        AFTER snapshot_requeria_autorizacion,

    ADD COLUMN snapshot_credencial_vencida TINYINT(1) NULL
        COMMENT 'La credencial de la cobertura estaba vencida el dia de la prestacion (B-2: alerta, no excluye)'
        AFTER snapshot_requeria_credencial,

    ADD COLUMN snapshot_vigente_el DATE NULL
        COMMENT 'Dia de la prestacion, en la zona de la sede, contra el que se resolvio el arancel'
        AFTER snapshot_credencial_vencida,

    ADD COLUMN snapshot_capturado_en DATETIME(6) NULL
        COMMENT 'Instante UTC en que se congelo el arancel'
        AFTER snapshot_vigente_el;


ALTER TABLE obligacion
    ADD CONSTRAINT ck_obligacion_concepto
        CHECK (concepto IN ('PARTICULAR', 'FINANCIADOR', 'COSEGURO')),

    -- La parte del financiador es la unica que se le debe a un financiador, y viceversa.
    ADD CONSTRAINT ck_obligacion_concepto_responsable
        CHECK ((concepto = 'FINANCIADOR') = (responsable = 'FINANCIADOR')),

    -- PARTICULAR no tiene convenio; FINANCIADOR y COSEGURO tienen el snapshot ENTERO. Un
    -- snapshot a medias es una deuda que despues nadie puede explicar ni presentar.
    ADD CONSTRAINT ck_obligacion_snapshot_convenio_coherente
        CHECK ((concepto = 'PARTICULAR'
                    AND snapshot_convenio_id IS NULL AND snapshot_arancel_id IS NULL
                    AND snapshot_plan_id IS NULL AND cobertura_id IS NULL
                    AND snapshot_importe_total IS NULL)
            OR (concepto <> 'PARTICULAR'
                    AND snapshot_convenio_id IS NOT NULL AND snapshot_arancel_id IS NOT NULL
                    AND snapshot_plan_id IS NOT NULL AND cobertura_id IS NOT NULL
                    AND practica_id IS NOT NULL
                    AND snapshot_convenio_codigo IS NOT NULL
                    AND snapshot_convenio_nombre IS NOT NULL
                    AND snapshot_importe_total IS NOT NULL
                    AND snapshot_importe_financiador IS NOT NULL
                    AND snapshot_coseguro IS NOT NULL
                    AND snapshot_requeria_orden IS NOT NULL
                    AND snapshot_requeria_autorizacion IS NOT NULL
                    AND snapshot_requeria_credencial IS NOT NULL
                    AND snapshot_credencial_vencida IS NOT NULL
                    AND snapshot_vigente_el IS NOT NULL
                    AND snapshot_capturado_en IS NOT NULL)),

    -- ck_arancel_partes_suman_total (V43), tambien en la copia.
    ADD CONSTRAINT ck_obligacion_snapshot_partes_suman
        CHECK (snapshot_importe_total IS NULL
            OR snapshot_importe_financiador + snapshot_coseguro = snapshot_importe_total),

    -- El importe de la fila ES la parte que le toca: el reparto no calcula, copia.
    ADD CONSTRAINT ck_obligacion_importe_segun_concepto
        CHECK ((concepto <> 'FINANCIADOR' OR importe_original = snapshot_importe_financiador)
            AND (concepto <> 'COSEGURO' OR importe_original = snapshot_coseguro));
