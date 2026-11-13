-- =====================================================================================
-- AKINE-08.08 — Renovacion automatica de abonos y avisos de vencimiento de pases (M29, M26).
--
-- V103 la reservo el orquestador para esta rama (08.05 tiene V102 y 08.09 V104).
--
-- Plan 08.08 ("renovacion idempotente", "un periodo genera como maximo una obligacion",
-- "notificacion programada"), RF-M29-007, RF-M18-010, RF-M26-008 (CA-M26-008-06).
--
-- Propietario de todo lo que toca: modulo `activity`. Sin tablas nuevas.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE NO HACE, A PROPOSITO
--
-- 1. NO CREA UNA TABLA DE CICLOS. Cada periodo de un abono ya es una fila de pase_servicio con
--    su obligacion (V64, V101): renovar es vender la fila del periodo siguiente. Lo unico que falta
--    es saber que periodo renueva a cual, y eso es una columna.
-- 2. NO REESCRIBE NINGUN CHECK existente (la trampa de V57): solo agrega.
-- 3. NO PONE CHECK sobre renueva_pase_id: MySQL prohibe un CHECK sobre una columna que participa
--    de una foreign key con accion referencial, y la regla "solo un abono renueva" ya la hace
--    cumplir la aplicacion, que es la unica que escribe la columna.
-- ---------------------------------------------------------------------------------------

-- La renovacion automatica es OPT-IN, por abono: se pide al vender o despues con
-- PUT .../renovacion. Por defecto nada se renueva solo: generar deuda todos los meses sin que nadie
-- lo haya pedido es exactamente lo que el plan llama "renovacion tras cancelacion".
ALTER TABLE pase_servicio
    ADD COLUMN renovacion_automatica TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '1 si el job tiene que vender el periodo siguiente al acercarse el fin de este. Solo ABONO. Se apaga al renovar (el periodo nuevo la hereda) o cuando la renovacion no se puede hacer.'
        AFTER motivo_anulacion,
    ADD COLUMN renovacion_autorizada_por_cuenta_id BIGINT NULL
        COMMENT 'Quien pidio la renovacion automatica. Es el vendedor de los periodos que vende el job: el job no es una persona y la deuda tiene que tener a alguien que la haya autorizado.'
        AFTER renovacion_automatica,
    ADD COLUMN renovacion_omitida_motivo VARCHAR(280) NULL
        COMMENT 'Por que el job NO renovo (producto de baja, persona de baja, periodo ocupado). NULL si no se intento o si se renovo.'
        AFTER renovacion_autorizada_por_cuenta_id,
    ADD COLUMN renueva_pase_id BIGINT NULL
        COMMENT 'El periodo de abono que este renueva. NULL en una venta hecha por una persona.'
        AFTER renovacion_omitida_motivo,
    ADD COLUMN aviso_vencimiento_en DATETIME(6) NULL
        COMMENT 'Cuando se resolvio el aviso de vencimiento proximo (RF-M26-008): encolado, o sin correo a quien mandarlo. Uno por pase.'
        AFTER renueva_pase_id,
    ADD COLUMN aviso_saldo_bajo_en DATETIME(6) NULL
        COMMENT 'Cuando se resolvio el aviso de saldo reducido (RF-M26-008). Uno por pase.'
        AFTER aviso_vencimiento_en;

-- Un abono con renovacion automatica tiene quien la autorizo, y un pack no se renueva (no tiene
-- periodo). Nombre con la tabla adelante: los CHECK son unicos por esquema.
ALTER TABLE pase_servicio
    ADD CONSTRAINT ck_pase_servicio_renovacion_de_abono
        CHECK (renovacion_automatica = 0
            OR (tipo = 'ABONO' AND renovacion_autorizada_por_cuenta_id IS NOT NULL));

-- "Un periodo genera como maximo una obligacion" del lado de la renovacion: un periodo se renueva
-- una sola vez. Es el cinturon de la idempotencia del job (dos instancias, un reintento): el
-- tirante es el lock del pase y la relectura en READ_COMMITTED. Varios NULL no colisionan, que es
-- lo correcto: las ventas manuales no renuevan a nadie.
ALTER TABLE pase_servicio
    ADD CONSTRAINT uk_pase_servicio_renovacion UNIQUE (organization_id, renueva_pase_id);

ALTER TABLE pase_servicio
    ADD CONSTRAINT fk_pase_servicio_renueva_pase
        FOREIGN KEY (renueva_pase_id) REFERENCES pase_servicio (id);

-- El job de renovacion busca, en todos los tenants, los abonos con renovacion pedida cuyo periodo
-- se termina. Corre sin contexto de organizacion, como el de vencimiento: el tenant va al final.
CREATE INDEX ix_pase_renovacion
    ON pase_servicio (renovacion_automatica, vigencia_hasta, organization_id);
