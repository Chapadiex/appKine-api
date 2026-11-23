package com.akine.organization.api.dto;

import com.akine.organization.spi.ActiveContext;
import com.akine.organization.spi.AuthorizedContext;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Optional;

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
		String consultorioName,

		@Schema(
				description = "true en el ultimo contexto que la cuenta eligio con POST "
						+ "/api/v1/auth/context, si sigue habilitado: es el que conviene "
						+ "preseleccionar al volver a entrar. A lo sumo un elemento lo trae en true; "
						+ "ninguno si la cuenta nunca eligio o si el elegido dejo de estar habilitado",
				example = "true")
		boolean lastSelected,

		@Schema(
				description = "true si el centro esta cerrado por falta de pago (suscripcion sin acceso): "
						+ "el contexto se muestra para explicar el motivo pero no se puede elegir. "
						+ "Estos elementos van al final y nunca traen lastSelected en true",
				example = "false")
		boolean sinAcceso) {

	public static AuthorizedContextResponse from(
			AuthorizedContext context, Optional<ActiveContext> ultimo) {
		boolean esElUltimo = ultimo
				.filter(activo -> activo.organizationId() == context.organizationId()
						&& activo.consultorioId() == context.consultorioId())
				.isPresent();
		return new AuthorizedContextResponse(
				context.organizationId(),
				context.organizationName(),
				context.consultorioId(),
				context.consultorioName(),
				esElUltimo,
				false);
	}

	/** Un contexto de un centro cerrado por falta de pago: visible, no elegible. */
	public static AuthorizedContextResponse sinAcceso(AuthorizedContext context) {
		return new AuthorizedContextResponse(
				context.organizationId(),
				context.organizationName(),
				context.consultorioId(),
				context.consultorioName(),
				false,
				true);
	}
}
