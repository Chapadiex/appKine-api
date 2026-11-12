-- =====================================================================================
-- AKINE-01.03 — Bootstrap del primer PLATFORM_ADMIN.
--
-- Trazabilidad: D-2 cerrada por el usuario el 23/08/2026 (opcion A), sub-decision D-14
-- cerrada el mismo dia, ADR-0020, ADR-0019, matriz de permisos §1.3 y §7.
--
-- Migracion SEPARADA de la que crea la tabla, con el mismo criterio que V4 uso para el
-- catalogo de planes: el DDL y el dato no se mezclan. Un rollback del dato no puede obligar a
-- tirar el esquema.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE EL SEED CREA TAMBIEN LA CUENTA (D-14, decidida)
--
-- El diseno original resolvia el email contra `cuenta` con un INSERT ... SELECT idempotente.
-- Sobre una base nueva eso inserta CERO filas y NO falla: al aplicarse las migraciones todavia
-- no existe ninguna cuenta. Es decir, el despliegue inicial terminaba sin ningun
-- PLATFORM_ADMIN, en silencio, y con los tres endpoints de plataforma publicados en el
-- contrato e inalcanzables — exactamente el defecto que esta etapa vino a cerrar.
--
-- Por eso el seed crea la fila de `cuenta` Y la de `platform_role` juntas. El alta pasa a ser
-- de un solo paso y reproducible desde cero.
--
-- ---------------------------------------------------------------------------------------
-- LAS TRES COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. NINGUN SECRETO VERSIONADO. password_hash queda NULL. La columna lo admite desde V6
--    ("NULL mientras la cuenta invitada no fijo credencial") y Argon2PasswordHasher.matches
--    devuelve false ante un hash nulo o ilegible, sin excepcion: la cuenta existe y NO se
--    puede autenticar con ninguna contrasena. La unica via para tomar posesion es el flujo de
--    recuperacion de contrasena de 01.02, que exige acceso a la casilla.
--    Poner un hash de una contrasena conocida aca seria versionar una credencial de
--    administracion de plataforma en un repositorio git, contra AGENT.md §10.
--
-- 2. ESTA MIGRACION ESCRIBE EN UNA TABLA DE OTRO MODULO. `cuenta` es de `identity`;
--    `organization` no puede compilar contra `identity` y por lo tanto este bootstrap no tiene
--    llamador Java posible. ADR-0020 autoriza por escrito el cruce de la frontera de propiedad
--    en SQL —lo autorizaba para LEER; la decision D-14 del usuario lo extiende a ESCRIBIR, y
--    queda registrado aca porque ArchUnit no ve los .sql: esta regla la sostiene la revision
--    humana de cada migracion.
--
-- 3. EL EMAIL QUEDA VERSIONADO EN EL REPOSITORIO. Es la contrapartida que el usuario acepto
--    explicitamente a cambio de que el alta sea auditable y reproducible. Tiene que ser una
--    casilla INSTITUCIONAL y estable, no la direccion personal de quien este a cargo hoy.
--
-- ---------------------------------------------------------------------------------------
-- IDEMPOTENCIA
--
-- Los dos INSERT son INSERT ... SELECT ... WHERE NOT EXISTS. Re-aplicar la migracion sobre una
-- base que ya tiene la cuenta (por ejemplo porque alguien se registro con ese email antes) no
-- duplica nada y no falla: la segunda sentencia engancha la cuenta que ya existia. Flyway no
-- reaplica una migracion versionada, pero la idempotencia importa igual para el caso de una
-- base restaurada a medias.
--
-- El arranque no queda librado a la fe: PlatformAdminBootstrapCheck cuenta las filas activas de
-- platform_role y loguea a nivel WARN si son cero. No falla el arranque —un entorno de
-- desarrollo sin platform admin es legitimo— pero deja de ser silencioso.
-- =====================================================================================

INSERT INTO cuenta (email, email_normalizado, nombre, apellido, password_hash, estado,
                    intentos_fallidos, active, version, created_at, updated_at)
SELECT 'plataforma@akine.app', 'plataforma@akine.app', 'Administracion', 'de Plataforma',
       NULL, 'ACTIVA', 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM cuenta c WHERE c.email_normalizado = 'plataforma@akine.app');

INSERT INTO platform_role (account_id, role_code, granted_by_account_id, reason,
                           valid_from, active, version, created_at, updated_at)
SELECT c.id, 'PLATFORM_ADMIN', NULL,
       'Bootstrap de plataforma — AKINE-01.03, ADR-0020, decision D-2/D-14 del 23/08/2026',
       UTC_TIMESTAMP(6), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
  FROM cuenta c
 WHERE c.email_normalizado = 'plataforma@akine.app'
   AND NOT EXISTS (SELECT 1 FROM platform_role p
                    WHERE p.account_id = c.id AND p.active = 1);
