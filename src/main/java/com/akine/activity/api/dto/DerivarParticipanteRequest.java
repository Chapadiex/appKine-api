package com.akine.activity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Lo que manda quien deriva a un participante de clase al circuito clinico (RF-M28-008).
 *
 * <p><b>Ni un dato clinico en el cuerpo.</b> El Caso se elige, no se crea: abrirlo pide
 * diagnostico y objetivo, y ponerlos aca seria copiar PHI al modulo grupal. La pantalla abre el
 * Caso con el endpoint de M10 y despues deriva.
 */
@Schema(
		name = "DerivarParticipante",
		description = "Vincula la participacion (la asistencia) a un Caso Clinico existente y activo "
				+ "de esa persona. No crea Sesion, no consume autorizacion y no devenga.")
public record DerivarParticipanteRequest(

		@Schema(description = "Caso Clinico destino, activo y de la historia de esa persona",
				example = "88")
		@Positive long casoClinicoId,

		@Schema(description = "Plan de Tratamiento del Caso. Opcional (RF-M11-007)", example = "12")
		@Positive Long planTratamientoId,

		@Schema(description = "Autorizacion declarada. Opcional (RF-M11-008); si viene, tiene que "
				+ "habilitar el dia de la clase. No se consume", example = "7")
		@Positive Long autorizacionId,

		@Schema(description = "Por que se deriva. Opcional: el motivo clinico vive en el Caso",
				example = "Indicacion del kinesiologo tras la clase")
		@Size(max = 300) String motivo,

		@Schema(description = "true activa el perfil de paciente si la persona no lo tiene (exige "
				+ "paciente:manage). Si falta o es false, una persona sin perfil es 409 "
				+ "persona-sin-perfil-paciente.",
				example = "false",
				defaultValue = "false")
		Boolean activarPerfilPaciente) {

	/** Ausente se lee como {@code false}: la conversion en paciente nunca es implicita. */
	public boolean activarPerfilPacienteEfectivo() {
		return Boolean.TRUE.equals(activarPerfilPaciente);
	}
}
