package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Activacion del perfil clinico sobre una persona existente (RF-M07-008).
 *
 * <p><b>No lleva ningun dato personal</b>, y esa ausencia es el requerimiento: RN-M07-007 exige
 * reutilizar la identidad sin duplicar DNI, contacto ni datos personales. Si este DTO tuviera un
 * apellido, existiria la forma de que la ficha clinica y la administrativa se separaran.
 *
 * <p>El motivo es opcional porque RF-M07-008 no lo exige: la activacion la dispara el inicio de
 * una atencion, y pedir una justificacion escrita para eso seria friccion sin destinatario.
 */
@Schema(description = "Activacion del perfil clinico. No recibe datos personales: los reutiliza")
public record ActivarPerfilPacienteRequest(

		@Schema(
				description = "Motivo declarado de la activacion. Opcional",
				example = "Inicia rehabilitacion post quirurgica",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String motivo) {
}
