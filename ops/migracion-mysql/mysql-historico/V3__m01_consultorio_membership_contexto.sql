-- =====================================================================================
-- AKINE-01.01 — Expandir: consultorio minimo, membership, contexto activo por cuenta y
-- registro de idempotencia del onboarding.
--
-- Trazabilidad: RF-M01-002 (memberships con rol y vigencia), RF-M01-005 (seleccion de
-- contexto persistida), RN-M01-001/003 (todo se resuelve en el tenant activo y lo valida
-- el backend), ADR-0008 (alta compuesta idempotente), ADR-0009 (contexto Org+Consultorio).
--
-- Propietario de todas las tablas: modulo organization.
--
-- Decisiones transversales F1 aplicadas:
--   T-4: la tabla se llama membership (NO organization_membership) y nace ya con
--        consultorio_id NULL. NULL = alcance organizacion. 01.03 la expande con estado y
--        grants; asi no hay un RENAME futuro, que ADR-0007 prohibe.
--   T-5 / B-1: is_founder es un ATRIBUTO, no un rol. El rol del propietario es ORG_ADMIN,
--        valor de la matriz de permisos aprobada. OWNER y ADMIN no existen.
--   B-4: request_hash en organization_onboarding, para detectar la misma Idempotency-Key
--        con un payload distinto.
--
-- Sin FK fisicas hacia tablas del modulo identity: account_id es referencia logica
-- (ADR-0001, ownership por modulo). La integridad la sostiene la aplicacion.
-- =====================================================================================

CREATE TABLE consultorio (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id BIGINT       NOT NULL,
    name            VARCHAR(160) NOT NULL,
    active          TINYINT(1)   NOT NULL DEFAULT 1,
    deleted_at      DATETIME(6)  NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    CONSTRAINT pk_consultorio PRIMARY KEY (id),
    CONSTRAINT uk_consultorio_org_name UNIQUE (organization_id, name),
    CONSTRAINT fk_consultorio_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    INDEX ix_consultorio_org_active (organization_id, active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Sede minima, lo que exige el onboarding (ADR-0008/0009). La configuracion completa la EXPANDE la etapa 02.01 (M03), no la reemplaza.';

CREATE TABLE membership (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    organization_id BIGINT      NOT NULL,
    consultorio_id  BIGINT      NULL COMMENT 'NULL = la membership alcanza a toda la organizacion (T-4)',
    account_id      BIGINT      NOT NULL COMMENT 'Referencia logica a identity.account: sin FK fisica, ownership por modulo',
    role_code       VARCHAR(48) NOT NULL COMMENT 'Valor de la matriz de permisos aprobada: PLATFORM_ADMIN | ORG_ADMIN | CONSULTORIO_ADMIN | PROFESIONAL | ADMINISTRATIVO | PACIENTE. La evaluacion fina es de 01.03',
    is_founder      TINYINT(1)  NOT NULL DEFAULT 0 COMMENT 'Atributo, no rol: marca al propietario que dio de alta la organizacion. 01.03 lo usa para el invariante "el fundador no puede ser desvinculado por otro admin"',
    valid_from      DATETIME(6) NOT NULL COMMENT 'Instante UTC de inicio de vigencia',
    valid_until     DATETIME(6) NULL COMMENT 'NULL = sin fin',
    active          TINYINT(1)  NOT NULL DEFAULT 1,
    deleted_at      DATETIME(6) NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    CONSTRAINT pk_membership PRIMARY KEY (id),
    -- En 01.01 una cuenta tiene a lo sumo una membership por organizacion. Si 01.03
    -- necesita memberships acotadas por sede o re-vinculacion con historial, expande este
    -- unique agregando el discriminador que corresponda.
    CONSTRAINT uk_membership_org_account UNIQUE (organization_id, account_id),
    CONSTRAINT fk_membership_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_membership_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    -- Resolucion de contextos autorizados de una cuenta: se ejecuta en CADA request
    -- (sin cache, ventana de revocacion cero), asi que tiene que ser un index seek.
    INDEX ix_membership_account (account_id, active),
    INDEX ix_membership_org_active (organization_id, active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Vinculo contextual entre una cuenta y una organizacion. Identidad global != membership contextual != habilitacion profesional.';

CREATE TABLE account_active_context (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    account_id      BIGINT      NOT NULL COMMENT 'Referencia logica a identity.account',
    organization_id BIGINT      NOT NULL,
    consultorio_id  BIGINT      NOT NULL,
    updated_at      DATETIME(6) NOT NULL COMMENT 'Instante UTC de la ultima seleccion',
    CONSTRAINT pk_account_active_context PRIMARY KEY (id),
    -- Un contexto activo global por cuenta: el sujeto es la cuenta, que es cross-org por
    -- naturaleza. Excepcion documentada al unique con alcance tenant; la fila igual lleva
    -- organization_id para poder auditarla.
    CONSTRAINT uk_active_context_account UNIQUE (account_id),
    CONSTRAINT fk_active_context_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_active_context_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Puntero de ultima seleccion, NO autoridad por request: el contexto efectivo es el del token, revalidado contra la base. Sin baja logica porque es reemplazable; el historial de cambios de contexto es audit_event.';

CREATE TABLE organization_onboarding (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash    VARCHAR(64) NULL COMMENT 'SHA-256 del payload canonico. NULL cuando el alta entra por spi y no por HTTP: ahi no hay payload que comparar (B-4)',
    account_id      BIGINT      NOT NULL COMMENT 'Cuenta que resulta propietaria (o actor, si el alta la hizo el admin de plataforma)',
    organization_id BIGINT      NOT NULL COMMENT 'Resultado; se escribe en la misma transaccion que la organizacion',
    consultorio_id  BIGINT      NOT NULL COMMENT 'Resultado',
    membership_id   BIGINT      NOT NULL COMMENT 'Resultado',
    created_at      DATETIME(6) NOT NULL,
    CONSTRAINT pk_organization_onboarding PRIMARY KEY (id),
    -- Unico GLOBAL deliberado: la operacion es pre-tenant. Al reintentar, la organizacion
    -- todavia puede no existir, asi que la clave no puede tener alcance tenant.
    CONSTRAINT uk_onboarding_key UNIQUE (idempotency_key),
    CONSTRAINT fk_onboarding_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_onboarding_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_onboarding_membership FOREIGN KEY (membership_id) REFERENCES membership (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Registro de idempotencia del alta compuesta (ADR-0008). Append-only: el reintento LEE esta fila y devuelve el resultado del ganador.';
