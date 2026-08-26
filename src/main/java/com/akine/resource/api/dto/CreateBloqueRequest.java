package com.akine.resource.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Alta de un bloque recurrente de disponibilidad de un profesional en una sede (RF-M05-003).
 *
 * <p>El profesional y la sede NO viajan en el cuerpo: salen de la ruta y del contexto ya
 * validado. Aceptarlos aca permitiria que el cuerpo y la URL dijeran cosas distintas y alguna
 * capa tendria que elegir cual gana.
 *
 * <h2>Los dos ejes temporales, que se confunden todo el tiempo</h2>
 *
 * <p>{@code diaSemana} + {@code horaDesde}/{@code horaHasta} son la RECURRENCIA: "los martes de
 * 09 a 13". {@code vigenciaDesde}/{@code vigenciaHasta} son la VENTANA en la que esa recurrencia
 * rige: "desde marzo y hasta que vuelva de la licencia". Son ortogonales, y mandar una sin la
 * otra es el error mas frecuente al escribir el cliente.
 */
@Schema(description = "Datos para dar de alta un bloque recurrente de disponibilidad")
public record CreateBloqueRequest(

		@Schema(
				description = "Dia de la semana en formato ISO-8601: lunes = 1 .. domingo = 7",
				example = "2",
				minimum = "1",
				maximum = "7",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El dia de la semana es obligatorio")
		@Min(value = 1, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)")
		@Max(value = 7, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)")
		Integer diaSemana,

		@Schema(
				description = "Hora local de inicio, en la zona de la sede. NUNCA un instante "
						+ "UTC: un lunes 09:00 tiene que seguir siendo 09:00 despues de un "
						+ "cambio de huso o de horario de verano",
				type = "string",
				example = "09:00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La hora de inicio es obligatoria")
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaDesde,

		@Schema(
				description = "Hora local de fin, EXCLUSIVA. Un bloque que llega a la medianoche "
						+ "se manda como 24:00, que es el unico valor que expresa el final del "
						+ "dia en un extremo exclusivo: 00:00 significaria el principio. Nunca "
						+ "cruza al dia siguiente",
				type = "string",
				example = "13:00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La hora de fin es obligatoria")
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaHasta,

		@Schema(
				description = "Primer dia en que el bloque rige. Omitirlo significa HOY en la "
						+ "zona de la sede, que es lo que quiere decir quien carga el horario de "
						+ "un profesional que ya esta atendiendo",
				example = "2026-09-01",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaDesde,

		@Schema(
				description = "Primer dia en que el bloque ya NO rige, EXCLUSIVO. Omitirlo "
						+ "significa sin fin previsto, que es el caso normal",
				example = "2027-01-01",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta) {
}
