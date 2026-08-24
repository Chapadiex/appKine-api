package com.akine.organization.domain.exception;

/**
 * El acceso de soporte pedido no existe o ya no esta vigente.
 *
 * <p>404. A diferencia del resto de los 404 del modulo, este no protege la existencia de un
 * tenant ajeno —las rutas de plataforma son publicas en el contrato y solo las alcanza un
 * {@code PLATFORM_ADMIN}—: es un 404 comun, de recurso que no esta.
 */
public class SupportAccessNotFoundException extends RuntimeException {

	private final Long supportAccessId;

	public SupportAccessNotFoundException(Long supportAccessId) {
		super("El acceso de soporte solicitado no existe o ya no esta vigente");
		this.supportAccessId = supportAccessId;
	}

	public Long getSupportAccessId() {
		return supportAccessId;
	}
}
