package com.akine.resource.application;

import com.akine.resource.domain.EspacioTipo;

import java.time.Instant;

/**
 * Edicion parcial de un espacio (RF-M04-002, RF-M04-007).
 *
 * <p>Semantica de PATCH: cada campo {@code null} deja el valor como estaba.
 *
 * @param clearValidUntil hace explicito el caso que un {@code null} no puede expresar. "No
 *                        toques el fin de vigencia" y "sacale el fin, que quede sin fin
 *                        previsto" son dos intenciones distintas, y con un solo parametro
 *                        nulable la segunda es imposible de pedir. Cuando vale {@code true},
 *                        {@code validUntil} se ignora
 * @param expectedVersion version que el cliente leyo. Se compara antes de mutar: si quedo
 *                        vieja, 409 y el cliente recarga. Sin esto dos ediciones simultaneas se
 *                        pisan y el segundo en guardar borra el cambio del primero sin que
 *                        nadie se entere
 */
public record EspacioEdicionCommand(
		String name,
		EspacioTipo tipo,
		Integer capacidad,
		String notes,
		Instant validFrom,
		Instant validUntil,
		boolean clearValidUntil,
		long expectedVersion) {
}
