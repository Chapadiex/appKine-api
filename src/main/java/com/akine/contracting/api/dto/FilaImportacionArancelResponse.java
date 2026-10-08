package com.akine.contracting.api.dto;

import com.akine.contracting.application.ImportacionAranceles.MotivoRechazo;
import com.akine.contracting.application.ImportacionAranceles.ResultadoFila;
import com.akine.platform.spi.problem.ProblemType;
import io.swagger.v3.oas.annotations.media.Schema;

/** El desenlace de una fila de una importacion de aranceles (B-7, RF-M16-007). */
@Schema(description = "Desenlace de una fila de la importacion")
public record FilaImportacionArancelResponse(

		@Schema(description = "Posicion de la fila en el lote, desde 1", example = "3")
		int fila,

		EstadoFilaImportacion estado,

		@Schema(description = "La practica resuelta. Null si no resolvio", example = "412")
		Long practicaId,

		@Schema(description = "La oferta de la fila, tal como vino", example = "77")
		Long ofertaId,

		@Schema(description = "Solo en RECHAZADA: el problem type que daria el alta unitaria de esa "
				+ "fila. validation-error (datos invalidos), not-found (practica u oferta no "
				+ "accesibles), oferta-sin-obra-social, practica-no-habilitada-en-oferta o "
				+ "arancel-solapado",
				example = "https://akine.app/problems/arancel-solapado")
		String problemType,

		@Schema(description = "Solo en RECHAZADA: por que, en una frase para el administrador")
		String detalle,

		@Schema(description = "Solo con arancel-solapado contra un arancel vigente: cual",
				example = "901")
		Long arancelExistenteId,

		@Schema(description = "Solo con arancel-solapado contra otra fila del mismo lote: su "
				+ "posicion", example = "1")
		Integer filaEnConflicto,

		@Schema(description = "Solo en una confirmacion aplicada: el arancel creado",
				example = "1204")
		Long arancelId) {

	public static FilaImportacionArancelResponse de(ResultadoFila resultado) {
		return new FilaImportacionArancelResponse(
				resultado.fila(),
				resultado.rechazada() ? EstadoFilaImportacion.RECHAZADA : EstadoFilaImportacion.ALTA,
				resultado.practicaId(),
				resultado.ofertaId(),
				resultado.motivo() == null ? null : problemType(resultado.motivo()).uri().toString(),
				resultado.detalle(),
				resultado.arancelExistenteId(),
				resultado.filaEnConflicto(),
				resultado.arancelId());
	}

	private static ProblemType problemType(MotivoRechazo motivo) {
		return switch (motivo) {
			case DATOS_INVALIDOS -> ProblemType.VALIDATION_ERROR;
			case PRACTICA_NO_ACCESIBLE, OFERTA_NO_ACCESIBLE -> ProblemType.NOT_FOUND;
			case OFERTA_SIN_OBRA_SOCIAL -> ProblemType.OFERTA_SIN_OBRA_SOCIAL;
			case PRACTICA_NO_HABILITADA_EN_OFERTA -> ProblemType.PRACTICA_NO_HABILITADA_EN_OFERTA;
			case ARANCEL_SOLAPADO -> ProblemType.ARANCEL_SOLAPADO;
		};
	}
}
