package com.akine.organization.spi;

/**
 * Resultado del alta compuesta (ADR-0008).
 *
 * <p>Solo ids: el llamador no recibe entities ni puede navegarlas. Si necesita los datos, los
 * pide por los contratos de lectura del modulo.
 *
 * @param organizationId tenant creado (o el ya existente, en un replay)
 * @param consultorioId  primera sede
 * @param membershipId   membership del propietario, {@code ORG_ADMIN} + {@code is_founder}
 * @param created        {@code true} solo la primera vez. Un reintento con la misma clave de
 *                       idempotencia devuelve {@code false} con los MISMOS ids: es lo que
 *                       distingue "cree un tenant" de "ya estaba creado", y el llamador lo
 *                       necesita para no repetir efectos suyos (por ejemplo, un mail de
 *                       bienvenida)
 */
public record ProvisioningResult(
		long organizationId,
		long consultorioId,
		long membershipId,
		boolean created) {
}
