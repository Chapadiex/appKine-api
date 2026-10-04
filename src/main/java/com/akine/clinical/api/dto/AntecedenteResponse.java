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
		long version) {

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
				view.version());
	}
}
