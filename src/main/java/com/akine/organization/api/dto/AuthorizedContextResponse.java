package com.akine.organization.api.dto;

import com.akine.organization.spi.AuthorizedContext;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Un contexto de trabajo que la cuenta autenticada tiene habilitado.
 *
 * <p>El contexto es siempre el par Organizacion + Consultorio (ADR-0009): no existe "estar en
 * una organizacion" sin sede, porque toda operacion clinica ocurre en una.
 *
 * <p>Los nombres viajan junto a los ids porque este listado es exactamente lo que el usuario
 * ve para elegir donde trabajar. Resolverlos con una segunda llamada obligaria a pedir datos
 * de organizaciones antes de tener contexto en ninguna, que es el problema del huevo y la
 * gallina que este endpoint existe para romper.
 *
 * <p><b>Este endpoint no cambia el contexto.</b> Seleccionarlo renueva el token, y el token es
 * del modulo {@code identity}: la seleccion se publica en 01.02 como
 * {@code POST /api/v1/auth/context}. Publicarla aca obligaria a dos round-trips y a retirar
 * despues un endpoint ya publicado, que es un cambio incompatible de contrato (decision D-6).
 */
@Schema(description = "Contexto de trabajo habilitado para la cuenta autenticada")
public record AuthorizedContextResponse(

		@Schema(description = "Identificador de la organizacion", example = "1")
		long organizationId,

		@Schema(description = "Nombre de la organizacion", example = "Centro Kinesico Belgrano")
		String organizationName,

		@Schema(description = "Identificador del consultorio", example = "1")
		long consultorioId,

		@Schema(description = "Nombre del consultorio", example = "Sede Central")
		String consultorioName) {

	public static AuthorizedContextResponse from(AuthorizedContext context) {
		return new AuthorizedContextResponse(
				context.organizationId(),
				context.organizationName(),
				context.consultorioId(),
				context.consultorioName());
	}
}
