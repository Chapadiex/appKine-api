package com.akine.resource.domain.exception;

/**
 * El bloque no existe, o es de otro tenant, de otra sede o de otro profesional (404).
 *
 * <p><b>Los cuatro casos se responden igual, a proposito.</b> Distinguirlos permitiria recorrer
 * ids consecutivos y averiguar cuantos profesionales tiene cada centro del SaaS y cuando
 * atienden. Para quien no tiene acceso, el recurso no existe. Es la misma regla heredada de
 * 01.01 que sostiene {@code EspacioNotAccessibleException}.
 *
 * <p>El cuarto caso —"de otro profesional"— merece un renglon propio porque no es obvio: el
 * puerto acota por tenant y por sede, pero <b>no</b> por membership, y la ruta lleva las dos
 * cosas. Sin la comprobacion explicita, un {@code CONSULTORIO_ADMIN} legitimo podria editar el
 * bloque de un profesional escribiendo el id de otro en la URL, y la fila de auditoria quedaria
 * contra la membership equivocada.
 *
 * <p>Ojo con lo que esto NO cubre: un bloque dado de baja del PROPIO profesional <b>si</b> se
 * devuelve. RN-M05-003 exige que la historia se conserve, y un 404 ahi la borraria por la
 * puerta de atras.
 */
public class BloqueNotAccessibleException extends RuntimeException {

	private final long bloqueId;

	public BloqueNotAccessibleException(long bloqueId) {
		super("Bloque de disponibilidad no accesible: " + bloqueId);
		this.bloqueId = bloqueId;
	}

	public long getBloqueId() {
		return bloqueId;
	}
}
