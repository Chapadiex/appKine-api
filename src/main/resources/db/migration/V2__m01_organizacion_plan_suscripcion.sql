-- =====================================================================================
-- AKINE-01.01 — Expandir: tenant, catalogo de planes y suscripcion.
--
-- Trazabilidad: RF-M01-001 (crear tenant con configuracion inicial y plan/suscripcion),
-- RF-M01-003 (transiciones de estado de la suscripcion), RF-M01-004 (limites y features),
-- RN-M01-002 (suspender o cambiar de plan jamas borra historicos).
--
-- Propietario de todas las tablas de esta migracion: modulo organization.
--
-- Convenciones heredadas de V1__baseline_tecnico.sql: utf8mb4 / utf8mb4_0900_ai_ci,
-- DATETIME(6) en UTC, baja logica con active + deleted_at, nombres pk_ / uk_ / fk_ / ix_.
--
-- Excepcion documentada a "toda tabla de negocio lleva organization_id":
--   organization    -> ES el tenant: su id es el organization_id del resto del sistema.
--   plan            -> catalogo comercial global de la plataforma, no dato de un tenant.
--   plan_limit      -> cuelga de plan.
--   plan_feature    -> cuelga de plan.
-- Cualquier otra tabla sin organization_id es un bug de aislamiento, no una simplificacion.
-- =====================================================================================

CREATE TABLE organization (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(160) NOT NULL COMMENT 'Nombre comercial del centro',
    slug            VARCHAR(64)  NOT NULL COMMENT 'Identificador legible para URLs y soporte',
    timezone        VARCHAR(64)  NOT NULL DEFAULT 'America/Argentina/Cordoba' COMMENT 'Zona IANA explicita para las reglas locales (ADR-0004)',
    active          TINYINT(1)   NOT NULL DEFAULT 1,
    deleted_at      DATETIME(6)  NULL COMMENT 'Instante UTC de la baja logica',
    version         BIGINT       NOT NULL DEFAULT 0 COMMENT 'Locking optimista (RNF-M01-003)',
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    CONSTRAINT pk_organization PRIMARY KEY (id),
    -- Unico GLOBAL deliberado: el slug es justamente lo que distingue un tenant de otro,
    -- no un dato dentro de un tenant.
    CONSTRAINT uk_organization_slug UNIQUE (slug)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Tenant raiz. Su id ES el organization_id del sistema. Sin columna status: el estado operativo deriva de la suscripcion.';

CREATE TABLE plan (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    code            VARCHAR(32)  NOT NULL COMMENT 'Clave estable consumida por el seed y por el spi de onboarding',
    name            VARCHAR(120) NOT NULL,
    active          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Un plan retirado se desactiva, nunca se borra: hay suscripciones que lo referencian',
    deleted_at      DATETIME(6)  NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    CONSTRAINT pk_plan PRIMARY KEY (id),
    CONSTRAINT uk_plan_code UNIQUE (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Catalogo global de plataforma. Sin organization_id: excepcion documentada, no es dato de un tenant.';

CREATE TABLE plan_limit (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    plan_id         BIGINT      NOT NULL,
    limit_code      VARCHAR(48) NOT NULL COMMENT 'Valores del enum LimitCode (organization.spi)',
    limit_value     INT         NULL COMMENT 'NULL = ilimitado',
    -- Baja logica (D-9 del challenge): quitar un limite de un plan no puede ser un DELETE
    -- fisico. Se pierde que incluia el plan cuando alguien se suscribio.
    -- Cuidado: uk_plan_limit no discrimina por active, asi que volver a habilitar un limite
    -- dado de baja se hace REACTIVANDO la fila existente, no insertando una nueva.
    active          TINYINT(1)  NOT NULL DEFAULT 1,
    deleted_at      DATETIME(6) NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    CONSTRAINT pk_plan_limit PRIMARY KEY (id),
    CONSTRAINT uk_plan_limit UNIQUE (plan_id, limit_code),
    CONSTRAINT fk_plan_limit_plan FOREIGN KEY (plan_id) REFERENCES plan (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Limite cuantitativo de un plan. La evaluacion es server-side (RF-M01-004) y solo frena altas nuevas (RN-M01-004).';

CREATE TABLE plan_feature (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    plan_id         BIGINT      NOT NULL,
    feature_code    VARCHAR(48) NOT NULL COMMENT 'Valores del enum FeatureCode (organization.spi)',
    -- Baja logica por D-9, con la misma advertencia que plan_limit sobre uk_plan_feature.
    active          TINYINT(1)  NOT NULL DEFAULT 1,
    deleted_at      DATETIME(6) NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    CONSTRAINT pk_plan_feature PRIMARY KEY (id),
    CONSTRAINT uk_plan_feature UNIQUE (plan_id, feature_code),
    CONSTRAINT fk_plan_feature_plan FOREIGN KEY (plan_id) REFERENCES plan (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Presencia de fila activa = feature habilitada para el plan.';

CREATE TABLE subscription (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    organization_id BIGINT      NOT NULL,
    plan_id         BIGINT      NOT NULL,
    status          VARCHAR(16) NOT NULL COMMENT 'ACTIVA | SUSPENDIDA | CANCELADA. CANCELADA es terminal',
    started_at      DATETIME(6) NOT NULL COMMENT 'Instante UTC del alta de la suscripcion',
    active          TINYINT(1)  NOT NULL DEFAULT 1,
    deleted_at      DATETIME(6) NULL,
    version         BIGINT      NOT NULL DEFAULT 0 COMMENT 'Locking optimista: dos transiciones concurrentes no pueden ganar las dos',
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    CONSTRAINT pk_subscription PRIMARY KEY (id),
    -- Exactamente una suscripcion por organizacion. El unique ya incorpora el alcance
    -- tenant por definicion.
    CONSTRAINT uk_subscription_organization UNIQUE (organization_id),
    CONSTRAINT fk_subscription_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_subscription_plan FOREIGN KEY (plan_id) REFERENCES plan (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Una suscripcion vigente por organizacion; la historia vive en subscription_transition. Tambien es la fila que se bloquea (SELECT FOR UPDATE) para serializar la evaluacion de limites.';

CREATE TABLE subscription_transition (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id   BIGINT       NOT NULL,
    subscription_id   BIGINT       NOT NULL,
    from_status       VARCHAR(16)  NULL COMMENT 'NULL = alta inicial de la suscripcion',
    to_status         VARCHAR(16)  NOT NULL,
    from_plan_id      BIGINT       NULL COMMENT 'NULL cuando la transicion no cambia el plan',
    to_plan_id        BIGINT       NULL COMMENT 'NULL cuando la transicion no cambia el plan',
    reason            VARCHAR(500) NULL COMMENT 'Obligatorio a nivel aplicacion al suspender o cancelar',
    actor_account_id  BIGINT       NULL COMMENT 'NULL = sistema. Referencia logica a identity, sin FK fisica (ADR-0001)',
    occurred_at       DATETIME(6)  NOT NULL COMMENT 'Instante UTC de la transicion',
    CONSTRAINT pk_subscription_transition PRIMARY KEY (id),
    CONSTRAINT fk_subs_transition_subscription FOREIGN KEY (subscription_id) REFERENCES subscription (id),
    INDEX ix_subs_transition_org (organization_id, subscription_id, occurred_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Historico append-only (RN-M01-002). Jamas UPDATE ni DELETE: sin created/updated ni baja logica porque una fila ya escrita no cambia. Su repositorio no expone metodos de modificacion.';
