package com.akine.person.api.dto;

import com.akine.person.application.PersonaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Una persona del padron de la organizacion (M07).
 *
 * <p>No lleva {@code organizationId} ni {@code consultorioId}: el tenant sale del contexto
 * validado del request y una persona no pertenece a ninguna sede.
 *
 * <p><b>{@code esPaciente} es de solo lectura y no tiene columna detras</b>: sale de si hay o no
 * un perfil clinico vigente. No existe ninguna operacion que lo reciba — activar el perfil es
 * {@code POST /api/v1/personas/{personaId}/perfil-paciente}.
 */
@Schema(description = "Persona del padron. Ser persona no implica ser paciente")
public record PersonaResponse(

		@Schema(description = "Identificador de la persona", example = "42")
		long id,

		@Schema(description = "Tipo de documento, o null si no declaro documento", example = "DNI",
				allowableValues = {"DNI", "LC", "LE", "CI", "PASAPORTE", "OTRO"})
		String tipoDocumento,

		@Schema(description = "Numero de documento tal como se cargo", example = "12345678")
		String numeroDocumento,

		@Schema(description = "Apellido", example = "Perez")
		String apellido,

		@Schema(description = "Nombre", example = "Ana Maria")
		String nombre,

		@Schema(description = "Fecha de nacimiento, o null", example = "1985-03-14")
		LocalDate fechaNacimiento,

		@Schema(description = "Correo de contacto administrativo, o null")
		String email,

		@Schema(description = "Telefono de contacto, o null", example = "+54 11 5555-0000")
		String telefono,

		@Schema(description = "Observaciones administrativas, o null")
		String notas,

		@Schema(
				description = "Si la persona tiene perfil clinico VIGENTE en esta organizacion. "
						+ "Derivado, no una columna. false no significa que nunca lo tuvo: puede "
						+ "haberlo tenido y estar dado de baja",
				example = "false")
		boolean esPaciente,

		@Schema(description = "Id del perfil clinico vigente, o null si no es paciente",
				example = "7")
		Long perfilPacienteId,

		@Schema(description = "Instante UTC en que se activo el perfil clinico, o null")
		Instant perfilActivadoEn,

		@Schema(
				description = "Ciclo de vida administrativo de la ficha. Una persona INACTIVA se "
						+ "sigue leyendo con 200: sus historicos tienen que resolver",
				example = "ACTIVO",
				allowableValues = {"ACTIVO", "INACTIVO"})
		String estado,

		@Schema(description = "Instante UTC de la baja logica, o null")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja, o null")
		String deactivationReason,

		@Schema(description = "Version para el bloqueo optimista. Reenviarla al editar",
				example = "0")
		long version) {

	public static PersonaResponse from(PersonaView view) {
		return new PersonaResponse(
				view.id(),
				view.tipoDocumento(),
				view.numeroDocumento(),
				view.apellido(),
				view.nombre(),
				view.fechaNacimiento(),
				view.email(),
				view.telefono(),
				view.notas(),
				view.esPaciente(),
				view.perfilPacienteId(),
				view.perfilActivadoEn(),
				view.estado(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version());
	}
}
