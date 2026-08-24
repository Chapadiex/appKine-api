package com.akine.organization.domain.exception;

/**
 * La membership pedida no existe, o existe y NO esta en el alcance del actor.
 *
 * <p><b>Los dos casos responden 404 y son indistinguibles a proposito.</b> Un 403 confirmaria
 * que ese id existe, y alcanzaria con recorrer numeros consecutivos para enumerar a los
 * colaboradores de los demas centros. Para quien no tiene acceso, el recurso no existe.
 *
 * <p>Es la mitad opuesta de {@link PermissionDeniedException}: fuera del alcance, 404; dentro
 * del alcance y sin permiso, 403. Elegir mal cualquiera de las dos filtra informacion o miente.
 */
public class MembershipNotAccessibleException extends RuntimeException {

	private final Long membershipId;

	public MembershipNotAccessibleException(Long membershipId) {
		super("La membership solicitada no existe o no esta disponible");
		this.membershipId = membershipId;
	}

	/** Id pedido. Sirve para el log correlacionado, jamas para el cuerpo de la respuesta. */
	public Long getMembershipId() {
		return membershipId;
	}
}
