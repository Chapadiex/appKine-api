package com.akine.resource.domain.exception;

/**
 * La sede sobre la que se quiso operar no existe o no es del tenant del request (404).
 *
 * <p><b>Por que este modulo tiene su propia excepcion en vez de dejar pasar la de
 * {@code organization}.</b> Aquella es de {@code organization.domain} y ArchUnit prohibe
 * importarla. Podria dejarse propagar sin nombrarla —es el truco que documenta
 * {@code PermissionGuard}— pero aca no hay nada que propagar: {@code ConsultorioDirectory}
 * devuelve un {@code Optional} vacio, no lanza. Alguien tiene que traducir ese vacio, y ese
 * alguien es este modulo.
 *
 * <p>El resultado HTTP es identico —404 {@code not-found}, sin cuerpo distinguible—, que es lo
 * unico que el cliente puede observar.
 */
public class ConsultorioNotAccessibleException extends RuntimeException {

	private final long consultorioId;

	public ConsultorioNotAccessibleException(long consultorioId) {
		super("Sede no accesible: " + consultorioId);
		this.consultorioId = consultorioId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}
}
