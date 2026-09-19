package com.akine.clinical.domain.exception;

/**
 * Se intento cerrar o reabrir un caso sin declarar por que (400, no 409).
 *
 * <p>No hay conflicto de estado: el caso esta como tiene que estar y el actor tiene permiso. Falta
 * un dato del pedido, y un 409 mandaria al profesional a reintentar el mismo cuerpo, que va a
 * fallar exactamente igual. Mismo reparto que {@link EnmiendaSinMotivoException} en 04.02.
 *
 * <p><b>Sin motivo, un cierre es indistinguible de un abandono</b> y el historial deja de servir
 * para lo unico que sirve. La regla vive en {@code CasoClinico} y no solo en el DTO: ningun camino
 * de escritura —ni uno futuro que no pase por el controller— puede cerrar un caso sin explicarlo.
 */
public class CierreDeCasoSinMotivoException extends RuntimeException {

	private final Long casoClinicoId;

	public CierreDeCasoSinMotivoException(Long casoClinicoId) {
		super("El cierre y la reapertura de un caso clinico exigen un motivo declarado");
		this.casoClinicoId = casoClinicoId;
	}

	public Long getCasoClinicoId() {
		return casoClinicoId;
	}
}
