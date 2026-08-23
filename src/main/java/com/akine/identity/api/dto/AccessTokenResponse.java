package com.akine.identity.api.dto;

import com.akine.identity.application.SessionService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * El access token recien emitido. <b>Es lo unico que sale en el cuerpo.</b>
 *
 * <p>El refresh token NO esta en este record y no puede agregarse: viaja exclusivamente en la
 * cookie {@code akine_rt}, que es {@code httpOnly} y por lo tanto inalcanzable para el
 * JavaScript de la pagina (ADR-0017). Ponerlo tambien en el cuerpo anularia esa proteccion de
 * una sola linea: bastaria un XSS para llevarse una sesion de doce horas.
 *
 * <p>{@code scope} vale {@code pre_context} inmediatamente despues del login. Con ese alcance
 * ningun endpoint de negocio responde: el frontend tiene que pedir los contextos y elegir uno
 * con {@code POST /api/v1/auth/context} (DP-02).
 */
@Schema(description = "Access token de vida corta, para el header Authorization: Bearer")
public record AccessTokenResponse(

		@Schema(
				description = "JWT compacto. Se guarda EN MEMORIA, nunca en localStorage",
				example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiJ9.firma")
		String accessToken,

		@Schema(description = "Esquema de autorizacion", example = "Bearer")
		String tokenType,

		@Schema(
				description = "Segundos de vida restantes. El cliente programa el refresh con "
						+ "este valor y no leyendo el token",
				example = "600")
		long expiresIn,

		@Schema(
				description = "Alcance del token: pre_context (recien autenticado, sin contexto "
						+ "elegido) o context (acotado a organizacion y consultorio)",
				example = "pre_context",
				allowableValues = {"pre_context", "context"})
		String scope,

		@Schema(description = "Organizacion del contexto, null si el alcance es pre_context",
				example = "1")
		Long organizationId,

		@Schema(description = "Consultorio del contexto, null si el alcance es pre_context",
				example = "1")
		Long consultorioId,

		@Schema(
				description = "Rol de la membership al momento de emitir. Sirve para dibujar el "
						+ "menu; NO autoriza nada: el backend revalida en cada request",
				example = "ORG_ADMIN")
		String roleCode) {

	/** Esquema de autorizacion. Constante: no hay otro soportado. */
	private static final String TIPO = "Bearer";

	public static AccessTokenResponse from(SessionService.AccesoEmitido acceso) {
		return new AccessTokenResponse(
				acceso.valor(),
				TIPO,
				acceso.expiraEnSegundos(),
				acceso.alcance().claimValue(),
				acceso.organizationId(),
				acceso.consultorioId(),
				acceso.roleCode());
	}
}
