package com.akine.identity.domain.exception;

/**
 * El contexto de trabajo pedido no esta disponible para esta cuenta.
 *
 * <p>Se traduce a <b>404</b>, jamas a 403. Es la regla heredada de AKINE-01.01 y de ADR-0019:
 * un 403 confirmaria que ese consultorio existe pero es de otro, y bastaria recorrer ids
 * consecutivos para mapear los centros de la competencia. Para quien no tiene acceso, el
 * recurso no existe.
 *
 * <p>Cubre indistintamente: consultorio inexistente, consultorio de otra organizacion,
 * membership inexistente, vencida o dada de baja, y suscripcion CANCELADA o BAJA. Las cinco
 * responden lo mismo por el mismo motivo.
 */
public class ContextNotAvailableException extends RuntimeException {

	private final long organizationId;
	private final long consultorioId;

	public ContextNotAvailableException(long organizationId, long consultorioId) {
		super("Contexto no disponible");
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
	}

	public long getOrganizationId() {
		return organizationId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}
}
