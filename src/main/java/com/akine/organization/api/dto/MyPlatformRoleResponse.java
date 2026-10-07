package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Si la cuenta autenticada administra la plataforma (AKINE-A-7, prerrequisito de RF-M06-005).
 *
 * <p>Es insumo de UX, igual que {@link EffectivePermissionsResponse}: decide si el frontend
 * muestra la consola de plataforma o el selector de contexto. Cada endpoint de plataforma sigue
 * verificando el rol por su cuenta; si esto mintiera, se veria una pantalla que despues responde
 * 403.
 *
 * <p>Un objeto y no un booleano pelado por el mismo motivo que aquel: un schema nombrado es lo
 * que hace que el cliente generado tenga un tipo, y deja lugar a crecer sin romper.
 */
@Schema(description = "Si la cuenta autenticada tiene un rol de plataforma vigente")
public record MyPlatformRoleResponse(

		@Schema(description = "true si la cuenta tiene el rol PLATFORM_ADMIN vigente en este "
				+ "instante, revalidado contra la base en este request", example = "false")
		boolean platformAdmin) {
}
