-- =====================================================================================
-- AKINE-01.02 — Identidad: registro de idempotencia del alta self-service.
--
-- Propietario: modulo identity.
--
-- Trazabilidad: ADR-0008 (alta compuesta Cuenta + Organizacion + Consultorio + Membership
-- en UNA transaccion), RF-M02-001, CA-M02-001-05 (reintento que no duplica).
--
-- POR QUE EXISTE SI organization_onboarding YA EXISTE (V3). Son dos registros de dos
-- operaciones distintas que comparten la clave del cliente. organization_onboarding hace
-- idempotente el alta DEL TENANT, y es lo unico que ese modulo puede saber. Esta tabla
-- hace idempotente el alta de la CUENTA y el envio de su correo: sin ella, un reintento
-- entraria a identity, encontraria el tenant ya creado por la clave y de todas formas
-- intentaria crear la cuenta y emitir un token de activacion nuevo. La clave del cliente
-- es la misma; el efecto que cada modulo tiene que no repetir, no.
--
-- clave_idempotencia es UNIQUE GLOBAL porque la operacion es PRE-TENANT: cuando el
-- cliente la genera todavia no existe ninguna organizacion a la que acotarla.
--
-- POR QUE GUARDA email_normalizado Y NO SOLO cuenta_id. El registro se escribe tambien
-- cuando el email YA existia y no se creo ninguna cuenta: la respuesta del endpoint es
-- uniforme (202) exista o no la cuenta, para no convertir el registro en un oraculo de
-- direcciones validas. En ese caso cuenta_id, organization_id y consultorio_id quedan
-- NULL, y el email normalizado es lo unico que permite reconocer el reintento.
-- =====================================================================================

CREATE TABLE onboarding_registro (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    clave_idempotencia VARCHAR(64)  NOT NULL COMMENT 'Idempotency-Key del cliente. Unique GLOBAL: la operacion nace antes que el tenant',
    email_normalizado  VARCHAR(320) NOT NULL,
    cuenta_id          BIGINT       NULL COMMENT 'NULL cuando el alta no creo cuenta porque el email ya existia',
    organization_id    BIGINT       NULL COMMENT 'Referencia logica al tenant creado, sin FK fisica (ADR-0001)',
    consultorio_id     BIGINT       NULL,
    estado             VARCHAR(20)  NOT NULL COMMENT 'COMPLETADO | DUPLICADO. No hay estado intermedio observable: todo el alta es una sola transaccion (ADR-0008)',
    created_at         DATETIME(6)  NOT NULL,
    CONSTRAINT pk_onboarding_registro PRIMARY KEY (id),
    CONSTRAINT uk_onboarding_registro_clave UNIQUE (clave_idempotencia),
    CONSTRAINT fk_onboarding_registro_cuenta FOREIGN KEY (cuenta_id) REFERENCES cuenta (id),
    -- Doble submit del mismo email sin reusar la clave: se detecta por aca.
    INDEX ix_onboarding_registro_email (email_normalizado, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Idempotencia del alta self-service, del lado de identity. Append-only: sin updated_at, sin baja logica, sin version.';
