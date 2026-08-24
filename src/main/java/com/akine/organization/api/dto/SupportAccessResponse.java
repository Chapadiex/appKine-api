package com.akine.organization.api.dto;

import com.akine.organization.application.SupportAccessView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Acceso de soporte de la administracion de plataforma sobre un tenant. */
@Schema(description = "Acceso de soporte concedido sobre una organizacion")
public record SupportAccessResponse(

		@Schema(description = "Identificador del acceso", example = "5")
		long id,

		@Schema(description = "Organizacion sobre la que rige", example = "7")
		long organizationId,

		@Schema(description = "Cuenta de plataforma que entra al tenant", example = "1")
		long accountId,

		@Schema(description = "Motivo declarado. Sin motivo no hay soporte")
		String reason,

		@Schema(description = "Cuenta que lo concedio", example = "1")
		long grantedByAccountId,

		@Schema(description = "Momento desde el que rige")
		Instant validFrom,

		@Schema(description = "Vencimiento. Como maximo cuatro horas despues del otorgamiento")
		Instant validUntil,

		@Schema(description = "Momento en que se cerro antes de vencer, si se cerro")
		Instant revokedAt,

		@Schema(description = "Baja logica: false cuando el acceso ya fue cerrado", example = "true")
		boolean active) {

	public static SupportAccessResponse from(SupportAccessView view) {
		return new SupportAccessResponse(
				view.id(),
				view.organizationId(),
				view.accountId(),
				view.reason(),
				view.grantedByAccountId(),
				view.validFrom(),
				view.validUntil(),
				view.revokedAt(),
				view.active());
	}
}
