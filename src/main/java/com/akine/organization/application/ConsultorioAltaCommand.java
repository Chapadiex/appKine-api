package com.akine.organization.application;

/**
 * Alta de una sede adicional (RF-M03-001).
 *
 * <p>La sede numero uno NO pasa por aca: la crea el onboarding compuesto en la misma
 * transaccion que la organizacion (ADR-0008), y ese camino no se reescribe. Esto es el camino
 * paralelo para las sedes 2..N.
 *
 * @param name            nombre de la sede. Unico entre las VIGENTES del tenant
 * @param timezone        zona IANA. {@code null} hereda la de la organizacion, que es el valor
 *                        que la UI propone
 * @param slotMinutes     intervalo de agenda en minutos. {@code null} usa el default
 * @param idempotencyKey  identificador del intento, generado por el cliente. Obligatorio
 * @param requestHash     SHA-256 del payload canonico, para detectar la misma clave con otro
 *                        contenido. {@code null} cuando el alta no entra por HTTP
 */
public record ConsultorioAltaCommand(
		String name,
		String timezone,
		Integer slotMinutes,
		String legalName,
		String taxId,
		String addressLine,
		String phone,
		String contactEmail,
		String idempotencyKey,
		String requestHash) {
}
