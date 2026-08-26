package com.akine.resource.api.dto;

import com.akine.resource.application.ExcepcionView;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Un cierre o una apertura puntual tal como los publica la API (RF-M05-004).
 *
 * <p>Las horas viajan con la convencion de {@link HoraDelDia}: la medianoche es {@code "24:00"}.
 */
@Schema(description = "Cierre o apertura puntual de disponibilidad")
public record ExcepcionResponse(

		@Schema(description = "Identificador de la excepcion", example = "1")
		long id,

		@Schema(description = "Organizacion propietaria", example = "1")
		long organizationId,

		@Schema(description = "Sede en la que rige la excepcion", example = "1")
		long consultorioId,

		@Schema(description = "Vinculo del profesional afectado. null = alcance SEDE ENTERA. La "
				+ "pantalla TIENE que mostrarlo distinto: una excepcion de sede afecta a todos "
				+ "los profesionales, y en un feriado una APERTURA de sede reemplaza el horario "
				+ "base de todos ellos", example = "1")
		Long membershipId,

		@Schema(description = "CIERRE recorta disponibilidad; APERTURA la habilita",
				example = "CIERRE", allowableValues = {"CIERRE", "APERTURA"})
		String tipo,

		@Schema(description = "Motivo declarado, de lista cerrada", example = "LICENCIA",
				allowableValues = {"AUSENCIA", "LICENCIA", "FERIADO", "BLOQUEO", "AMPLIACION",
						"OTRO"})
		String motivo,

		@Schema(description = "Primer dia cubierto", example = "2026-09-07")
		LocalDate fechaDesde,

		@Schema(description = "Primer dia YA NO cubierto, EXCLUSIVO", example = "2026-09-08")
		LocalDate fechaHasta,

		@Schema(description = "Hora local de inicio. null junto con horaHasta = DIA COMPLETO",
				type = "string", example = "14:00")
		@JsonSerialize(using = HoraDelDia.Serializador.class)
		LocalTime horaDesde,

		@Schema(description = "Hora local de fin, EXCLUSIVA. La medianoche viaja como 24:00",
				type = "string", example = "18:00")
		@JsonSerialize(using = HoraDelDia.Serializador.class)
		LocalTime horaHasta,

		@Schema(description = "Feriado que motivo la excepcion, si la carga nacio del calendario "
				+ "nacional", example = "5")
		Long feriadoId,

		@Schema(description = "Texto libre operativo. Nunca informacion clinica")
		String notes,

		@Schema(description = "ACTIVO o INACTIVO. DERIVADO, no una columna", example = "ACTIVO")
		String estado,

		@Schema(description = "Instante de la baja logica. null si la excepcion esta vigente")
		Instant deletedAt,

		@Schema(description = "Motivo declarado en la baja. null si la excepcion esta vigente")
		String deactivationReason,

		@Schema(description = "Version de la fila", example = "0")
		long version) {

	/** {@code ExcepcionView.nuevo()} no se copia: es la senal interna de 201 contra 200. */
	public static ExcepcionResponse from(ExcepcionView view) {
		return new ExcepcionResponse(
				view.id(),
				view.organizationId(),
				view.consultorioId(),
				view.membershipId(),
				view.tipo(),
				view.motivo(),
				view.fechaDesde(),
				view.fechaHasta(),
				view.horaDesde(),
				view.horaHasta(),
				view.feriadoId(),
				view.notes(),
				view.estado(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version());
	}
}
