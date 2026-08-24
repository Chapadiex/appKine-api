package com.akine.organization.domain.exception;

/**
 * Ya hay una sede VIGENTE con ese nombre en el tenant.
 *
 * <p>Sale de la violacion de {@code uk_consultorio_org_name_vigente}, no de un chequeo previo:
 * dos altas simultaneas con el mismo nombre leerian las dos "no existe". Quien decide es la
 * restriccion, y el perdedor recibe <b>409</b>, nunca un 500.
 *
 * <p>El nombre NO vuelve en el cuerpo de la respuesta. Lo mando el cliente, y reflejar en la
 * respuesta lo que llega en el request es el vector clasico de XSS reflejado; va al log, que es
 * donde sirve. Mismo criterio que {@link OrganizationSlugTakenException}.
 *
 * <p>Solo colisiona con las sedes VIGENTES: el nombre de una sede dada de baja se puede reusar.
 * El por que de la columna generada que lo permite esta en la migracion V18.
 */
public class ConsultorioNameTakenException extends RuntimeException {

	private final transient String name;

	public ConsultorioNameTakenException(String name) {
		super("Ya existe una sede vigente con ese nombre en la organizacion");
		this.name = name;
	}

	public String getName() {
		return name;
	}
}
