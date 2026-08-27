package com.akine.offering.api.dto;

import com.akine.offering.domain.Modalidad;
import com.akine.offering.domain.Naturaleza;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Alta de un servicio del catalogo global.
 *
 * <p><b>Sin {@code Idempotency-Key}, con el mismo criterio que el catalogo clinico:</b> un
 * servicio no consume cupo de ningun plan, asi que lo unico que un reintento podria producir es
 * una fila duplicada, y contra eso el unique de codigo es una garantia mas fuerte que una clave
 * que depende de que el cliente la mande bien. El reintento responde 409 y no crea nada.
 */
@Schema(description = "Datos para dar de alta un servicio del catalogo global")
public record CreateServicioRequest(

		@Schema(
				description = "Clave estable con la que las ofertas lo referencian. NO se puede "
						+ "cambiar despues. Unico entre los servicios VIGENTES: el codigo de uno "
						+ "dado de baja si se puede reusar",
				example = "KINESIOLOGIA_SESION",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El codigo del servicio es obligatorio")
		@Size(max = 64, message = "El codigo no puede superar los 64 caracteres")
		String codigo,

		@Schema(
				description = "Nombre visible. Unico entre los servicios vigentes, comparado sin "
						+ "distinguir mayusculas ni acentos",
				example = "Sesion de kinesiologia",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre del servicio es obligatorio")
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String nombre,

		@Schema(description = "Descripcion libre. Nunca contenido clinico",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La descripcion no puede superar los 280 caracteres")
		String descripcion,

		@Schema(
				description = "Que clase de prestacion es. Ninguna regla del sistema ramifica "
						+ "por este valor: lo que decide el comportamiento son los defaults de "
						+ "abajo y lo que la oferta termine declarando",
				example = "TERAPEUTICO",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La naturaleza del servicio es obligatoria")
		Naturaleza naturaleza,

		@Schema(
				description = "Modalidad SUGERIDA al crear una oferta. La oferta puede apartarse: "
						+ "esto es un default, no una restriccion",
				example = "INDIVIDUAL",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La modalidad por defecto es obligatoria")
		Modalidad modalidadDefault,

		@Schema(
				description = "Default de si la prestacion exige un caso clinico abierto. Si se "
						+ "omite, false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereCasoClinicoDefault,

		@Schema(
				description = "Default de si la prestacion genera registro clinico. Si se omite, "
						+ "false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean generaRegistroClinicoDefault) {
}
