package com.akine.person.api.dto;

import com.akine.person.domain.TipoDocumento;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Alta de una persona en el padron de la organizacion.
 *
 * <p><b>Esto da de alta una PERSONA, nunca un paciente.</b> No hay ningun campo para pedir las
 * dos cosas a la vez, y es deliberado: RF-M07-007 exige poder registrar a quien viene a una
 * actividad no clinica sin crearle nada clinico, y RF-M07-010 exige que ese camino no lo cree
 * por accidente. Convertir a la persona en paciente es
 * {@code POST /api/v1/personas/{personaId}/perfil-paciente}.
 *
 * <p><b>Lo unico obligatorio son el apellido y el nombre.</b> No el documento: una persona sin
 * DNI es un caso real —un menor, un extranjero recien llegado, una urgencia— y exigirlo obligaria
 * al mostrador a inventar uno, que es la peor salida posible porque contamina el padron con
 * claves que despues chocan de verdad.
 */
@Schema(description = "Datos para dar de alta una persona. No crea ningun perfil clinico")
public record CreatePersonaRequest(

		@Schema(
				description = "Tipo de documento. Viaja junto con el numero: los dos o ninguno",
				example = "DNI",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		TipoDocumento tipoDocumento,

		@Schema(
				description = "Numero de documento tal como figura. Se guarda como se tipeo y se "
						+ "compara normalizado, asi que 12.345.678 y 12345678 son el mismo "
						+ "documento y el segundo no entra si ya existe el primero",
				example = "12345678",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 32, message = "El numero de documento no puede superar los 32 caracteres")
		String numeroDocumento,

		@Schema(description = "Apellido", example = "Perez",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El apellido es obligatorio")
		@Size(max = 120, message = "El apellido no puede superar los 120 caracteres")
		String apellido,

		@Schema(description = "Nombre", example = "Ana Maria",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre es obligatorio")
		@Size(max = 120, message = "El nombre no puede superar los 120 caracteres")
		String nombre,

		@Schema(
				description = "Fecha de nacimiento. Opcional: un alta de mostrador puede no "
						+ "tenerla, y exigirla trabaria el alta por un dato que se completa "
						+ "despues",
				example = "1985-03-14",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Past(message = "La fecha de nacimiento tiene que ser anterior a hoy")
		LocalDate fechaNacimiento,

		@Schema(
				description = "Correo de contacto ADMINISTRATIVO. No es una credencial de acceso: "
						+ "la cuenta del portal es otra entidad y puede no existir",
				example = "ana.perez@example.com",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Email(message = "El correo no tiene un formato valido")
		@Size(max = 320, message = "El correo no puede superar los 320 caracteres")
		String email,

		@Schema(description = "Telefono de contacto", example = "+54 11 5555-0000",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 40, message = "El telefono no puede superar los 40 caracteres")
		String telefono,

		@Schema(
				description = "Observaciones ADMINISTRATIVAS. Ningun dato clinico va aca: la "
						+ "informacion clinica vive en los modulos clinicos (RN-M07-003)",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 500, message = "Las notas no pueden superar los 500 caracteres")
		String notas,

		@Schema(
				description = "El operador ya vio las coincidencias y declara que es otra "
						+ "persona. Sin esto, un alta que coincide en nombre completo o en "
						+ "telefono con alguien ya registrado se rechaza con 409 "
						+ "persona-posible-duplicado y la lista de candidatos. NO saltea el "
						+ "documento repetido, que es un invariante duro",
				example = "false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean confirmaPosibleDuplicado) {
}
