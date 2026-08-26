package com.akine.resource.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Edicion parcial de un bloque de disponibilidad (RF-M05-005).
 *
 * <p>Semantica de PATCH aunque el verbo sea {@code PUT}: cada campo {@code null} deja el valor
 * como estaba. El verbo lo fija el contrato de la etapa; la semantica la fija este documento.
 *
 * <p><b>El profesional no se puede cambiar.</b> No hay campo de membership y no es un olvido:
 * reasignar un bloque a otro profesional no es una edicion sino un bloque nuevo (RN-M05-001), y
 * permitirlo dejaria la autoria historica apuntando a quien nunca atendio en esa franja.
 */
@Schema(description = "Cambios a aplicar sobre un bloque de disponibilidad vigente")
public record UpdateBloqueRequest(

		@Schema(
				description = "Nuevo dia de la semana, ISO-8601: lunes = 1 .. domingo = 7. "
						+ "Omitirlo lo deja como estaba",
				example = "3",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Min(value = 1, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)")
		@Max(value = 7, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)")
		Integer diaSemana,

		@Schema(
				description = "Nueva hora local de inicio. Omitirla la deja como estaba",
				type = "string",
				example = "10:00",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaDesde,

		@Schema(
				description = "Nueva hora local de fin, EXCLUSIVA. 24:00 para un bloque que "
						+ "llega a la medianoche. Omitirla la deja como estaba",
				type = "string",
				example = "24:00",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaHasta,

		@Schema(
				description = "Nuevo primer dia de vigencia. Omitirlo lo deja como estaba",
				example = "2026-09-01",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaDesde,

		@Schema(
				description = "Nuevo fin de vigencia, EXCLUSIVO. Se IGNORA si "
						+ "limpiarVigenciaHasta viene en true",
				example = "2027-01-01",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(
				description = "Saca el fin de vigencia y deja el bloque sin fin previsto. Existe "
						+ "porque un null no puede expresarlo: \"no toques el fin\" y \"saca el "
						+ "fin\" son dos intenciones distintas, y con un solo campo nulable la "
						+ "segunda es imposible de pedir",
				example = "false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean limpiarVigenciaHasta,

		@Schema(
				description = "Version que el cliente leyo. Se compara ANTES de mutar: si quedo "
						+ "vieja la respuesta es 409 concurrent-modification y hay que recargar. "
						+ "Sin esto dos ediciones simultaneas se pisan y el segundo en guardar "
						+ "borra el cambio del primero sin que nadie se entere",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version del bloque es obligatoria")
		@PositiveOrZero(message = "La version no puede ser negativa")
		Long version) {
}
