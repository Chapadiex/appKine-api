package com.akine.offering.domain.exception;

/**
 * Ya hay un {@code Servicio} VIGENTE con ese codigo en el catalogo global (409).
 *
 * <p>La garantia real no es una consulta previa sino el unique {@code uk_servicio_codigo_vigente}
 * de la migracion V24: entre un SELECT de comprobacion y el INSERT/UPDATE hay una ventana en la
 * que otro request entra. Esta excepcion se lanza traduciendo la violacion del unique, que es la
 * unica comprobacion que no tiene ventana. Mismo patron que
 * {@code resource.domain.exception.EspacioNameTakenException}.
 *
 * <p>El codigo de un servicio dado de baja no colisiona: el unique lleva {@code deleted_key}
 * como discriminador, asi que se puede reusar despues de una baja logica.
 */
public class ServicioCodigoTakenException extends RuntimeException {

	private final String codigo;

	public ServicioCodigoTakenException(String codigo) {
		super("Ya existe un servicio vigente con ese codigo en el catalogo global");
		this.codigo = codigo;
	}

	public String getCodigo() {
		return codigo;
	}
}
