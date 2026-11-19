package com.akine.organization.api.dto;

import com.akine.organization.domain.OrganizationListing;
import com.akine.organization.domain.SituacionDeCobro;
import com.akine.organization.domain.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un centro en el listado de la administracion de plataforma.
 *
 * <p>Datos minimos a proposito: lo que la plataforma necesita para reconocer el centro y elegirlo
 * —nombre, slug, estado y plan de la suscripcion, alta y cobro— y nada que sea dato del centro.
 * No viajan miembros, sedes, consumo de limites ni nada de pacientes: eso sigue detras de
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
		Instant createdAt,
		@Schema(description = "Fin de la prueba gratis, instante UTC. Solo en PRUEBA; vencido = solo lectura",
				example = "2026-12-18T03:00:00Z")
		Instant pruebaHasta,
		@Schema(description = "Tiene el adicional de IA contratado", example = "false")
		boolean ia,
		@Schema(description = "Hasta cuando esta pagada la suscripcion, instante UTC. Ausente = sin control "
				+ "de cobro (el centro opera como siempre)", example = "2026-12-19T03:00:00Z")
		Instant pagadoHasta,
		@Schema(description = "Donde esta el centro respecto de su periodo pagado: SIN_CONTROL (sin "
				+ "pagadoHasta), AL_DIA, POR_VENCER (15 dias o menos), EN_GRACIA (vencido hace menos de 3 dias, "
				+ "sigue operando), SOLO_LECTURA (de 3 a 18 dias vencido) o SIN_ACCESO (desde los 18 dias)",
				example = "AL_DIA")
		SituacionDeCobro situacionDeCobro,
		@Schema(description = "Instante en que se cierra el acceso si no paga: pagadoHasta + 18 dias. Ausente "
				+ "sin control de cobro", example = "2027-01-06T03:00:00Z")
		Instant cierreEl) {

	public static PlatformOrganizationResponse from(OrganizationListing listing) {
		return from(listing, Instant.now());
	}

	static PlatformOrganizationResponse from(OrganizationListing listing, Instant ahora) {
		return new PlatformOrganizationResponse(
				listing.id(),
				listing.name(),
				listing.slug(),
				listing.status(),
				listing.planCode(),
				listing.planName(),
				listing.createdAt(),
				listing.pruebaHasta(),
				listing.ia(),
				listing.pagadoHasta(),
				SituacionDeCobro.de(listing.pagadoHasta(), ahora),
				SituacionDeCobro.cierreEl(listing.pagadoHasta()));
	}
}
