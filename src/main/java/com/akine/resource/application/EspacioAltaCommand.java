package com.akine.resource.application;

import com.akine.resource.domain.EspacioTipo;

import java.time.Instant;

/**
 * Alta de un espacio (RF-M04-001, RF-M04-007).
 *
 * <p>No lleva {@code organizationId} ni {@code consultorioId}: los dos salen del contexto
 * validado y de la ruta, y viajan como parametros del servicio. Meterlos aca invitaria a que
 * algun dia se llenaran desde el cuerpo del request, que es exactamente lo que el modelo de
 * contexto existe para impedir.
 *
 * @param capacidad  {@code null} toma {@code Espacio.CAPACIDAD_POR_DEFECTO} = 1, que es el caso
 *                   de un box individual y el mas frecuente
 * @param validFrom  {@code null} significa "desde ya": el servicio lo resuelve al instante del
 *                   alta. Es una comodidad del cliente, no un default del dominio, que lo exige
 * @param validUntil {@code null} = sin fin previsto
 */
public record EspacioAltaCommand(
		String name,
		EspacioTipo tipo,
		Integer capacidad,
		String notes,
		Instant validFrom,
		Instant validUntil) {
}
