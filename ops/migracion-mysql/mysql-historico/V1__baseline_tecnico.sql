-- =====================================================================================
-- AKINE-00.01 — Migracion tecnica de baseline.
--
-- NO crea tablas funcionales. Los modelos de M01-M29 se crean unicamente en la etapa
-- que los aprueba, y cada tabla pertenece a un unico modulo propietario (AGENT.md sec. 4).
--
-- Proposito de esta migracion:
--   1. Verificar de punta a punta que Flyway aplica sobre una base vacia.
--   2. Fijar las convenciones de esquema que heredan todas las migraciones siguientes.
--
-- CONVENCIONES OBLIGATORIAS PARA TODA MIGRACION POSTERIOR
--   - Charset utf8mb4 / collation utf8mb4_0900_ai_ci. Nunca utf8 (que en MySQL son 3 bytes
--     y rompe emojis y varios caracteres).
--   - Toda tabla de negocio lleva organization_id NOT NULL. Lleva consultorio_id cuando el
--     hecho pertenece a una sede.
--   - Todo indice y todo UNIQUE incorpora el alcance tenant. Un UNIQUE sin organization_id
--     es un bug de aislamiento entre organizaciones, no una optimizacion pendiente.
--   - Importes en DECIMAL con precision explicita. Nunca FLOAT ni DOUBLE.
--   - Instantes en DATETIME(6) y siempre en UTC. Las reglas locales aplican una zona IANA
--     explicita en la capa de dominio, no en la base.
--   - Baja logica: columnas active y deleted_at. Prohibido el DELETE fisico sobre
--     informacion historica relevante.
--   - Patron expandir-migrar-contraer para cambios de esquema. Sin drops destructivos en
--     etapas funcionales.
--   - Una migracion aplicada NO se edita jamas. Se corrige con una nueva.
-- =====================================================================================

CREATE TABLE platform_schema_info (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    baseline_stage      VARCHAR(32)  NOT NULL COMMENT 'Etapa del plan que establecio el baseline',
    application_version VARCHAR(32)  NOT NULL COMMENT 'Version de la aplicacion que lo aplico',
    contract_version    VARCHAR(32)  NOT NULL COMMENT 'Version SemVer del contrato OpenAPI',
    applied_at          DATETIME(6)  NOT NULL COMMENT 'Instante UTC de aplicacion',
    CONSTRAINT pk_platform_schema_info PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Procedencia del esquema. Propiedad del modulo platform. Sin datos de negocio.';

INSERT INTO platform_schema_info (baseline_stage, application_version, contract_version, applied_at)
VALUES ('AKINE-00.01', '0.0.1-SNAPSHOT', '0.1.0', UTC_TIMESTAMP(6));
