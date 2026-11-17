package com.akine.encounter.api.dto;

import com.akine.encounter.application.SugerenciasView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Una sugerencia de revision del tratamiento, con su evidencia y su respuesta (M31 fase 2). */
@Schema(description = "Sugerencia de revision del tratamiento, para revisar: no es una indicacion")
public record SugerenciaDeEvolucionResponse(

		@Schema(description = "Id de la sugerencia", example = "301")
		long id,

		@Schema(description = "Pedido que la genero")
		UUID generacionId,

		@Schema(description = "Hallazgo de la fase 1 que la origina", example = "DOLOR_SIN_MEJORA")
		String hallazgo,

		@Schema(description = "La sugerencia, hasta 280 caracteres")
		String texto,

		@Schema(description = "Por que, apoyado en los datos y en los pasajes citados; hasta 600 caracteres")
		String fundamento,

		@Schema(description = "Pasajes de la biblioteca que la respaldan; al menos uno")
		List<SugerenciaDeEvolucionCitaResponse> citas,

		@Schema(description = "Sesiones del caso que originan el hallazgo")
		List<Long> sesiones,

		@Schema(description = "Modelo que la redacto", example = "qwen3:4b-instruct")
		String modelo,

		@Schema(description = "PENDIENTE, ACEPTADA o DESCARTADA", allowableValues = {"PENDIENTE", "ACEPTADA",
				"DESCARTADA"})
		String respuesta,

		@Schema(description = "Cuenta que pidio las sugerencias")
		long solicitadaPorCuentaId,

		@Schema(description = "Cuando se pidio")
		Instant solicitadaEn,

		@Schema(description = "Cuenta que la respondio; puede no ser quien la pidio")
		Long respondidaPorCuentaId,

		@Schema(description = "Cuando se respondio")
		Instant respondidaEn,

		@Schema(description = "Motivo del descarte")
		String motivoDescarte,

		@Schema(description = "Version para responder con control optimista", example = "0")
		long version) {

	public static SugerenciaDeEvolucionResponse de(SugerenciasView.Sugerencia s) {
		return new SugerenciaDeEvolucionResponse(s.id(), s.generacionId(), s.hallazgo(), s.texto(), s.fundamento(),
				s.citas().stream().map(SugerenciaDeEvolucionCitaResponse::de).toList(), s.sesiones(), s.modelo(),
				s.respuesta(), s.solicitadaPorCuentaId(), s.solicitadaEn(), s.respondidaPorCuentaId(),
				s.respondidaEn(), s.motivoDescarte(), s.version());
	}
}
