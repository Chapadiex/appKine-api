package com.akine.organization.api.dto;

import com.akine.organization.domain.OrganizationListing;
import com.akine.organization.domain.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un centro en el listado de la administracion de plataforma.
 *
 * <p>Datos minimos a proposito: lo que la plataforma necesita para reconocer el centro y elegirlo
 * —nombre, slug, estado y plan de la suscripcion, alta— y nada que sea dato del centro. No viajan
 * miembros, sedes, consumo de limites ni nada de pacientes: eso sigue detras de
 * {@code tenant:read} con acceso de soporte.
 */
@Schema(description = "Centro en el listado de la administracion de plataforma: identificacion, "
		+ "estado y plan de la suscripcion, sin datos del centro")
public record PlatformOrganizationResponse(

		@Schema(description = "Identificador de la organizacion", example = "1")
		long id,

		@Schema(description = "Nombre del centro", example = "Centro Kinesio Norte")
		String name,

		@Schema(description = "Identificador legible y unico del centro", example = "kinesio-norte")
		String slug,

		@Schema(description = "Estado de la suscripcion", example = "ACTIVA")
		SubscriptionStatus status,

		@Schema(description = "Codigo del plan contratado", example = "BASICO")
		String planCode,

		@Schema(description = "Nombre comercial del plan contratado", example = "Basico")
		String planName,

		@Schema(description = "Instante de alta de la organizacion", example = "2026-01-15T13:45:00Z")
		Instant createdAt) {

	public static PlatformOrganizationResponse from(OrganizationListing listing) {
		return new PlatformOrganizationResponse(
				listing.id(),
				listing.name(),
				listing.slug(),
				listing.status(),
				listing.planCode(),
				listing.planName(),
				listing.createdAt());
	}
}
