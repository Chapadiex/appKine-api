package com.akine.assistant.api.dto;

import com.akine.assistant.application.Interpretacion;
import com.akine.assistant.spi.Intencion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Lo que el asistente entendio de una pregunta. El cliente lo devuelve tal cual en el historial. */
@Schema(description = "Lo que el asistente entendio de una pregunta. El cliente lo devuelve tal cual en el "
		+ "historial del siguiente turno, sin modificarlo.")
public record InterpretacionDto(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Intencion intencion,
		@Schema(description = "Nombre de la persona tal como se escribio", maxLength = 120) @Size(max = 120) String persona,
		@Schema(description = "Actividad de la clase, en minusculas", maxLength = 120) @Size(max = 120) String clase,
		@Schema(description = "Periodo en el vocabulario del asistente (HOY, ESTA_SEMANA, DIA:LUNES, MES:OCTUBRE...)",
				maxLength = 40) @Size(max = 40) String periodo,
		@Schema(description = "ECONOMICO, TURNOS o CLASES, solo para REPORTE", maxLength = 20) @Size(max = 20) String reporte) {

	public static InterpretacionDto de(Interpretacion i) {
		return new InterpretacionDto(i.intencion(), i.persona(), i.clase(), i.periodo(), i.reporte());
	}

	public Interpretacion aInterpretacion() {
		return new Interpretacion(intencion, persona, clase, periodo, reporte);
	}
}
