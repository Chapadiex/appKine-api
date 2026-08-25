package com.akine.resource.spi;

import java.time.Instant;

/**
 * Lo que otro modulo necesita saber de un espacio, sin poder tocar su entity.
 *
 * <p>Es el "contexto que deja disponible para la etapa siguiente" que pide la etapa
 * AKINE-02.02: <b>id, capacidad y vigencia</b>. La agenda de F5 arma con esto la grilla de
 * recursos sin leer una sola fila de {@code espacio}, que es de este modulo.
 *
 * <p><b>{@code name} viaja aunque el consumidor no lo necesite para decidir nada</b>, y es
 * deliberado: RN-M04-003 exige que los historicos conserven el nombre del recurso. Un turno
 * que solo guardara el id mostraria, seis meses despues, el nombre ACTUAL del box y no el que
 * tenia el dia de la atencion. Quien registre un hecho sobre un espacio deberia copiarse este
 * nombre en su propia fila.
 *
 * @param enServicio si el espacio esta operable Y dentro de su ventana operativa en el instante
 *                   que se pregunto. Es la respuesta a RN-M04-002 ya calculada: recalcularla
 *                   del lado del consumidor duplicaria la regla y las dos copias divergirian
 */
public record EspacioSnapshot(
		long id,
		long organizationId,
		long consultorioId,
		String name,
		String tipo,
		int capacidad,
		Instant validFrom,
		Instant validUntil,
		boolean active,
		boolean enServicio) {
}
