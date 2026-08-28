package com.akine.person.api.dto;

import com.akine.person.domain.TipoDocumento;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Edicion parcial de una persona (RF-M07-003).
 *
 * <p>Semantica de PATCH: lo que no viene, no se toca. Misma convencion que el resto de la API.
 * <b>Un campo en {@code null} no vacia el valor</b> — la consecuencia y el motivo estan en
 * {@code Persona.updateDatos}.
 *
 * <p>No incluye ni el estado ni el perfil de paciente: el primero es la baja logica (RF-M07-005,
 * etapa 03.02) y el segundo tiene su propia ruta, con su propio permiso y su propio evento de
 * auditoria. Que "volverse paciente" no sea un campo editable de la ficha es exactamente lo que
 * hace que no ocurra por accidente.
 */
@Schema(description = "Cambios a aplicar sobre una persona. Lo que no viene, no se toca")
public record UpdatePersonaRequest(

		@Schema(
				description = "Tipo de documento. Mandar el tipo reemplaza el par completo; sin "
						+ "el, el documento no se toca",
				example = "DNI")
		TipoDocumento tipoDocumento,

		@Schema(
				description = "Numero de documento. Corregirlo puede chocar con el de otra "
						+ "persona vigente y responder 409, igual que en el alta",
				example = "12345678")
		@Size(max = 32, message = "El numero de documento no puede superar los 32 caracteres")
		String numeroDocumento,

		@Schema(description = "Apellido", example = "Perez")
		@Size(max = 120, message = "El apellido no puede superar los 120 caracteres")
		String apellido,

		@Schema(description = "Nombre", example = "Ana Maria")
		@Size(max = 120, message = "El nombre no puede superar los 120 caracteres")
		String nombre,

		@Schema(description = "Fecha de nacimiento", example = "1985-03-14")
		@Past(message = "La fecha de nacimiento tiene que ser anterior a hoy")
		LocalDate fechaNacimiento,

		@Schema(description = "Correo de contacto administrativo",
				example = "ana.perez@example.com")
		@Email(message = "El correo no tiene un formato valido")
		@Size(max = 320, message = "El correo no puede superar los 320 caracteres")
		String email,

		@Schema(description = "Telefono de contacto", example = "+54 11 5555-0000")
		@Size(max = 40, message = "El telefono no puede superar los 40 caracteres")
		String telefono,

		@Schema(description = "Observaciones administrativas. Nada clinico")
		@Size(max = 500, message = "Las notas no pueden superar los 500 caracteres")
		String notas,

		@Schema(
				description = "Version que el cliente leyo. Si quedo vieja, la edicion se rechaza "
						+ "con 409 en vez de pisar el cambio de otro operador",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@PositiveOrZero(message = "La version esperada no puede ser negativa")
		long expectedVersion) {
}
