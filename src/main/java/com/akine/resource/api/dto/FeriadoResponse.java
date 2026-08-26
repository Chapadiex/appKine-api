package com.akine.resource.api.dto;

import com.akine.resource.application.FeriadoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Un feriado del calendario nacional, tal como viaja dentro de {@link CalendarioSedeResponse}.
 *
 * <p><b>No lleva {@code organizationId}, y no es un olvido:</b> la tabla {@code feriado} es
 * GLOBAL (ADR-0022). Un feriado nacional no es de nadie; la decision que SI es de cada sede —si
 * cierra ese dia— viaja en {@link CalendarioSedeResponse#cierraPorFeriado()}.
 *
 * <p>Por eso tampoco hay un endpoint propio de feriados en esta version del contrato: la unica
 * pregunta que una pantalla de esta etapa hace es "que feriados caen en la ventana que estoy
 * mirando, y esta sede cierra en ellos", y esa se responde entera con
 * {@code GET /consultorios/{cid}/calendario}. Un listado global sin sede no tendria consumidor.
 */
@Schema(description = "Feriado del calendario nacional que cae en la ventana consultada")
public record FeriadoResponse(

		@Schema(description = "Identificador del feriado", example = "5")
		long id,

		@Schema(description = "Pais del calendario al que pertenece", example = "AR")
		String pais,

		@Schema(description = "Fecha del feriado", example = "2026-07-09")
		LocalDate fecha,

		@Schema(description = "Nombre con el que la pantalla lo rotula",
				example = "Dia de la Independencia")
		String nombre,

		@Schema(description = "Clasificacion del feriado. Texto y no enum: la lista valida la "
				+ "fija el CHECK de la migracion y esta version del contrato no ramifica "
				+ "comportamiento por tipo, solo lo muestra", example = "INAMOVIBLE")
		String tipo) {

	public static FeriadoResponse from(FeriadoView view) {
		return new FeriadoResponse(
				view.id(), view.pais(), view.fecha(), view.nombre(), view.tipo());
	}
}
