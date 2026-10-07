package com.akine.organization.application;

import com.akine.organization.spi.AltaDeSedeExtension.Complemento;

/**
 * Alta de una sede adicional (RF-M03-001), y con ella el primer box y el horario general cuando
 * vienen (RF-M03-002, A-8).
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
 * @param complemento     primer box y horario general. {@code null} equivale a
 *                        {@link Complemento#ninguno()}
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
		String requestHash,
		Complemento complemento) {

	public ConsultorioAltaCommand {
		complemento = complemento == null ? Complemento.ninguno() : complemento;
	}

	/** Alta sin primer box ni horario: la forma anterior a A-8. */
	public ConsultorioAltaCommand(
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
		this(name, timezone, slotMinutes, legalName, taxId, addressLine, phone, contactEmail,
				idempotencyKey, requestHash, null);
	}
}
