package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Lo unico que devuelve el alta directa: el id del vinculo creado.
 *
 * <h2>Por que no devuelve la membership completa</h2>
 *
 * <p>Porque no puede, y forzarlo costaria mas de lo que da. El contrato de
 * {@code organization.spi.MembershipProvisioning.createDirect} devuelve un {@code long}, y la
 * representacion completa de un vinculo es {@code organization.api.dto.MembershipResponse}:
 * importarla desde aca cruzaria la capa HTTP de dos modulos, que es exactamente la flecha que
 * ADR-0001 cierra —el mismo motivo por el que {@code IdentityApiActor} duplica veinte lineas de
 * {@code ApiActor} en vez de compartirlas—.
 *
 * <p>El cliente que necesite el vinculo entero lo pide donde vive:
 * {@code GET /api/v1/organizations/{orgId}/memberships/{membershipId}}. La cabecera
 * {@code Location} de la respuesta ya apunta a esa ruta, asi que no hay que armarla a mano.
 *
 * @param membershipId identificador del vinculo recien creado
 */
@Schema(description = "Identificador del vinculo creado por el alta directa")
public record CreatedMembershipResponse(

		@Schema(description = "Identificador del vinculo creado", example = "42")
		long membershipId) {
}
