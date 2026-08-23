package com.akine.platform.spi.audit;

/**
 * Puerto de auditoria de la plataforma. Cualquier modulo registra por aca los hechos
 * sensibles; ninguno escribe la tabla {@code audit_event} directamente.
 *
 * <p><b>Se invoca DENTRO de la transaccion de negocio.</b> No es un listener post-commit y no
 * debe convertirse en uno. Un listener que falla despues del commit deja la mutacion hecha y
 * sin rastro, y sobre datos clinicos y economicos eso es inaceptable: la trazabilidad tiene
 * que ser una consecuencia de la operacion, no un efecto secundario que puede perderse. Si el
 * registro falla, la operacion NO se confirma. El costo es un INSERT mas por operacion
 * sensible.
 *
 * <p>Esto lo distingue de los eventos de dominio (notificaciones, proyecciones, efectos
 * derivados), que si van {@code AFTER_COMMIT} o por outbox porque su fallo no debe revertir el
 * negocio. Son dos mecanismos con dos propositos: no se reemplazan.
 *
 * <p><b>Que jamas se registra:</b> contrasenas, tokens, claves, y ningun contenido clinico.
 * Ver {@link AuditEntry}.
 *
 * <p>La tabla detras es append-only: no existe operacion de modificacion ni de borrado en
 * este contrato, y tampoco en el repositorio que lo implementa.
 */
public interface AuditTrail {

	/**
	 * Registra el hecho. La escritura participa de la transaccion en curso.
	 *
	 * @param entry hecho a auditar, ya validado por su propio constructor
	 */
	void record(AuditEntry entry);
}
