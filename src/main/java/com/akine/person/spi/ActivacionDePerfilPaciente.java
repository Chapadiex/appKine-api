package com.akine.person.spi;

/**
 * El pedido EXPLICITO de convertir a una Persona en Paciente desde otro modulo (RF-M07-008).
 *
 * <p>Existe porque {@code person.application.OperatingActor} es privado de ese modulo y no puede
 * cruzar el borde.
 *
 * @param motivo por que se activa el perfil. Lo guarda {@code perfil_paciente} y es lo que explica
 *               seis meses despues por que esta persona paso a ser paciente
 */
public record ActivacionDePerfilPaciente(
		long accountId,
		boolean platformAdmin,
		Long organizationId,
		Long consultorioId,
		long personaId,
		String motivo) {
}
