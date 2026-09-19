package com.akine.clinical.api.dto;

import com.akine.clinical.application.EntradaClinicaVersionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Una version del contenido de una entrada clinica (RF-M09-006).
 *
 * <p>Las versiones <b>no se dan de baja, ni siquiera logicamente</b>: una version es un hecho
 * pasado y desactivarla seria reescribir historia clinica, que ADR-0011 prohibe. Por eso este
 * record no tiene ningun campo de ciclo de vida — no es una omision, es que no existe el estado.
 */
@Schema(description = "Una version del contenido de una entrada clinica. Inmutable")
public record EntradaClinicaVersionResponse(

		@Schema(description = "Numero de version. La 1 es el original", example = "2")
		int numeroVersion,

		@Schema(description = "Texto clinico tal como quedo en esta version")
		String cuerpo,

		@Schema(description = "Por que se escribio esta version. null en la 1",
				example = "Se corrigio la lateralidad")
		String motivoEnmienda,

		@Schema(description = "Instante UTC en que se escribio")
		Instant registradaEn,

		@Schema(description = "Cuenta que la escribio", example = "8")
		long registradaPor) {

	public static EntradaClinicaVersionResponse from(EntradaClinicaVersionView view) {
		return new EntradaClinicaVersionResponse(
				view.numeroVersion(),
				view.cuerpo(),
				view.motivoEnmienda(),
				view.registradaEn(),
				view.registradaPor());
	}
}
