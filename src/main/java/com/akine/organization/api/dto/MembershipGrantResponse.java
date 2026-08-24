package com.akine.organization.api.dto;

import com.akine.organization.application.MembershipGrantView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Permiso adicional otorgado sobre un vinculo, por encima de lo que da su rol. */
@Schema(description = "Permiso adicional otorgado a un colaborador")
public record MembershipGrantResponse(

		@Schema(description = "Identificador del permiso adicional", example = "12")
		long id,

		@Schema(description = "Vinculo sobre el que se otorgo", example = "42")
		long membershipId,

		@Schema(description = "Codigo del permiso, del catalogo de la matriz",
				example = "auditoria:read-clinica")
		String permissionCode,

		@Schema(description = "Cuenta que lo otorgo", example = "4")
		long grantedByAccountId,

		@Schema(description = "Motivo declarado del otorgamiento")
		String reason,

		@Schema(description = "Momento desde el que rige")
		Instant validFrom,

		@Schema(description = "Momento hasta el que rige, o null si no vence")
		Instant validUntil,

		@Schema(description = "Baja logica: false cuando el permiso ya fue dado de baja",
				example = "true")
		boolean active) {

	public static MembershipGrantResponse from(MembershipGrantView view) {
		return new MembershipGrantResponse(
				view.id(),
				view.membershipId(),
				view.permissionCode(),
				view.grantedByAccountId(),
				view.reason(),
				view.validFrom(),
				view.validUntil(),
				view.active());
	}
}
