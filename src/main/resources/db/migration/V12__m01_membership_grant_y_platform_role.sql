-- =====================================================================================
-- AKINE-01.03 — Permisos adicionales por membership (membership_grant) y rol de plataforma
-- (platform_role).
--
-- Trazabilidad: RF-M02-004, matriz de permisos §3 ("No por defecto", "Segun permiso"),
-- §6 (asignacion base por rol) y §7 (invariantes), ADR-0004 (persistencia multi-tenant),
-- ADR-0019 (lista de excepciones a ADR-0004), ADR-0020 (esta migracion es la que lo aplica).
--
-- Propietario de las dos tablas: modulo organization. Ningun otro modulo las lee.
-- platform las ve solo por puertos invertidos (MembershipDirectory, PlatformRoleDirectory).
--
-- ---------------------------------------------------------------------------------------
-- LOS NULL EN LOS UNIQUE DE MySQL — la trampa que V10 ya nos costo una vez
--
-- En MySQL, como en el estandar SQL, varios NULL NO colisionan en un indice unico. Un
--     UNIQUE (organization_id, membership_id, permission_code) filtrando por active en la app
-- no sirve, y un unique parcial (WHERE active = 1) no existe en MySQL.
--
-- Las dos tablas de abajo necesitan exactamente eso: "a lo sumo UNA fila VIGENTE por clave,
-- conservando el historial de las dadas de baja". Se resuelve igual que V10 resolvio el
-- alcance de membership: con una columna GENERADA que vale el discriminador cuando la fila
-- esta vigente y NULL cuando no lo esta. Los NULL no colisionan — y eso, que en V10 era el
-- problema, aca es exactamente el mecanismo: las filas historicas salen del unique solas.
--
--   grant_activo = IF(active = 1, permission_code, NULL)
--   rol_activo   = IF(active = 1, role_code,       NULL)
--
-- Un unique sobre permission_code a secas impediria RE-otorgar un permiso que alguna vez se
-- quito, que es una operacion legitima y frecuente.
--
-- STORED y no VIRTUAL, mismo criterio que V10: InnoDB indexa las dos, pero STORED se lee y se
-- explica en un plan sin recalcular la expresion.
-- =====================================================================================

CREATE TABLE membership_grant (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id       BIGINT       NOT NULL COMMENT 'ADR-0004: toda tabla de negocio lleva el tenant, y todo unique lo incluye',
    membership_id         BIGINT       NOT NULL,
    permission_code       VARCHAR(48)  NOT NULL COMMENT 'Codigo del catalogo de la matriz §5. En F1 el unico otorgable es auditoria:read-clinica; el codigo valida contra el catalogo y rechaza el resto con 400, nunca con una fila invalida',
    granted_by_account_id BIGINT       NOT NULL COMMENT 'Referencia logica a identity.cuenta, sin FK fisica (ADR-0001)',
    reason                VARCHAR(500) NOT NULL COMMENT 'NOT NULL a proposito: un permiso adicional sin motivo declarado no se puede revisar seis meses despues, que es cuando se revisa',
    valid_from            DATETIME(6)  NOT NULL COMMENT 'Instante UTC de inicio de vigencia',
    valid_until           DATETIME(6)  NULL COMMENT 'NULL = sin fin',
    revoked_by_account_id BIGINT       NULL,
    revoked_reason        VARCHAR(500) NULL,
    active                TINYINT(1)   NOT NULL DEFAULT 1,
    deleted_at            DATETIME(6)  NULL,
    version               BIGINT       NOT NULL DEFAULT 0 COMMENT 'Optimistic locking: grant y revoke simultaneos del mismo permiso no se pisan, el perdedor recibe 409',
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    grant_activo          VARCHAR(48) AS (IF(active = 1, permission_code, NULL)) STORED
        COMMENT 'Discriminador del unique: el permiso mientras el grant esta vigente, NULL cuando se dio de baja. Varios NULL no colisionan, asi que las filas historicas salen del unique y se puede re-otorgar lo que alguna vez se quito',
    CONSTRAINT pk_membership_grant PRIMARY KEY (id),
    CONSTRAINT uk_membership_grant_activo UNIQUE (organization_id, membership_id, grant_activo),
    CONSTRAINT fk_membership_grant_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_membership_grant_membership FOREIGN KEY (membership_id) REFERENCES membership (id),
    INDEX ix_membership_grant_membership (membership_id, active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Permisos adicionales otorgados a una membership concreta ("No por defecto" y "Segun permiso" de la matriz §3). La asignacion BASE por rol NO vive aca: vive en codigo (organization.domain.RolePermissions), porque es la especificacion y cambiarla tiene que ser un diff revisable, no un UPDATE.';

-- ---------------------------------------------------------------------------------------
-- platform_role — la unica tabla de esta etapa SIN organization_id.
--
-- La excepcion a ADR-0004 esta formalizada en ADR-0020, escrito para esta etapa porque
-- ADR-0019 cierra su lista de excepciones diciendo que "una excepcion nueva exige un ADR que
-- la agregue a esta tabla; un comentario en la migracion no alcanza". Sin ese ADR esta
-- migracion no se commitea.
--
-- El motivo, en una linea: la matriz §1.3 define PLATFORM_ADMIN como el rol que NO tiene
-- membership en ninguna organizacion. Preguntarle a que tenant pertenece la fila no tiene
-- respuesta, y acotarlo a uno seria el rol contrario.
--
-- Que la aisla, ya que no es el tenant (ADR-0020): es una tabla de SUJETO cuya clave es
-- account_id, que ya es global (ADR-0019); el unico acceso es
-- PlatformRoleDirectory.isPlatformAdmin(accountId, at), resuelto desde el sub del token; y
-- tener el rol NO da acceso a datos de ningun tenant — para eso hace falta ademas un
-- support_access vigente, que si lleva organization_id.
--
-- role_code admite un unico valor: PLATFORM_ADMIN. No es una tabla de roles generica.
-- ---------------------------------------------------------------------------------------

CREATE TABLE platform_role (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    account_id            BIGINT       NOT NULL COMMENT 'Referencia logica a identity.cuenta, sin FK fisica (ADR-0001). Es global: ADR-0019',
    role_code             VARCHAR(48)  NOT NULL COMMENT 'Unico valor admitido: PLATFORM_ADMIN. No es una tabla de roles generica',
    granted_by_account_id BIGINT       NULL COMMENT 'NULL = seed de bootstrap (V15): no hubo actor humano',
    reason                VARCHAR(500) NOT NULL COMMENT 'Obligatorio: el permiso mas alto del sistema no se otorga sin motivo escrito',
    valid_from            DATETIME(6)  NOT NULL,
    valid_until           DATETIME(6)  NULL COMMENT 'NULL = sin fin',
    revoked_by_account_id BIGINT       NULL,
    revoked_reason        VARCHAR(500) NULL,
    active                TINYINT(1)   NOT NULL DEFAULT 1,
    deleted_at            DATETIME(6)  NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    rol_activo            VARCHAR(48) AS (IF(active = 1, role_code, NULL)) STORED
        COMMENT 'Mismo mecanismo que grant_activo: el rol mientras esta vigente, NULL cuando se revoco. Permite revocar y volver a otorgar conservando el historial',
    CONSTRAINT pk_platform_role PRIMARY KEY (id),
    CONSTRAINT uk_platform_role_activo UNIQUE (account_id, rol_activo),
    CONSTRAINT ck_platform_role_code CHECK (role_code = 'PLATFORM_ADMIN'),
    INDEX ix_platform_role_account (account_id, active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Rol de plataforma (PLATFORM_ADMIN). SIN organization_id: excepcion a ADR-0004 formalizada en ADR-0020, porque es un objeto de la plataforma y no de un tenant (matriz §1.3). Propietario: modulo organization; platform la consume por el puerto invertido PlatformRoleDirectory.';
