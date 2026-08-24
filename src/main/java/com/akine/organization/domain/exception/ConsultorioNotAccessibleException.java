package com.akine.organization.domain.exception;

/**
 * La sede pedida no existe, o existe y pertenece a OTRO tenant.
 *
 * <p><b>Los dos casos responden 404 y son indistinguibles a proposito.</b> Un 403 confirmaria
 * que ese id existe, y bastaria recorrer numeros consecutivos para enumerar las sedes del
 * SaaS. Es la misma regla heredada de 01.01 y la mitad opuesta de
 * {@link PermissionDeniedException}: fuera del alcance, 404; dentro del alcance y sin permiso,
 * 403.
 *
 * <p><b>Una sede INACTIVA no cae aca.</b> Se lee, se lista y conserva su auditoria: responderle
 * 404 seria borrar historia por la puerta de atras (RF-M03-004, regla maestra 10). Lo que una
 * sede inactiva rechaza son las operaciones NUEVAS, y eso es un 409.
 */
public class ConsultorioNotAccessibleException extends RuntimeException {

	private final Long consultorioId;

	public ConsultorioNotAccessibleException(Long consultorioId) {
		super("La sede solicitada no existe o no esta disponible");
		this.consultorioId = consultorioId;
	}

	/** Id pedido. Sirve para el log correlacionado, jamas para el cuerpo de la respuesta. */
	public Long getConsultorioId() {
		return consultorioId;
	}
}
