-- =====================================================================================
-- AKINE-01.02 — Identidad: tabla cuenta.
--
-- Propietario: modulo identity. Ningun otro modulo escribe ni lee esta tabla: se llega
-- por identity.spi o no se llega.
--
-- Trazabilidad: RF-M02-001 (identidad unica), RF-M02-002 (validar credenciales y estado),
-- RF-M02-005 (bloquear / desactivar sin perder trazabilidad), RN-M02-003 (nada de
-- credenciales en claro), RN-M02-004 (bloquear no borra historicos).
--
-- POR QUE NO LLEVA organization_id (excepcion deliberada a ADR-0004).
-- ADR-0004 exige organization_id NOT NULL en toda tabla de negocio. ADR-0009 define la
-- Cuenta como identidad unica cross-tenant: una persona = una cuenta, y esa misma cuenta
-- trabaja en N organizaciones. Ponerle organization_id reintroduciria exactamente el
-- modelo que ADR-0009 descarta —una cuenta por tenant— y romperia la deduplicacion de
-- personas. El aislamiento de esta tabla no es por tenant sino POR CUENTA: todo acceso se
-- hace por cuenta_id y la API no expone ningun listado de cuentas cross-tenant.
--
-- uk_cuenta_email_normalizado es UNIQUE GLOBAL, y eso NO es un descuido: es la
-- materializacion en la base de RF-M02-001. Si el unique tuviera alcance de tenant, la
-- misma persona podria tener una cuenta por organizacion y la identidad unica dejaria de
-- existir como invariante. Lo mismo vale para token_verificacion y refresh_token (V7):
-- cuelgan de la cuenta, que es global.
--
-- La contrasena se guarda como hash Argon2id en formato PHC ($argon2id$v=19$m=..,t=..,p=..
-- $salt$hash). Los parametros viajan dentro del propio hash, asi que subirlos manana no
-- exige migracion: los hashes viejos siguen verificando con sus propios parametros.
-- =====================================================================================

CREATE TABLE cuenta (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    email             VARCHAR(320) NOT NULL COMMENT 'Tal como lo tipeo la persona. Se muestra; no se busca por aca',
    email_normalizado VARCHAR(320) NOT NULL COMMENT 'lower-case + trim. Es la clave de identidad (RF-M02-001)',
    nombre            VARCHAR(120) NOT NULL,
    apellido          VARCHAR(120) NOT NULL,
    password_hash     VARCHAR(255) NULL COMMENT 'Argon2id en formato PHC. NULL mientras la cuenta invitada no fijo credencial. JAMAS la contrasena en claro (RN-M02-003)',
    estado            VARCHAR(30)  NOT NULL DEFAULT 'PENDIENTE_ACTIVACION' COMMENT 'PENDIENTE_ACTIVACION | ACTIVA | BLOQUEADA | DESACTIVADA. VARCHAR y no ENUM: agregar un estado no puede exigir un ALTER (ADR-0007)',
    intentos_fallidos INT          NOT NULL DEFAULT 0 COMMENT 'Observabilidad y alerta, no lockout: bloquear por intentos habilita un DoS dirigido. La proteccion es el rate limit',
    ultimo_login_en   DATETIME(6)  NULL COMMENT 'Instante UTC',
    bloqueada_en      DATETIME(6)  NULL,
    bloqueada_motivo  VARCHAR(300) NULL COMMENT 'Motivo declarado por el administrador (RF-M02-005)',
    active            TINYINT(1)   NOT NULL DEFAULT 1,
    deleted_at        DATETIME(6)  NULL COMMENT 'Se setea SOLO al desactivar. La fila nunca se borra: los historicos que la referencian tienen que seguir siendo legibles (RN-M02-004)',
    version           BIGINT       NOT NULL DEFAULT 0 COMMENT 'Optimistic locking: dos administradores operando la misma cuenta no pueden pisarse',
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    CONSTRAINT pk_cuenta PRIMARY KEY (id),
    CONSTRAINT uk_cuenta_email_normalizado UNIQUE (email_normalizado),
    -- El login busca por email normalizado (ya cubierto por el unique). Este indice sirve
    -- a la administracion de cuentas por estado, que es la otra consulta real.
    INDEX ix_cuenta_estado (estado, active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Identidad unica cross-tenant (ADR-0009). Sin organization_id a proposito: ver el comentario de cabecera de V6. El rol NO vive aca: es contextual y vive en membership.';
