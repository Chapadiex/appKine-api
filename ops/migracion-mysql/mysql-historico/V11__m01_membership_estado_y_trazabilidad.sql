-- =====================================================================================
-- AKINE-01.03 — Expandir membership: estado explicito y trazabilidad de la revocacion.
--
-- Trazabilidad: RF-M01-002, RF-M02-004, RN-M05-003, §33 (toda entidad con estados declara
-- los suyos), matriz de permisos §7, ADR-0004, ADR-0007 (expandir-migrar-contraer),
-- ADR-0020 (PLATFORM_ADMIN no es un valor legal de membership.role_code).
--
-- Propietario de la tabla: modulo organization.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE UN ESTADO, SI YA HAY active Y VIGENCIA
--
-- Son TRES cosas distintas y las tres tienen que cumplirse para que la membership habilite
-- algo:
--   * active      — baja logica de la fila (regla maestra 10: nada se borra);
--   * vigencia    — valid_from / valid_until, la ventana temporal del vinculo;
--   * estado      — donde esta la membership en su maquina de estados.
--
-- Sin estado, "suspendida" y "revocada" son indistinguibles: las dos serian active = 0 con
-- valid_until cerrado, y no habria forma de saber si el vinculo puede reactivarse o si murio
-- para siempre. §33 exige que toda entidad con estados los declare; esta columna es eso.
--
-- Valores: ACTIVA | SUSPENDIDA | REVOCADA (terminal).
--
-- INVITADA y RECHAZADA NO se declaran. Eran los estados del flujo de invitacion por mail, que
-- la decision D-1 dejo fuera de 01.03. Declarar valores que ninguna transicion produce seria
-- documentar una maquina de estados que no existe. Cuando la invitacion llegue, agregarlos es
-- una migracion de catalogo, no un rediseno.
--
-- Backfill: 'ACTIVA' por DEFAULT. Es correcto por construccion — la unica membership que el
-- sistema escribe hoy es la del fundador (ORG_ADMIN, is_founder = 1) y nace vigente.
--
-- invited_by_account_id tampoco entra, por el mismo motivo que INVITADA: sin invitacion no hay
-- quien invito. revoked_by_account_id y revoked_reason SI entran, porque la revocacion si
-- entra y su motivo es obligatorio (matriz §7).
--
-- ---------------------------------------------------------------------------------------
-- EL CHECK DE role_code
--
-- V3 dejo escrito en el comentario de la columna que PLATFORM_ADMIN era un valor valido de
-- membership.role_code. La matriz §1.3 dice lo contrario y es vinculante: "PLATFORM_ADMIN no
-- tiene membership en ninguna organizacion". ADR-0020 cierra la contradiccion creando
-- platform_role y prohibiendo ese valor aca. El CHECK es la mitad de la garantia; la otra
-- mitad es un test.
--
-- El enum RoleCode conserva el valor porque es el catalogo de roles del SISTEMA, no el de
-- valores legales de esta columna.
--
-- ---------------------------------------------------------------------------------------
-- EL UNIQUE NO SE TOCA — y hay que decir por que, porque no es obvio
--
-- uk_membership_org_account_scope (V10) incluye a TODAS las filas, activas e historicas. Una
-- membership revocada sigue ocupando la clave (organization_id, account_id, consultorio_scope),
-- asi que revocar a una persona y volver a vincularla EN LA MISMA SEDE choca contra el unique.
--
-- No se corrige aca a proposito. Es la decision D-13 del diseno y sigue abierta: expandir el
-- discriminador para que las filas no vigentes salgan del unique (barato hoy, irreversible en
-- la practica en cuanto coexistan una vigente y una revocada del mismo alcance) o reusar la
-- fila al revincular (conserva el unique, pierde el historial, contra RN-M05-003).
--
-- Lo que si hay que saber para no perder tiempo buscandolo: el alta directa de membership que
-- 01.03 introduce SI puede chocar contra ese unique, y por eso el servicio traduce la clave
-- duplicada a un 409 explicito en vez de dejar salir un 500.
--
-- ---------------------------------------------------------------------------------------
-- EL INDICE
--
-- ix_membership_org_consultorio_estado sirve al listado de colaboradores del alcance
-- (colaborador:read: el ORG_ADMIN ve toda la organizacion, el CONSULTORIO_ADMIN solo su sede)
-- y al conteo del invariante de ultimo admin, que filtra por (organization_id, estado, active).
-- =====================================================================================

ALTER TABLE membership
    ADD COLUMN estado VARCHAR(20) NOT NULL DEFAULT 'ACTIVA'
        COMMENT 'Maquina de estados del vinculo (§33): ACTIVA | SUSPENDIDA | REVOCADA (terminal). Distinto de active (baja logica) y de la vigencia: las tres condiciones tienen que cumplirse. INVITADA/RECHAZADA no se declaran: son del flujo de invitacion, fuera por D-1';

ALTER TABLE membership
    ADD COLUMN revoked_by_account_id BIGINT NULL
        COMMENT 'Quien revoco. Referencia logica a identity.cuenta, sin FK fisica (ADR-0001). NULL mientras la membership no fue revocada';

ALTER TABLE membership
    ADD COLUMN revoked_reason VARCHAR(500) NULL
        COMMENT 'Motivo declarado de la revocacion. Obligatorio al revocar: sin motivo la auditoria no responde "por que me desvincularon" seis meses despues, que es cuando se pregunta';

ALTER TABLE membership
    ADD INDEX ix_membership_org_consultorio_estado (organization_id, consultorio_id, estado, active);

ALTER TABLE membership
    ADD CONSTRAINT ck_membership_role_code_no_plataforma
        CHECK (role_code <> 'PLATFORM_ADMIN');
