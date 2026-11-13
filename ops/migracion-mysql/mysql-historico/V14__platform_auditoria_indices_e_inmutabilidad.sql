-- =====================================================================================
-- AKINE-01.03 — Auditoria: los indices que las consultas de M24 necesitan, y la
-- inmutabilidad aplicada por la BASE.
--
-- Trazabilidad: RF-M24-002, RF-M24-003, RF-M24-004, RN-M24-001, RNF-M24-004. V5 dejo estas
-- dos cosas anotadas explicitamente como trabajo de 01.03.
--
-- Propietario de la tabla: modulo platform.
--
-- ---------------------------------------------------------------------------------------
-- LOS INDICES: por que los dos de V5 no alcanzan
--
-- V5 dejo:
--     ix_audit_event_entity (organization_id, entity_type, entity_id, occurred_at)
--     ix_audit_event_actor  (organization_id, actor_account_id, occurred_at)
--
-- Los dos sirven a RF-M24-002 y RF-M24-003. Ninguno sirve a RF-M24-004, que es un RANGO sobre
-- occurred_at filtrando SOLO por organizacion: con entity_type o actor_account_id de por medio
-- el prefijo del indice no aplica y MySQL termina escaneando el tenant entero. Hoy esa consulta
-- ya esta declarada en AuditEventRepository y es un scan.
--
-- El segundo indice existe porque el alcance de auditoria:read no es uniforme: un ORG_ADMIN ve
-- la organizacion entera y un CONSULTORIO_ADMIN ve SU consultorio, y esa segunda consulta
-- filtra ademas por consultorio_id.
--
-- ---------------------------------------------------------------------------------------
-- LA INMUTABILIDAD: por que un trigger y no la forma del repositorio
--
-- Hasta hoy "append-only" dependia de que AuditEventRepository extendiera Repository y no
-- JpaRepository, es decir de que los metodos delete no existieran. Eso protege del descuido y
-- no protege de nada mas: un UPDATE por SQL directo, un script de mantenimiento o un ORM mal
-- configurado pasan por encima.
--
-- RN-M24-001 exige que la auditoria no sea editable por usuarios operativos. Con los triggers,
-- la garantia deja de ser una propiedad del codigo Java y pasa a ser una propiedad de la tabla.
-- SIGNAL SQLSTATE '45000' es el mecanismo estandar de MySQL para abortar con un error de
-- usuario; el mensaje viaja en el SQLException y por eso dice exactamente que paso.
--
-- La unica forma de probar un trigger es dispararlo: hay un test de integracion que intenta un
-- UPDATE y un DELETE nativos y espera el fallo.
--
-- Nota operativa: estos triggers tambien bloquean el borrado de datos de prueba. Es deliberado
-- y es el punto — los tests de integracion corren contra una base efimera que se crea y se
-- destruye entera, no contra una a la que haya que limpiarle filas.
-- =====================================================================================

ALTER TABLE audit_event
    ADD INDEX ix_audit_event_org_time (organization_id, occurred_at);

ALTER TABLE audit_event
    ADD INDEX ix_audit_event_org_loc_time (organization_id, consultorio_id, occurred_at);

CREATE TRIGGER trg_audit_event_no_update
    BEFORE UPDATE ON audit_event
    FOR EACH ROW
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'audit_event es append-only: RN-M24-001 prohibe modificar la auditoria';

CREATE TRIGGER trg_audit_event_no_delete
    BEFORE DELETE ON audit_event
    FOR EACH ROW
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'audit_event es append-only: RN-M24-001 prohibe borrar la auditoria';
