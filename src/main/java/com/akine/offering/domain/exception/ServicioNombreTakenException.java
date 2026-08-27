package com.akine.offering.domain.exception;

/**
 * Ya hay un {@code Servicio} VIGENTE con ese nombre en el catalogo global (409).
 *
 * <p>Traduce la violacion de {@code uk_servicio_nombre_vigente} (migracion V24), por el mismo
 * motivo de ventana de carrera que {@link ServicioCodigoTakenException}: la comprobacion sin
 * ventana es la del unique, no un SELECT previo.
 *
 * <p>El nombre de un servicio dado de baja no colisiona: el unique lleva {@code deleted_key} como
 * discriminador, asi que se puede reusar despues de una baja logica.
 */
public class ServicioNombreTakenException extends RuntimeException {

	private final String nombre;

	public ServicioNombreTakenException(String nombre) {
		super("Ya existe un servicio vigente con ese nombre en el catalogo global");
		this.nombre = nombre;
	}

	public String getNombre() {
		return nombre;
	}
}
