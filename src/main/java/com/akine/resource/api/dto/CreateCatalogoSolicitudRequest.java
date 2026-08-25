package com.akine.resource.api.dto;

import com.akine.resource.domain.CatalogoTipo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Pedido para que la plataforma incorpore un concepto GLOBAL (RF-M06-005).
 *
 * <p><b>Pedir no bloquea.</b> Mientras la plataforma decide, el centro puede crear el concepto
 * como contextual suyo y seguir trabajando; esta solicitud existe para que el catalogo comun
 * crezca con criterio, no para frenar a nadie.
 *
 * <p>Un segundo pedido identico mientras el primero sigue PENDIENTE responde 409
 * catalogo-solicitud-duplicada: es la idempotencia frente a un reintento por timeout. Volver a
 * pedir algo ya RECHAZADO si esta permitido.
 */
@Schema(description = "Datos del pedido de alta de un concepto global")
public record CreateCatalogoSolicitudRequest(

		@Schema(description = "Que concepto global se pide",
				example = "ESPECIALIDAD",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El tipo de concepto solicitado es obligatorio")
		CatalogoTipo tipo,

		@Schema(description = "Nombre propuesto", example = "Terapia ocupacional",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre propuesto es obligatorio")
		@Size(max = 160, message = "El nombre propuesto no puede superar los 160 caracteres")
		String nombrePropuesto,

		@Schema(description = "Codigo sugerido. Opcional: el centro propone, la plataforma "
				+ "dispone", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 48, message = "El codigo propuesto no puede superar los 48 caracteres")
		String codigoPropuesto,

		@Schema(
				description = "Por que hace falta. Obligatoria: sin ella la plataforma no puede "
						+ "decidir y la solicitud es ruido",
				example = "Tres profesionales del centro la ejercen y hoy no hay forma de "
						+ "registrarla",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La justificacion es obligatoria")
		@Size(max = 500, message = "La justificacion no puede superar los 500 caracteres")
		String justificacion) {
}
