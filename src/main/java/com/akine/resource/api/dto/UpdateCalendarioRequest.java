package com.akine.resource.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/**
 * Edicion de la politica de calendario de una sede (RF-M05-004).
 *
 * <p>Semantica de PATCH aunque el verbo sea {@code PUT}: cada campo {@code null} deja el valor
 * como estaba. <b>{@code cierraPorFeriado} viaja como {@link Boolean} y no como {@code boolean}
 * justamente por eso</b>: un nulo NO es {@code false}. Ese es el error que convierte "no toques
 * la politica" en "abri todos los feriados" para todos los profesionales de la sede, hacia
 * adelante y hacia atras.
 *
 * <p><b>No lleva {@code version} y no puede dar 409 por concurrencia.</b> La fila de politica se
 * crea a demanda: un cliente que nunca la vio no tiene ninguna version que mandar, y exigirsela
 * le impediria su primera edicion. Consecuencia declarada: dos administradores que editan la
 * politica a la vez no producen conflicto, gana el segundo, y la auditoria conserva los dos
 * cambios con su autor. La {@code version} viaja igual en la RESPUESTA, para que la pantalla
 * pueda detectar que alguien mas la cambio.
 */
@Schema(description = "Cambios a aplicar sobre la politica de calendario de la sede")
public record UpdateCalendarioRequest(

		@Schema(
				description = "Pais cuyo calendario de feriados usa la sede. Omitirlo lo deja "
						+ "como estaba; el valor por defecto de una sede nueva es AR",
				example = "AR",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		// Pattern y no Size: el @ApiResponse promete 400 para "no es un codigo de dos letras", y
		// con Size a secas {"pais":"12"} pasaba la validacion y llegaba al servicio. La anotacion
		// y lo que el contrato promete tienen que decir lo mismo.
		@Pattern(regexp = "[A-Za-z]{2}",
				message = "El pais es un codigo de dos letras, por ejemplo AR")
		String pais,

		@Schema(
				description = "Si la sede cierra los feriados de ese pais. OMITIRLO lo deja como "
						+ "estaba: null NO es false. Apagarlo abre de golpe todos los feriados "
						+ "del calendario para todos los profesionales de la sede, por eso el "
						+ "cambio se audita aunque sea un solo flag",
				example = "true",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean cierraPorFeriado) {
}
