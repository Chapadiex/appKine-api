-- =====================================================================================
-- AKINE-02.01 — Migrar: toda sede ya existente hereda la zona horaria de su organizacion.
--
-- Paso 2 de 3 de la expansion de `consultorio` (ver la cabecera de V16).
--
-- ---------------------------------------------------------------------------------------
-- POR QUE EL BACKFILL ES EXACTO Y NO UNA APROXIMACION
--
-- Hasta esta migracion, el UNICO camino que crea consultorios es
-- OrganizationService.provisionTenant, y ese camino usa la zona de la organizacion (o
-- America/Argentina/Cordoba por defecto). No existia forma de expresar una zona distinta
-- por sede, asi que no puede existir ninguna sede cuya zona real difiera de la de su tenant.
-- Copiarla no adivina nada: reconstruye el unico valor que pudo haber tenido.
--
-- Idempotente y repetible: el WHERE excluye lo ya rellenado.
-- ---------------------------------------------------------------------------------------
UPDATE consultorio c
    JOIN organization o ON o.id = c.organization_id
SET c.timezone = o.timezone
WHERE c.timezone IS NULL;

-- ---------------------------------------------------------------------------------------
-- VERIFICACION, EN ESTA MISMA MIGRACION
--
-- MySQL 8 valida un CHECK contra las filas EXISTENTES al agregarlo: si el backfill de arriba
-- hubiera dejado una sola fila sin zona, este ALTER falla con el error 3819 y Flyway se
-- detiene con la migracion marcada como fallida. Es la unica forma portable de hacer fallar
-- una migracion desde SQL plano: SIGNAL exige un bloque compuesto, o sea un procedimiento
-- temporal y un cambio de delimitador.
--
-- El CHECK NO es la restriccion definitiva —esa es el NOT NULL de V18—: se agrega para
-- verificar y se retira ahi mismo. Dejarlo duplicaria la misma regla en dos formas distintas
-- y la proxima persona no sabria cual manda.
-- ---------------------------------------------------------------------------------------
ALTER TABLE consultorio
    ADD CONSTRAINT ck_consultorio_timezone_backfill CHECK (timezone IS NOT NULL);
