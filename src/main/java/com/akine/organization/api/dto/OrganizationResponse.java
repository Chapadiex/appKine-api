package com.akine.organization.api.dto;

import com.akine.organization.application.OrganizationView;
import com.akine.organization.domain.OperationalStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Datos publicos de un tenant.
 *
 * <p>{@code version} no es decorativo: es el valor que hay que devolver en el
 * {@code PATCH} para el bloqueo optimista. Sin el, dos ediciones concurrentes se pisan y la
 * segunda borra el cambio de la primera sin que nadie se entere.
 *
 * <p>{@code operationalStatus} es un valor calculado —la baja logica de la organizacion gana
 * sobre el estado de la suscripcion— y no una columna. El frontend lo usa para decidir que
 * puede ofrecer; no lo recalcula por su cuenta.
 */
@Schema(description = "Organizacion (tenant) con su estado operativo efectivo")
public record OrganizationResponse(

		@Schema(description = "Identificador de la organizacion", example = "1")
		long id,

		@Schema(description = "Nombre del centro", example = "Centro Kinesico Belgrano")
		String name,

		@Schema(
				description = "Identificador legible y estable. No cambia al renombrar el centro: "
						+ "se usa en URLs y en soporte",
				example = "centro-kinesico-belgrano")
		String slug,

		@Schema(description = "Zona horaria IANA del centro", example = "America/Argentina/Buenos_Aires")
		String timezone,

		@Schema(description = "false cuando la organizacion fue dada de baja logica", example = "true")
		boolean active,

		@Schema(
				description = "Version para bloqueo optimista. Devolverla tal cual en el PATCH",
				example = "0")
		long version,

		@Schema(
				description = "Estado operativo efectivo del tenant, derivado de la suscripcion y "
						+ "de la baja logica de la organizacion",
				example = "ACTIVA")
		OperationalStatus operationalStatus) {

	public static OrganizationResponse from(OrganizationView view) {
		return new OrganizationResponse(
				view.id(),
				view.name(),
				view.slug(),
				view.timezone(),
				view.active(),
				view.version(),
				view.operationalStatus());
	}
}
