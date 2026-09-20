package com.akine.clinical.spi;

import java.util.List;

/**
 * Lo que {@code clinical} sabe de una participacion, para que el llamador pueda explicar bloqueos.
 *
 * <p>Es una <b>lectura clinica</b>: exige permiso, justificacion o relacion asistencial, y queda
 * auditada. No es una consulta barata que una pantalla pueda hacer en loop — es deliberado.
 *
 * @param historiaClinicaId {@code null} si esa persona todavia no tiene historia abierta. No es un
 *                          bloqueo: la derivacion la abre. Viaja para que la pantalla pueda decir
 *                          "se le va a abrir historia" antes de que ocurra
 * @param derivaciones      todas, vigentes y revertidas, mas nueva primero. Las revertidas viajan
 *                          porque "ya lo derivaron y lo deshicieron" es informacion distinta de
 *                          "nunca lo derivaron", y la segunda no explica nada
 */
public record EstadoClinicoDeParticipacion(
		Long historiaClinicaId, List<DerivacionSnapshot> derivaciones) {

	/** {@code true} si hay al menos un vinculo en pie. */
	public boolean tieneDerivacionVigente() {
		return derivaciones.stream().anyMatch(DerivacionSnapshot::estaVigente);
	}
}
