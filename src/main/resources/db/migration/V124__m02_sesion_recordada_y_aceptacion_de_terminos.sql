-- =====================================================================================
-- DP-45 punto 5 — "Mantener la sesion iniciada" y aceptacion de terminos en el alta.
--
-- Las dos piezas son de `identity` y, como todo ese modulo, NO llevan organization_id: la
-- cuenta y su sesion son una identidad global (ADR-0019).
--
-- 1. refresh_token.recordar_sesion
--    La sesion se abrio con recordarme=true. La vida ya esta en `expira_en`, que la rotacion
--    hereda y no corre (ADR-0017); la columna la hace visible y el sucesor la copia. Default 0:
--    toda sesion viva al aplicar la migracion es una sesion comun, que es lo que era.
--
-- 2. aceptacion_terminos
--    Una fila por cuenta y version aceptada, append-only. Tabla y no columnas en `cuenta`
--    porque al cambiar la version habra re-aceptaciones y la historia no se pisa.
-- =====================================================================================

ALTER TABLE refresh_token
    ADD COLUMN recordar_sesion TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'DP-45: sesion abierta con "mantener la sesion iniciada". La rotacion la copia; la vida la fija expira_en'
        AFTER user_agent;

CREATE TABLE aceptacion_terminos (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    cuenta_id   BIGINT      NOT NULL,
    version     VARCHAR(40) NOT NULL COMMENT 'Version de los terminos aceptada, la de akine.identity.terminos.version-vigente en ese momento',
    aceptado_en DATETIME(6) NOT NULL COMMENT 'Instante UTC de la aceptacion',
    origen      VARCHAR(20) NOT NULL COMMENT 'REGISTRO | INVITACION: por que camino nacio la cuenta',
    CONSTRAINT pk_aceptacion_terminos PRIMARY KEY (id),
    CONSTRAINT uk_aceptacion_terminos_cuenta_version UNIQUE (cuenta_id, version),
    CONSTRAINT fk_aceptacion_terminos_cuenta FOREIGN KEY (cuenta_id) REFERENCES cuenta (id),
    CONSTRAINT ck_aceptacion_terminos_origen CHECK (origen IN ('REGISTRO', 'INVITACION'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'DP-45: constancia de que una cuenta acepto una version de los terminos y condiciones. Append-only; sin organization_id (ADR-0019).';
