package com.akine.organization.spi;

import java.time.Instant;

/**
 * Estado de la membership de una cuenta en una organizacion, tal como lo ven otros modulos.
 *
 * <p>{@code roleCode} viaja como {@code String} y no como el enum de {@code domain} a
 * proposito: un consumidor que use el enum estaria importando
 * {@code com.akine.organization.domain}, que es privado del modulo y que ArchUnit rechaza. El
 * catalogo de roles vigente es la matriz de permisos aprobada, no este record.
 *
 * <p>Se devuelve la vigencia cruda ({@code validFrom} / {@code validUntil} / {@code active})
 * ademas de {@link #validAt(Instant)} para que 01.03 pueda mostrarla, pero la decision de
 * "esta vigente" se toma con el metodo: si cada consumidor la recalcula, la regla se duplica y
 * empieza a divergir.
 *
 * @param membershipId id de la fila
 * @param roleCode     rol de seguridad de la matriz aprobada. En 01.01 solo se escribe
 *                     {@code ORG_ADMIN}
 * @param founder      atributo, NO un rol (T-5): distingue al propietario que fundo la
 *                     organizacion sin inventar un rol {@code OWNER}
 * @param validFrom    inicio de vigencia
 * @param validUntil   fin de vigencia, o {@code null} si no tiene
 * @param active       baja logica. Distinta de la vigencia: las dos tienen que cumplirse
 */
public record MembershipSnapshot(
		long membershipId,
		String roleCode,
		boolean founder,
		Instant validFrom,
		Instant validUntil,
		boolean active) {

	/** Indica si la membership habilita algo en ese instante. Vigencia Y baja logica. */
	public boolean validAt(Instant momento) {
		if (!active || momento.isBefore(validFrom)) {
			return false;
		}
		return validUntil == null || momento.isBefore(validUntil);
	}
}
