package com.akine.resource.api.dto;

import com.akine.resource.domain.MotivoExcepcion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Edicion de una excepcion (agenda operativa). Reemplaza motivo, fechas, horario y notas; el tipo y el
 * alcance no se editan: cambiarlos es cargar otra excepcion.
 */
@Schema(description = "Datos nuevos de una excepcion. Reemplaza los que tenia: se manda la excepcion "
		+ "entera. El tipo (CIERRE/APERTURA) y el alcance (sede o profesional) no se editan")
public record UpdateExcepcionRequest(

		@Schema(description = "Motivo, de lista cerrada. Con OTRO, las notas son obligatorias",
				example = "VACACIONES", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El motivo de la excepcion es obligatorio")
		MotivoExcepcion motivo,

		@Schema(description = "Primer dia cubierto", example = "2026-12-01",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La fecha de inicio es obligatoria")
		LocalDate fechaDesde,

		@Schema(description = "Primer dia YA NO cubierto, EXCLUSIVO", example = "2026-12-15",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La fecha de fin es obligatoria")
		LocalDate fechaHasta,

		@Schema(description = "Hora local de inicio. OMITIRLA junto con horaHasta significa DIA COMPLETO",
				type = "string", example = "14:00", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaDesde,

		@Schema(description = "Hora local de fin, EXCLUSIVA. 24:00 para llegar a la medianoche",
				type = "string", example = "18:00", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaHasta,

		@Schema(description = "Texto libre operativo. NUNCA informacion clinica. Obligatorio con motivo OTRO",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La observacion no puede superar los 280 caracteres")
		String notes,

		@Schema(description = "Version que el cliente leyo. Si quedo vieja, 409 concurrent-modification",
				example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version de la excepcion es obligatoria")
		@PositiveOrZero(message = "La version no puede ser negativa")
		Long version,

		@Schema(description = "Confirma el cambio aunque deje turnos pendientes fuera de la disponibilidad. "
				+ "Sin esto, un cambio con turnos afectados responde 409 `bloqueo-con-turnos-afectados` con "
				+ "la lista. Los turnos nunca se tocan",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean confirmaTurnosAfectados,

		@Schema(description = "Solo APERTURA de un profesional: igual que en el alta, cierra la otra sede en "
				+ "las franjas superpuestas con su horario alla. Lo reservado alla nunca se sustituye (409 "
				+ "`recurso-ocupado`)",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean sustituirDisponibilidadSuperpuesta) {
}
