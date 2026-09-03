package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Edicion de una autorizacion. Lo que no viaja no se toca.
 *
 * <p><b>No lleva la cobertura, la practica ni el estado</b>, y ninguna de las tres es un olvido.
 * Las dos primeras son inmutables: cambiarlas es OTRA autorizacion, porque reescribirian contra
 * que se autorizo al paciente. El estado se mueve con {@code POST /{id}/estado}, que recibe una
 * ACCION y no un estado destino.
 *
 * <p>Tampoco lleva el snapshot del convenio: es una copia congelada y no un campo editable. Una
 * copia que se puede editar no es una copia.
 */
@Schema(description = "Cambios sobre una autorizacion")
public record UpdateAutorizacionRequest(

		@Schema(description = "Numero que devolvio el financiador", example = "AUT-99120034")
		@Size(max = 64, message = "El numero no puede superar los 64 caracteres")
		String numero,

		@Schema(description = "Orden medica que la respalda. Tiene que ser de esta persona",
				example = "51")
		Long ordenMedicaId,

		@Schema(description = "Sesiones otorgadas. No puede quedar por debajo de lo ya consumido",
				example = "10")
		@Positive(message = "La cantidad autorizada tiene que ser mayor que cero")
		Integer cantidadAutorizada,

		@Schema(description = "Primer dia en que habilita", example = "2026-09-01")
		LocalDate vigenciaDesde,

		@Schema(description = "ULTIMO dia, INCLUSIVE", example = "2026-12-31")
		LocalDate vigenciaHasta,

		@Schema(description = "Notas administrativas. NUNCA contenido clinico")
		@Size(max = 500, message = "Las observaciones no pueden superar los 500 caracteres")
		String observaciones,

		@Schema(
				description = "Version que el cliente leyo. Una version vieja responde 409 en vez "
						+ "de pisar el cambio ajeno en silencio",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		long expectedVersion) {
}
