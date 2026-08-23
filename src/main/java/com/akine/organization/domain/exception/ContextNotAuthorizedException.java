package com.akine.organization.domain.exception;

/**
 * La cuenta no tiene un contexto autorizado sobre la organizacion y el consultorio pedidos.
 *
 * <p>Se lanza cuando la revalidacion contra la base falla: membership inexistente, dada de
 * baja o fuera de vigencia; consultorio inactivo o de otra organizacion; organizacion dada de
 * baja; suscripcion cancelada. El backend nunca confia en el contexto que viene en el token
 * (RN-M01-003), asi que esta validacion corre en CADA request y no una sola vez al emitirlo:
 * una membership revocada deja de servir en el request siguiente, sin ventana de gracia.
 *
 * <p>Se traduce a 404 y no a 403: responder "prohibido" confirmaria que esa organizacion o
 * ese consultorio existen.
 */
public class ContextNotAuthorizedException extends RuntimeException {

	private final Long accountId;
	private final Long organizationId;
	private final Long consultorioId;

	public ContextNotAuthorizedException(Long accountId, Long organizationId, Long consultorioId) {
		super("Contexto no autorizado para la cuenta");
		this.accountId = accountId;
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
	}

	public Long getAccountId() {
		return accountId;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}
}
