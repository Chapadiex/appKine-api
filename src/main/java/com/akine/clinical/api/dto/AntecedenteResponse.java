package com.akine.clinical.api.dto;

import com.akine.clinical.application.AntecedenteView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un antecedente clinico tal como sale por la API (RF-M09-002).
 *
 * <p>Trae tambien los campos de la baja: un antecedente dado de baja sigue siendo consultable
 * (regla maestra 10) y una respuesta que ocultara el motivo dejaria al historico sin poder
 * explicarse.
 */
@Schema(description = "Antecedente clinico de una historia (RF-M09-002)")
public record AntecedenteResponse(

		@Schema(description = "Identificador del antecedente", example = "41")
		long id,

		@Schema(description = "Clase de antecedente", example = "ALERGIA",
				allowableValues = {"MEDICO", "QUIRURGICO", "ALERGIA", "MEDICACION", "FAMILIAR",
						"HABITO", "OTRO"})
		String tipo,

		@Schema(description = "Texto clinico del antecedente. No se edita: se da de baja y se "
				+ "registra el nuevo")
		String descripcion,

		@Schema(description = "Instante UTC en que se registro")
		Instant registradoEn,

		@Schema(description = "Cuenta que lo registro", example = "8")
		long registradoPor,

		@Schema(description = "Si el antecedente sigue vigente", example = "true")
		boolean vigente,

		@Schema(description = "Instante UTC de la baja logica, o null")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja, o null")
		String deactivationReason,

		@Schema(description = "Version del registro", example = "0")
		long version,

		@Schema(description = "Por que camino entro (DP-47): PROFESIONAL desde la historia, o "
				+ "DECLARADO por el paciente, que espera revision", example = "PROFESIONAL",
				allowableValues = {"PROFESIONAL", "DECLARADO"})
		String origen,

		@Schema(description = "Nombre de quien lo registro, o null si la cuenta ya no resuelve")
		String cargadoPorNombre,

		@Schema(description = "Nombre del profesional que reviso el dato declarado, o null")
		String revisadoPorNombre,

		@Schema(description = "Instante UTC de la revision de un dato declarado, o null si no se reviso")
		Instant revisadoEn,

		@Schema(description = "Zona de una cirugia (ficha de ingreso), o null", example = "Rodilla derecha")
		String zona,

		@Schema(description = "Año de una cirugia (ficha de ingreso), o null", example = "2019")
		Short anio,

		@Schema(description = "Donde se hizo la cirugia (ficha de ingreso), o null", example = "Hospital Privado")
		String lugar,

		@Schema(description = "Enfermedad de la lista de la ficha de ingreso, o null", example = "DIABETES",
				allowableValues = {"DIABETES", "HIPERTENSION", "CARDIOPATIA", "MARCAPASOS", "OSTEOPOROSIS",
						"ANTICOAGULADO", "EMBARAZO", "CANCER", "EPILEPSIA", "RESPIRATORIA"})
		String codigo) {

	public static AntecedenteResponse from(AntecedenteView view) {
		return new AntecedenteResponse(
				view.id(),
				view.tipo(),
				view.descripcion(),
				view.registradoEn(),
				view.registradoPor(),
				view.vigente(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version(),
				view.origen(),
				view.cargadoPorNombre(),
				view.revisadoPorNombre(),
				view.revisadoEn(),
				view.zona(),
				view.anio(),
				view.lugar(),
				view.codigo());
	}
}
