package com.akine.resource.domain.exception;

/**
 * El espacio no existe, o es de otro tenant o de otra sede (404).
 *
 * <p><b>Los tres casos se responden igual, a proposito.</b> Distinguirlos permitiria recorrer
 * ids consecutivos y averiguar cuantos boxes tiene cada centro del SaaS. Para quien no tiene
 * acceso, el recurso no existe.
 *
 * <p>Ojo con lo que esto NO cubre: un espacio dado de baja del PROPIO tenant <b>si</b> se
 * devuelve, con 200. RN-M04-003 exige que los historicos conserven su nombre y su estado, y un
 * 404 ahi seria borrar historia por la puerta de atras.
 */
public class EspacioNotAccessibleException extends RuntimeException {

	private final long espacioId;

	public EspacioNotAccessibleException(long espacioId) {
		super("Espacio no accesible: " + espacioId);
		this.espacioId = espacioId;
	}

	public long getEspacioId() {
		return espacioId;
	}
}
