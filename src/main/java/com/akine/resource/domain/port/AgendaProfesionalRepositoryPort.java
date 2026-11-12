package com.akine.resource.domain.port;

/**
 * Fila-lock de la agenda de una persona (V129, {@code agenda_profesional}).
 *
 * <p>Mismo protocolo que {@code consultorio_calendario}: la fila se crea en su propia transaccion
 * con {@code INSERT ... ON CONFLICT DO NOTHING} y se bloquea despues, en la de negocio.
 */
public interface AgendaProfesionalRepositoryPort {

	/** Lectura SIN lock: si la fila ya existe. */
	boolean existeFila(long organizationId, long accountId);

	/** {@code INSERT ... ON CONFLICT DO NOTHING}: idempotente, en su propia transaccion. */
	void crearSiFalta(long organizationId, long accountId);

	/** {@code SELECT ... FOR UPDATE}. {@code false} si la fila no existe. */
	boolean bloquear(long organizationId, long accountId);
}
