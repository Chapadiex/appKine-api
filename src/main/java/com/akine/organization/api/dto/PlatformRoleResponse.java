package com.akine.organization.api.dto;

import com.akine.organization.application.PlatformRoleView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Rol de administracion de plataforma otorgado a una cuenta. */
@Schema(description = "Rol de plataforma vigente sobre una cuenta")
public record PlatformRoleResponse(

		@Schema(description = "Identificador del otorgamiento", example = "2")
		long id,

		@Schema(description = "Cuenta que administra la plataforma", example = "1")
		long accountId,

		@Schema(description = "Codigo del rol", example = "PLATFORM_ADMIN")
		String roleCode,

		@Schema(description = "Cuenta que lo otorgo, o null si vino del seed de migracion",
				example = "1")
		Long grantedByAccountId,

		@Schema(description = "Motivo declarado del otorgamiento")
		String reason,

		@Schema(description = "Momento desde el que rige")
		Instant validFrom,

		@Schema(description = "Vencimiento, o null si no vence")
		Instant validUntil,

		@Schema(description = "Baja logica: false cuando el rol ya fue revocado", example = "true")
		boolean active) {

	public static PlatformRoleResponse from(PlatformRoleView view) {
		return new PlatformRoleResponse(
				view.id(),
				view.accountId(),
				view.roleCode(),
				view.grantedByAccountId(),
				view.reason(),
				view.validFrom(),
				view.validUntil(),
				view.active());
	}
}
