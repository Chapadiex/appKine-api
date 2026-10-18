package com.akine.activity.application;

import java.util.List;

/**
 * El resultado de un lote de atencion grupal (AKINE-08.05). <b>200 aunque haya fallos
 * parciales</b>, como el lote de asistencia: es el resultado del pedido, no un error.
 */
public record ResultadoDeAtencionGrupal(List<ItemDeAtencionGrupal> items, int exitosos, int fallidos) {

	public static ResultadoDeAtencionGrupal de(List<ItemDeAtencionGrupal> items) {
		int exitosos = (int) items.stream().filter(ItemDeAtencionGrupal::exitoso).count();
		return new ResultadoDeAtencionGrupal(List.copyOf(items), exitosos, items.size() - exitosos);
	}
}
