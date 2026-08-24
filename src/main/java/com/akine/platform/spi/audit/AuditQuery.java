package com.akine.platform.spi.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Lectura de la auditoria (RF-M24-002, RF-M24-003, RF-M24-004).
 *
 * <p>Es la contraparte de {@link AuditTrail}: {@code platform} sigue siendo el unico propietario
 * de {@code audit_event}, y los demas modulos la leen por aca y solo por aca.
 *
 * <p><b>Este puerto no autoriza.</b> Filtra por el {@code organizationId} que recibe y nada
 * mas. Quien decide si el actor puede leer esa auditoria —y con que alcance— es
 * {@code organization}, que es dueño de la matriz de permisos; {@code platform} no puede
 * evaluarlo sin depender de un modulo funcional, que es lo que el puerto invertido evita. Por
 * lo tanto: <b>ningun llamador puede pasar un {@code organizationId} que venga del cliente</b>.
 *
 * <p>Siempre paginado (RNF-M24-004) y siempre por {@code occurred_at} descendente: la pregunta
 * de una auditoria es casi siempre "que paso ultimo".
 */
public interface AuditQuery {

	/**
	 * Historial de una entidad concreta (RF-M24-002). Usa {@code ix_audit_event_entity}.
	 */
	Page<AuditEventSummary> porEntidad(AuditEventFilter filtro, Pageable pageable);

	/**
	 * Actividad de un actor dentro de un tenant (RF-M24-003). Usa {@code ix_audit_event_actor}.
	 */
	Page<AuditEventSummary> porActor(AuditEventFilter filtro, Pageable pageable);

	/**
	 * Ventana temporal (RF-M24-004). Usa los indices que agrego la migracion V14.
	 *
	 * <p>Con {@code consultorioId} no nulo la consulta se acota a esa sede.
	 */
	Page<AuditEventSummary> porPeriodo(AuditEventFilter filtro, Pageable pageable);
}
