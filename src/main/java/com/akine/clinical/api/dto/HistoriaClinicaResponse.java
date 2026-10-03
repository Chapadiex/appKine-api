package com.akine.clinical.api.dto;

import com.akine.clinical.application.HistoriaClinicaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * La Historia Clinica de una persona tal como sale por la API (M09, RF-M09-001).
 *
 * <p>Trae los antecedentes vigentes y un asomo del timeline —los hechos mas recientes, sin
 * contenido clinico—; la linea de tiempo completa y paginada es la del recurso
 * {@code /timeline}. Los datos del paciente viajan pero no se guardan en la historia
 * (RN-M09-003).
 *
 * <p>{@code version} es la que el cliente devuelve como {@code expectedVersion} al actualizar el
 * resumen.
 */
@Schema(description = "Historia clinica de una persona, con su resumen y antecedentes vigentes")
public record HistoriaClinicaResponse(

		@Schema(description = "Identificador de la historia", example = "88")
		long id,

		@Schema(description = "Persona titular de la historia", example = "12")
		long personaId,

		@Schema(description = "Nombre completo del paciente, leido de person en cada consulta")
		String personaNombreCompleto,

		@Schema(description = "Documento del paciente, o null", example = "DNI 30111222")
		String personaDocumento,

		@Schema(description = "Instante UTC de apertura de la historia")
		Instant abiertaEn,

		@Schema(description = "Cuenta que la abrio", example = "8")
		long abiertaPor,

		@Schema(description = "Resumen clinico minimo, o null si nunca se escribio")
		String resumen,

		@Schema(description = "Instante UTC de la ultima actualizacion del resumen, o null")
		Instant resumenActualizadoEn,

		@Schema(description = "Cuenta que actualizo el resumen por ultima vez, o null")
		Long resumenActualizadoPor,

		@Schema(description = "Antecedentes vigentes de la historia")
		List<AntecedenteResponse> antecedentes,

		@Schema(description = "Asomo del timeline: los hechos clinicos mas recientes, sin "
				+ "contenido clinico")
		List<EventoClinicoResponse> eventos,

		@Schema(description = "Si el acceso se autorizo por relacion asistencial (true) o por "
				+ "motivo declarado (false)", example = "false")
		boolean conRelacionAsistencial,

		@Schema(description = "Version de la historia. Se devuelve como expectedVersion al "
				+ "actualizar el resumen", example = "0")
		long version) {

	public static HistoriaClinicaResponse from(HistoriaClinicaView view) {
		return new HistoriaClinicaResponse(
				view.id(),
				view.personaId(),
				view.personaNombreCompleto(),
				view.personaDocumento(),
				view.abiertaEn(),
				view.abiertaPor(),
				view.resumen(),
				view.resumenActualizadoEn(),
				view.resumenActualizadoPor(),
				view.antecedentes().stream().map(AntecedenteResponse::from).toList(),
				view.eventos().stream().map(EventoClinicoResponse::from).toList(),
				view.conRelacionAsistencial(),
				view.version());
	}
}
