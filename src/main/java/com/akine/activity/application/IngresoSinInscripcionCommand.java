package com.akine.activity.application;

import com.akine.activity.domain.ResultadoAsistencia;

/**
 * Lo que hay que decir para dejar entrar a quien se presento sin estar inscripto (RF-M13-007).
 *
 * @param resultado normalmente {@code PRESENTE} o {@code PRESENTE_TARDE}. Se admite
 *                  {@code AUSENTE} y suena absurdo, pero no se prohibe: quien se anota en el
 *                  mostrador y despues se va igual ocupo el lugar, y negarlo obligaria a inventar
 *                  un segundo camino para el mismo hecho
 */
public record IngresoSinInscripcionCommand(
		long personaId,
		ResultadoAsistencia resultado,
		String observaciones) {
}
