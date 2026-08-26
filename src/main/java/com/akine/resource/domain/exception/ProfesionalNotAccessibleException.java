package com.akine.resource.domain.exception;

/**
 * La membership de la ruta no existe o es de otro tenant (404).
 *
 * <p>Los dos casos se responden igual, por la regla heredada de 01.01: distinguirlos permitiria
 * recorrer ids consecutivos y contar los colaboradores de cada centro del SaaS.
 *
 * <p><b>No confundir con {@link ProfesionalNoVinculadoException}</b>, que es 409. La diferencia
 * es exactamente la que separa 404 de 403/409 en todo este repo: aca el actor no puede saber que
 * ese vinculo existe, alla el vinculo es del propio tenant y el actor lo tiene delante en la
 * pantalla de colaboradores. El primero no puede filtrar nada; el segundo tiene que explicar por
 * que la operacion no procede.
 *
 * <p><b>Nota para la tarea 10:</b> esta excepcion NO figura en la lista de archivos del brief de
 * la tarea 7. Se agrego porque el brief nombra un solo tipo para el profesional
 * —{@code ProfesionalNoVinculadoException}— y ese es 409, asi que sin esta el caso "membership
 * de otro tenant" tendria que salir por el 404 de otra entidad y la fila del log mentiria sobre
 * que fue lo que no resolvio. El advice del modulo tiene que mapearla a 404.
 */
public class ProfesionalNotAccessibleException extends RuntimeException {

	private final long membershipId;

	public ProfesionalNotAccessibleException(long membershipId) {
		super("Profesional no accesible: " + membershipId);
		this.membershipId = membershipId;
	}

	public long getMembershipId() {
		return membershipId;
	}
}
