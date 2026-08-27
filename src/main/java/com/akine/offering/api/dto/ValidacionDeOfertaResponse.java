package com.akine.offering.api.dto;

import com.akine.offering.application.ValidacionDeOfertaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Si una combinacion de oferta, profesional y espacio puede prestarse, y por que no.
 *
 * <p>Responde la mitad calculable de RF-M05-008 y RF-M04-009. La otra mitad —reservar— es de la
 * etapa de agenda, que va a consumir exactamente esto.
 *
 * <p><b>Devuelve TODOS los motivos que fallan, no el primero.</b> Si al profesional le falta
 * habilitacion y ademas el espacio esta fuera de servicio, arreglar uno solo no alcanza, y decirlo
 * de a uno obliga al usuario a dos vueltas.
 */
@Schema(description = "Si la combinacion puede prestarse, y todos los motivos por los que no")
public record ValidacionDeOfertaResponse(

		@Schema(description = "Oferta consultada", example = "34")
		long ofertaId,

		@Schema(description = "Profesional consultado, si se pregunto por uno", example = "215")
		Long membershipId,

		@Schema(description = "Espacio consultado, si se pregunto por uno", example = "3")
		Long espacioId,

		@Schema(description = "true si no falla ninguna condicion")
		boolean puedePrestarse,

		@Schema(
				description = "Minimo entre la capacidad de la oferta y la de los espacios "
						+ "habilitados en servicio",
				example = "6")
		int capacidadEfectiva,

		@Schema(description = "Condiciones que no se cumplen. Vacia si puedePrestarse es true")
		List<MotivoDeRechazo> motivos) {

	/** Una condicion que no se cumple. */
	@Schema(description = "Una condicion que impide prestar la oferta")
	public record MotivoDeRechazo(

			@Schema(
					description = "Codigo estable, para que la pantalla ramifique sin leer prosa",
					example = "profesional-no-habilitado",
					allowableValues = {
							"oferta-inactiva", "oferta-fuera-de-vigencia",
							"profesional-no-habilitado", "habilitacion-fuera-de-vigencia",
							"vinculo-no-vigente", "espacio-no-habilitado",
							"espacio-fuera-de-servicio", "capacidad-insuficiente"})
			String codigo,

			@Schema(description = "Texto para mostrar, que nombra el recurso concreto")
			String detalle) {
	}

	public static ValidacionDeOfertaResponse de(ValidacionDeOfertaView view) {
		return new ValidacionDeOfertaResponse(
				view.ofertaId(),
				view.membershipId(),
				view.espacioId(),
				view.puedePrestarse(),
				view.capacidadEfectiva(),
				view.motivos().stream()
						.map(motivo -> new MotivoDeRechazo(motivo.codigo(), motivo.detalle()))
						.toList());
	}
}
