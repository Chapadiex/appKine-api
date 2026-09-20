package com.akine.activity.application;

import java.util.List;

/**
 * El resultado del cierre operativo de una clase.
 *
 * <p><b>Cerrar no cobra y no devenga nada</b> (misma regla que DP-06 para la Sesion). Lo que esta
 * operacion deja es una participacion resuelta por persona: la referencia economica unica de la
 * que 08.06 y 08.07 van a colgar el devengo cuando la politica exista.
 *
 * @param cerroAhora      {@code false} si ya estaba realizada. Un segundo cierre no encuentra
 *                        pendientes y no escribe nada: la idempotencia sale del estado del mundo,
 *                        no de una bandera
 * @param ausentados      los que quedaron sin marcar y el cierre resolvio como ausentes
 * @param esperaCancelada cuantos quedaron en la cola de una clase que ya ocurrio. Dejarlos
 *                        esperando seria un estado que no se resuelve nunca
 */
public record ResultadoDeCierre(
		ClaseView clase,
		boolean cerroAhora,
		List<AsistenciaView> ausentados,
		int esperaCancelada,
		CuposView cupos) {
}
