package com.akine.person.api.dto;

import com.akine.person.application.AutorizacionElegibleView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Una autorizacion del paciente con el veredicto de si sirve ese dia, y por que no (RF-M17-007).
 *
 * <p>Es el "selector explicable": la lista trae <b>todas</b> las activas y no solo las que
 * habilitan, porque decirle al mostrador "no hay ninguna" sin decirle que una vencio anteayer y
 * otra se agoto lo deja sin nada que hacer.
 *
 * <p><b>No filtra por practica.</b> Una sesion declara su oferta y una autorizacion es por
 * practica; no existe tabla puente entre las dos y V24 la dejo afuera a proposito. Quien elige
 * entre varias candidatas es quien conoce el caso, y por eso cada fila trae su
 * {@code practicaId} a la vista. Unificar las dos granularidades es 06.04.
 */
@Schema(description = "Autorizacion candidata con su veredicto de elegibilidad")
public record AutorizacionElegibleResponse(

		@Schema(description = "Identificador de la autorizacion", example = "77")
		long id,

		@Schema(description = "Paciente", example = "1204")
		long personaId,

		@Schema(description = "Cobertura contra la que el financiador la otorgo", example = "412")
		long coberturaId,

		@Schema(description = "Practica autorizada", example = "88")
		long practicaId,

		@Schema(description = "Numero que devolvio el financiador", example = "AUT-2026-99120")
		String numero,

		@Schema(description = "Estado decidido por una persona", example = "APROBADA",
				allowableValues = {"PENDIENTE", "APROBADA", "OBSERVADA", "RECHAZADA"})
		String estadoAutorizacion,

		@Schema(description = "Lo que el financiador otorgo. Null = sin tope", example = "10")
		Integer cantidadAutorizada,

		@Schema(description = "Consumidas. AKINE-04.05: ya no vale siempre cero", example = "4")
		int cantidadConsumida,

		@Schema(description = "Restantes. Null si no hay tope declarado", example = "6")
		Integer saldo,

		@Schema(description = "Primer dia en que habilita", example = "2026-09-01")
		LocalDate vigenciaDesde,

		@Schema(description = "ULTIMO dia, INCLUSIVE. Null = sin vencimiento",
				example = "2026-11-30")
		LocalDate vigenciaHasta,

		@Schema(description = "Sirve ese dia: activa, APROBADA, vigente y con saldo",
				example = "true")
		boolean habilita,

		@Schema(
				description = "Por que NO sirve. Null exactamente cuando habilita. Vocabulario "
						+ "cerrado para que la pantalla pueda agrupar y traducir. Una vencida Y "
						+ "agotada se informa VENCIDA, porque renovarla es lo que corresponde y "
						+ "ampliarle la cantidad no sirve de nada",
				example = "VENCIDA",
				allowableValues = {"VENCIDA", "AGOTADA", "AUN_NO_VIGENTE", "NO_APROBADA"})
		String motivoNoElegible,

		@Schema(description = "Dias que faltan para vencer. Null si no vence", example = "72")
		Long diasParaVencer,

		@Schema(description = "Convenio CONGELADO al registrarla. Null si no habia",
				example = "12")
		Long convenioId,

		@Schema(description = "Nombre del convenio congelado")
		String convenioNombre) {

	public static AutorizacionElegibleResponse de(AutorizacionElegibleView view) {
		return new AutorizacionElegibleResponse(
				view.id(),
				view.personaId(),
				view.coberturaId(),
				view.practicaId(),
				view.numero(),
				view.estadoAutorizacion(),
				view.cantidadAutorizada(),
				view.cantidadConsumida(),
				view.saldo(),
				view.vigenciaDesde(),
				view.vigenciaHasta(),
				view.habilita(),
				view.motivoNoElegible(),
				view.diasParaVencer(),
				view.convenioId(),
				view.convenioNombre());
	}
}
