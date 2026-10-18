package com.akine.activity.application;

/**
 * El pedido de derivar a un participante de clase al circuito clinico (RF-M28-008, AKINE-08.04).
 *
 * <p><b>No trae nada clinico.</b> El Caso no se crea aca: se elige uno que ya existe (§9.2 del
 * diseño). Lo que viaja son ids y la bandera explicita de RF-M07-010.
 *
 * @param planTratamientoId     opcional (RF-M11-007): si viene, tiene que ser del Caso
 * @param autorizacionId        opcional (RF-M11-008): si viene, tiene que habilitar ese dia. No se
 *                              consume
 * @param activarPerfilPaciente {@code true} es el operador diciendo "convertilo en paciente".
 *                              Sin ella, una persona sin perfil es 409
 * @param justificacion         la cabecera {@code X-Justificacion-Acceso}; la evalua {@code clinical}
 */
public record DerivarParticipanteCommand(
		long casoClinicoId,
		Long planTratamientoId,
		Long autorizacionId,
		String motivo,
		boolean activarPerfilPaciente,
		String justificacion) {
}
