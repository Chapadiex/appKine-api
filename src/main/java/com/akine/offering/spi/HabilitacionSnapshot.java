package com.akine.offering.spi;

import java.time.Instant;

/**
 * Un recurso habilitado para una oferta, con la vigencia de esa habilitacion.
 *
 * <p>Sirve para las dos tablas de 02.07 —profesionales y espacios— porque la unica diferencia
 * entre ellas es que apunta a. Dos records identicos salvo el nombre del campo serian dos lugares
 * donde arreglar el mismo bug de vigencia.
 *
 * @param recursoId {@code membership_id} o {@code espacio_id} segun de cual de las dos venga
 * @param validUntil {@code null} = sin vencimiento
 */
public record HabilitacionSnapshot(
		long id,
		long recursoId,
		Instant validFrom,
		Instant validUntil,
		boolean active) {

	/**
	 * La habilitacion cubre ese instante.
	 *
	 * <p>{@code validFrom} inclusivo, {@code validUntil} exclusivo: es el mismo criterio de
	 * {@code ConsultorioMembershipSnapshot}, y mezclar criterios de borde entre modulos produce
	 * diferencias de un dia que no se ven hasta que alguien reserva el ultimo turno de una
	 * vigencia.
	 */
	public boolean vigenteEn(Instant at) {
		if (!active) {
			return false;
		}
		if (validFrom.isAfter(at)) {
			return false;
		}
		return validUntil == null || validUntil.isAfter(at);
	}
}
