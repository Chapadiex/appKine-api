package com.akine.resource.application;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Edicion parcial de un bloque de disponibilidad (RF-M05-005).
 *
 * <p>Semantica de PATCH: cada campo {@code null} deja el valor como estaba. Misma forma que
 * {@code EspacioEdicionCommand}, y por los mismos motivos.
 *
 * <p><b>El profesional no se puede cambiar.</b> No hay campo de membership y no es un olvido:
 * reasignar un bloque a otro profesional no es una edicion sino un bloque nuevo (RN-M05-001), y
 * la columna esta declarada {@code updatable = false} en la entity. Permitirlo dejaria la
 * autoria historica apuntando a quien nunca atendio en esa franja.
 *
 * @param limpiarVigenciaHasta hace explicito el caso que un {@code null} no puede expresar. "No
 *                             toques el fin de vigencia" y "sacale el fin, que quede sin fin
 *                             previsto" son dos intenciones distintas, y con un solo parametro
 *                             nulable la segunda es imposible de pedir. Cuando vale
 *                             {@code true}, {@code vigenciaHasta} se ignora
 * @param expectedVersion      version que el cliente leyo. Se compara antes de mutar: si quedo
 *                             vieja, 409 {@code concurrent-modification} y el cliente recarga.
 *                             Sin esto dos ediciones simultaneas se pisan y el segundo en
 *                             guardar borra el cambio del primero sin que nadie se entere
 */
public record BloqueEdicionCommand(
		Integer diaSemana,
		LocalTime horaDesde,
		LocalTime horaHasta,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean limpiarVigenciaHasta,
		long expectedVersion) {
}
