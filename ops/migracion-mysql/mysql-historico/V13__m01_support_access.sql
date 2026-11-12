-- =====================================================================================
-- AKINE-01.03 — Acceso de soporte: la unica via por la que un PLATFORM_ADMIN opera DENTRO
-- de un tenant.
--
-- Trazabilidad: matriz de permisos §3 (valores "Soporte" y "Restringido"), §7 (invariante
-- "PLATFORM_ADMIN accede a datos de un tenant solo por acceso de soporte justificado y
-- auditado"), §8 (hueco declarado con etapa destino = 01.03), DP-03, ADR-0004, ADR-0020.
--
-- Propietario: modulo organization.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE DECIDIO EL USUARIO (D-3, 23/08/2026) Y ESTA TABLA MATERIALIZA
--
--   * Quien lo otorga: el propio PLATFORM_ADMIN, con motivo declarado. Es el modelo mas
--     debil de los tres evaluados y es el unico operable: los otros dos —cuatro ojos, o
--     aprobacion del ORG_ADMIN— no sirven para el caso que motiva el soporte, que es
--     justamente "el tenant no puede entrar".
--   * Vigencia: 4 horas. Ni 1 h (friccion operativa real) ni 24 h (ventana larga sobre datos
--     de salud ajenos).
--   * El tenant se entera: el otorgamiento escribe SUPPORT_ACCESS_GRANTED en audit_event CON
--     el organization_id del tenant, asi que aparece en su propia consulta de auditoria
--     (auditoria:read). Es lo correcto para datos de salud.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE valid_until ES NOT NULL, Y POR QUE ESA COLUMNA ES LA DIFERENCIA CON UN GRANT COMUN
--
-- membership_grant.valid_until admite NULL ("sin fin"). Aca no. La matriz §3 define "Soporte"
-- como "justificacion obligatoria, acotado en tiempo y auditado": un acceso de soporte sin fin
-- no es soporte, es una membership encubierta sin las restricciones de una membership. La
-- columna NOT NULL es lo que hace que ese "acotado en tiempo" sea un invariante de la base y
-- no una intencion del codigo.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE NO HAY UNIQUE
--
-- Dos accesos de soporte simultaneos del mismo PLATFORM_ADMIN al mismo tenant, con motivos
-- distintos, son legitimos: son dos incidentes. Lo que hay que impedir no es la fila repetida
-- sino el acceso sin motivo y sin fin, y de eso se ocupan los NOT NULL. Un unique aca
-- obligaria a inventar un discriminador que no discrimina nada.
-- =====================================================================================

CREATE TABLE support_access (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id       BIGINT       NOT NULL COMMENT 'Tenant al que se accede. NOT NULL: un acceso de soporte sin tenant no significa nada',
    account_id            BIGINT       NOT NULL COMMENT 'El PLATFORM_ADMIN que accede. Referencia logica a identity.cuenta, sin FK fisica (ADR-0001)',
    reason                VARCHAR(500) NOT NULL COMMENT 'Obligatorio. Sin motivo declarado no hay acceso de soporte (matriz §3)',
    granted_by_account_id BIGINT       NOT NULL COMMENT 'Quien lo otorgo. Con D-3 cerrada es autoconcedido, asi que hoy coincide con account_id; la columna existe para que el dia que sea de cuatro ojos no haya que migrar',
    valid_from            DATETIME(6)  NOT NULL,
    valid_until           DATETIME(6)  NOT NULL COMMENT 'NOT NULL: el acceso de soporte SIEMPRE vence. Es lo que lo distingue de un grant comun',
    revoked_at            DATETIME(6)  NULL COMMENT 'Revocacion anticipada. La fila nunca se borra: regla maestra 10',
    revoked_by_account_id BIGINT       NULL,
    active                TINYINT(1)   NOT NULL DEFAULT 1,
    deleted_at            DATETIME(6)  NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    CONSTRAINT pk_support_access PRIMARY KEY (id),
    CONSTRAINT fk_support_access_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    -- "Tiene este platform admin un acceso vigente a este tenant AHORA": es la pregunta que el
    -- evaluador de permisos hace en cada operacion amparada por soporte, asi que tiene que ser
    -- un seek.
    INDEX ix_support_access_org_account (organization_id, account_id, valid_until)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Acceso temporal de un PLATFORM_ADMIN a un tenant, con motivo obligatorio y vencimiento obligatorio. Habilita unicamente los valores "Soporte" y "Restringido" de la matriz §3. Cada operacion amparada deja SUPPORT_ACCESS_USED en audit_event.';
