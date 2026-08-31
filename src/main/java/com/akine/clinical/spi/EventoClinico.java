package com.akine.clinical.spi;

import java.time.Instant;

/**
 * Un hecho clinico datado, aportado al timeline de una Historia Clinica por el modulo que lo
 * conoce.
 *
 * <p>Es deliberadamente pobre: instante, origen, tipo, un titulo corto y la referencia a la
 * entidad de origen. <b>No trae contenido clinico</b> —la evolucion de una sesion, el texto de un
 * informe— porque el timeline es un indice, no un visor: quien quiera el detalle va al modulo
 * dueño con su propio permiso, y ese acceso se audita alli.
 *
 * @param origen     modulo o entidad que produjo el hecho, para que el consumidor sepa a quien
 *                   pedirle el detalle
 * @param referencia id de la entidad de origen dentro de ese modulo
 */
public record EventoClinico(
		Instant ocurrioEn,
		String origen,
		String tipo,
		String titulo,
		long referencia) {
}
