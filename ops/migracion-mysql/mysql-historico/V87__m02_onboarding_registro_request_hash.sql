-- =====================================================================================
-- V87 - request_hash en onboarding_registro (escenario diferido 7b, docs/tests-diferidos.md)
--
-- Hasta aca el alta self-service reconocia el reintento SOLO por la Idempotency-Key: la
-- misma clave con otro email, otro nombre u otro slug respondia 202 —el acuse del pedido
-- original— y la persona quedaba esperando un correo para una cuenta que nunca se dio de
-- alta. La comparacion de hash que si existe en organization nunca se ejecutaba, porque
-- identity corta primero en el replay.
--
-- La columna guarda el SHA-256 (hex, 64) del payload canonico del alta. NO incluye la
-- contrasena: un SHA-256 sin sal de un string cuyos demas campos se conocen es un hash de
-- contrasena crackeable offline, y esta tabla no es donde se guardan credenciales.
--
-- NULL = no comparable. Las filas anteriores a esta migracion quedan en NULL y su reintento
-- se acepta como replay, igual que hasta hoy. Aditiva: nada existente cambia de forma.
-- =====================================================================================

ALTER TABLE onboarding_registro
    ADD COLUMN request_hash VARCHAR(64) NULL
        COMMENT 'SHA-256 hex del payload canonico del alta, sin la contrasena. NULL = no comparable (filas previas a V87)'
        AFTER estado;
