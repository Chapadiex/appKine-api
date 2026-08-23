package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Acuse uniforme de los endpoints que no pueden revelar si la cuenta existe (ADR-0018).
 *
 * <p>Lo devuelven el registro, el pedido de restablecimiento y el reenvio de activacion, con
 * <b>exactamente el mismo contenido</b> exista o no la direccion. No lleva identificadores, ni
 * {@code Location}, ni un booleano "se envio": cualquiera de esas tres cosas convertiria el
 * endpoint en un verificador de direcciones de correo.
 *
 * <p>El texto esta redactado en condicional a proposito ("si el email corresponde a una
 * cuenta"): es la unica forma honesta de decir la verdad sin decir cual de los dos casos ocurrio.
 */
@Schema(description = "Acuse uniforme: no revela si la cuenta existe")
public record AcceptedResponse(

		@Schema(
				description = "Mensaje uniforme para mostrarle a la persona",
				example = "Si el email corresponde a una cuenta, vas a recibir un correo con "
						+ "las instrucciones.")
		String message) {

	public static AcceptedResponse de(String message) {
		return new AcceptedResponse(message);
	}
}
