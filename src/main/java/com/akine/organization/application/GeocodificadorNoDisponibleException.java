package com.akine.organization.application;

/**
 * El geocodificador no respondio, respondio mal o esta apagado en este despliegue (503).
 *
 * <p>No es un error del cliente ni un dato invalido: la sede se puede guardar igual con la
 * direccion escrita y sin coordenadas, o fijando la ubicacion a mano. Por eso lleva un
 * {@code Retry-After} y no un mensaje de "corrija el pedido".
 */
public class GeocodificadorNoDisponibleException extends RuntimeException {

	/** Segundos sugeridos para reintentar. Nominatim pide no insistir. */
	public static final int SEGUNDOS_PARA_REINTENTAR = 30;

	public GeocodificadorNoDisponibleException(String motivo) {
		super(motivo);
	}

	public GeocodificadorNoDisponibleException(String motivo, Throwable causa) {
		super(motivo, causa);
	}
}
