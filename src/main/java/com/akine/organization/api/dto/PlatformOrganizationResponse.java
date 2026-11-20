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

		@Schema(description = "Codigo del plan contratado", example = "BASE")
		String planCode,

		@Schema(description = "Nombre comercial del plan contratado", example = "Plan Básico")
		String planName,

		@Schema(description = "Instante de alta de la organizacion", example = "2026-01-15T13:45:00Z")
		Instant createdAt,
		@Schema(description = "Fin de la prueba gratis, instante UTC. Solo en PRUEBA; vencido = solo lectura",
				example = "2026-12-18T03:00:00Z")
		Instant pruebaHasta,
		@Schema(description = "Tiene el adicional de IA contratado", example = "false")
		boolean ia) {

	public static PlatformOrganizationResponse from(OrganizationListing listing) {
		return new PlatformOrganizationResponse(
				listing.id(),
				listing.name(),
				listing.slug(),
				listing.status(),
				listing.planCode(),
				listing.planName(),
				listing.createdAt(),
				listing.pruebaHasta(),
				listing.ia());
	}
}
