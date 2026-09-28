package com.akine.activity.application;

import java.util.List;

/**
 * El resultado de un lote de asistencia (RF-M13-008).
 *
 * <p><b>El HTTP es 200 aunque haya fallos parciales</b>: no es un error del pedido, es el resultado
 * del pedido. Publicar un 207 obligaria a cada cliente generado a manejar un status que este
 * repositorio no usa en ninguna otra parte.
 */
public record ResultadoDeLote(List<ItemDeLote> items, int exitosos, int fallidos, CuposView cupos) {

	public static ResultadoDeLote de(List<ItemDeLote> items, CuposView cupos) {
		int exitosos = (int) items.stream().filter(ItemDeLote::exitoso).count();
		return new ResultadoDeLote(items, exitosos, items.size() - exitosos, cupos);
	}
}
