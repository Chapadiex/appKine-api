package com.akine.person.domain.exception;

/**
 * Ya hay otra autorizacion APROBADA de la misma cobertura y practica solapada en el tiempo (409).
 *
 * <p>Es un duplicado de carga, y su consecuencia es concreta: el saldo autorizado se contaria dos
 * veces y el centro creeria tener veinte sesiones donde el financiador dio diez.
 *
 * <p>Ningun unique lo puede expresar —01-01..06-30 y 03-01..12-31 no comparten ningun valor de
 * columna y MySQL 8.4 no tiene exclusion constraints—: lo hace cumplir el lock de
 * {@code autorizacion_persona_lock}, en {@code READ_COMMITTED}.
 *
 * <p><b>Lo que NO alcanza esta excepcion:</b> dos autorizaciones CONSECUTIVAS —renovar es el caso
 * normal—, dos de practicas distintas aunque se pisen, y cualquier PENDIENTE u OBSERVADA, que
 * todavia no autoriza ninguna cantidad.
 */
public class AutorizacionSuperpuestaException extends RuntimeException {

	private final long autorizacionExistenteId;

	public AutorizacionSuperpuestaException(long autorizacionExistenteId) {
		super("Ya existe una autorizacion aprobada de esa practica que se solapa con esta");
		this.autorizacionExistenteId = autorizacionExistenteId;
	}

	public long getAutorizacionExistenteId() {
		return autorizacionExistenteId;
	}
}
