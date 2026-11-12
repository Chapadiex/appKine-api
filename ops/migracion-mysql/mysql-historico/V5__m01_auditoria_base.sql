-- =====================================================================================
-- AKINE-01.01 — Auditoria base: tabla audit_event.
--
-- Por que nace aca y no en 01.03 (decision transversal T-2): RNF-M01-006 exige auditar las
-- operaciones sensibles de M01 —alta de organizacion, transicion de suscripcion, seleccion
-- de contexto— y esas ocurren en 01.01. Dejarlas con un log estructurado y nada mas es
-- dejarlas sin auditoria consultable.
--
-- Por que se escribe DENTRO de la transaccion de negocio (T-2, gana el criterio de 01.03):
-- un listener post-commit que falla deja la mutacion hecha SIN rastro. Sobre datos clinicos
-- y economicos eso es inaceptable. El costo es un INSERT mas por operacion sensible.
-- Los eventos de dominio (notificaciones, efectos derivados) siguen siendo AFTER_COMMIT:
-- son otro mecanismo, con otro proposito.
--
-- Propietario: modulo platform (puerto platform.spi.audit.AuditTrail). Cualquier modulo lo
-- invoca por ese puerto; ninguno escribe esta tabla directamente.
--
-- 01.03 agrega sobre esta base: endpoints de consulta paginada, permisos auditoria:read /
-- auditoria:read-clinica, enforcement de inmutabilidad por trigger SIGNAL con su test, y el
-- modelo de acceso de soporte.
-- =====================================================================================

CREATE TABLE audit_event (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id   BIGINT       NULL COMMENT 'NULL SOLO para eventos de plataforma sin tenant (p.ej. alta de un plan). Todo evento de negocio lo lleva',
    consultorio_id    BIGINT       NULL COMMENT 'NULL cuando el hecho no pertenece a una sede concreta',
    actor_account_id  BIGINT       NULL COMMENT 'NULL = sistema. Referencia logica a identity.account, sin FK fisica (ADR-0001)',
    event_type        VARCHAR(64)  NOT NULL COMMENT 'Que paso: ORGANIZATION_CREATED, SUBSCRIPTION_TRANSITIONED, CONTEXT_SELECTED, ...',
    entity_type       VARCHAR(64)  NOT NULL COMMENT 'Sobre que entidad: Organization, Subscription, Membership, ...',
    entity_id         BIGINT       NULL COMMENT 'NULL cuando el evento no apunta a una fila concreta',
    previous_state    VARCHAR(64)  NULL,
    new_state         VARCHAR(64)  NULL,
    details           JSON         NULL COMMENT 'Contexto adicional del evento. JAMAS secretos, tokens, contrasenas ni contenido clinico',
    reason            VARCHAR(500) NULL COMMENT 'Motivo declarado por el actor cuando la operacion lo exige',
    correlation_id    VARCHAR(64)  NULL COMMENT 'Correlaciona el evento con el log estructurado del request (ADR-0005)',
    occurred_at       DATETIME(6)  NOT NULL COMMENT 'Instante UTC del hecho',
    CONSTRAINT pk_audit_event PRIMARY KEY (id),
    -- "Que le paso a esta entidad": la consulta natural de una auditoria.
    INDEX ix_audit_event_entity (organization_id, entity_type, entity_id, occurred_at),
    -- "Que hizo este actor": la consulta natural de una investigacion.
    INDEX ix_audit_event_actor (organization_id, actor_account_id, occurred_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Auditoria append-only. Jamas UPDATE ni DELETE: por eso no tiene updated_at, ni baja logica, ni version. Jamas secretos ni contenido clinico. Sin FK a organization para que la auditoria sobreviva a cualquier operacion sobre el tenant.';
