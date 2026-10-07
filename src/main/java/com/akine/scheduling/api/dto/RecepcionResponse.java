package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.RecepcionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * La recepcion de un turno (M13, AKINE E-4).
 *
 * <p>PHI minima: nada clinico. La observacion es administrativa.
 */
@Schema(
		name = "Recepcion",
		description = "Recepcion de un turno: llegada, validacion administrativa, espera y llamado "
				+ "(DP-16). Ningun estado prueba que la atencion ocurrio: eso lo dice la Sesion.")
public record RecepcionResponse(

		@Schema(example = "77")
		long id,

		@Schema(example = "301")
		long turnoId,

		// CUIDADO: lista escrita a mano. OpenApiContractIT verifica que no se quede corta respecto
		// de EstadoRecepcion.
		@Schema(
				description = "`LLEGO` (sin validar), `VALIDADA` (se sabe como se atiende), "
						+ "`OBSERVADA` (la validacion encontro algo: advierte, no bloquea), "
						+ "`EN_ESPERA`, `LLAMADA`, `ANULADA` (check-in por error) o `CERRADA` (el "
						+ "turno se cancelo con la persona presente; la llegada consta).",
				allowableValues = {"LLEGO", "VALIDADA", "OBSERVADA", "EN_ESPERA", "LLAMADA",
						"ANULADA", "CERRADA"},
				example = "EN_ESPERA")
		String estado,

		@Schema(description = "Hora REAL de llegada, puesta por el servidor.", example = "2026-09-15T11:52:00Z")
		Instant llegadaEn,

		@Schema(description = "Cuenta que registro la llegada.", example = "12")
		long llegadaPorCuentaId,

		@Schema(
				description = "Como se resolvio: con cobertura o Particular. Ausente mientras no se "
						+ "resolvio (incluida una observada que paso a espera sin resolverse).",
				allowableValues = {"COBERTURA", "PARTICULAR"},
				example = "COBERTURA")
		String modalidad,

		@Schema(description = "Practica con la que se valido (principal de la oferta).", example = "33")
		Long practicaId,

		@Schema(description = "Cobertura del paciente con la que se valido.", example = "412")
		Long coberturaId,

		@Schema(description = "Convenio vigente que fijo los requisitos.", example = "9")
		Long convenioId,

		@Schema(
				description = "Lo que observo la validacion, con un codigo adelante "
						+ "(`OFERTA_SIN_PRACTICA`, `SIN_COBERTURA_APLICABLE`, `COBERTURA_NO_APLICABLE`, "
						+ "`DOCUMENTACION_INCOMPLETA`).",
				example = "DOCUMENTACION_INCOMPLETA: ORDEN: El convenio exige orden medica")
		String observacion,

		@Schema(description = "Por que se decidio atender como Particular.", example = "Sin orden medica")
		String motivoParticular,

		@Schema(example = "2026-09-15T11:53:00Z")
		Instant validadaEn,

		@Schema(example = "2026-09-15T11:54:00Z")
		Instant enEsperaDesde,

		@Schema(example = "2026-09-15T12:01:00Z")
		Instant llamadaEn,

		@Schema(description = "Cuando se anulo o se cerro.", example = "2026-09-15T12:30:00Z")
		Instant cerradaEn,

		@Schema(description = "Motivo de la anulacion o de la cancelacion del turno.", example = "El profesional se descompuso")
		String motivoCierre,

		@Schema(description = "Version para el control optimista de las transiciones", example = "2")
		long version) {

	public static RecepcionResponse de(RecepcionView vista) {
		return new RecepcionResponse(
				vista.id(), vista.turnoId(), vista.estado(), vista.llegadaEn(),
				vista.llegadaPorCuentaId(), vista.modalidad(), vista.practicaId(),
				vista.coberturaId(), vista.convenioId(), vista.observacion(),
				vista.motivoParticular(), vista.validadaEn(), vista.enEsperaDesde(),
				vista.llamadaEn(), vista.cerradaEn(), vista.motivoCierre(), vista.version());
	}
}
