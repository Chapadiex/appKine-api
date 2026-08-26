package com.akine.resource.api.dto;

import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.TipoExcepcion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Alta de un cierre o de una apertura puntual de disponibilidad (RF-M05-004).
 *
 * <p>La sede NO viaja en el cuerpo: sale de la ruta y del contexto ya validado. El profesional
 * SI viaja, y es la diferencia importante: una excepcion puede ser de un profesional o de la
 * SEDE ENTERA, y ese alcance es un dato del pedido, no de la ruta.
 *
 * <h2>Dos errores que este cuerpo hace faciles, y como evitarlos</h2>
 *
 * <ol>
 *   <li><b>{@code fechaHasta} es EXCLUSIVA.</b> Un cierre de un solo dia se carga como
 *       {@code [D, D+1)}. Mandar {@code fechaDesde == fechaHasta} no cierra "ese dia": cierra
 *       cero dias.</li>
 *   <li><b>{@code membershipId} nulo significa ALCANCE, no un dato faltante.</b> Omitirlo cierra
 *       la sede entera para todos los profesionales. Es la misma convencion que
 *       {@code consultorio_id} nulo en {@code membership}.</li>
 * </ol>
 */
@Schema(description = "Datos para dar de alta un cierre o una apertura puntual")
public record CreateExcepcionRequest(

		@Schema(
				description = "Vinculo del profesional al que se le carga la excepcion. "
						+ "OMITIRLO significa SEDE ENTERA —afecta a TODOS los profesionales—, "
						+ "no un dato faltante",
				example = "1",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Long membershipId,

		@Schema(
				description = "CIERRE recorta disponibilidad; APERTURA la habilita donde no la "
						+ "habia. La apertura NO es el caso raro: es como un centro declara que "
						+ "atiende un feriado o que suma una banda un sabado puntual",
				example = "CIERRE",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El tipo de excepcion es obligatorio")
		TipoExcepcion tipo,

		@Schema(
				description = "Motivo, de lista cerrada, para que un reporte pueda agrupar sin "
						+ "normalizar despues",
				example = "LICENCIA",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El motivo de la excepcion es obligatorio")
		MotivoExcepcion motivo,

		@Schema(
				description = "Primer dia cubierto por la excepcion",
				example = "2026-09-07",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La fecha de inicio es obligatoria")
		LocalDate fechaDesde,

		@Schema(
				description = "Primer dia YA NO cubierto, EXCLUSIVO. Un cierre de un solo dia se "
						+ "carga como [D, D+1)",
				example = "2026-09-08",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La fecha de fin es obligatoria")
		LocalDate fechaHasta,

		@Schema(
				description = "Hora local de inicio. OMITIRLA junto con horaHasta significa DIA "
						+ "COMPLETO. Vienen las dos o ninguna",
				type = "string",
				example = "14:00",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaDesde,

		@Schema(
				description = "Hora local de fin, EXCLUSIVA. 24:00 para llegar a la medianoche. "
						+ "Viene junto con horaDesde o no viene ninguna",
				type = "string",
				example = "18:00",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaHasta,

		@Schema(
				description = "Feriado que motivo la excepcion, para trazar el origen cuando la "
						+ "carga nace del calendario nacional",
				example = "5",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Long feriadoId,

		@Schema(
				description = "Texto libre operativo. NUNCA informacion clinica",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La observacion no puede superar los 280 caracteres")
		String notes) {
}
