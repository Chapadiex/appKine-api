package com.akine.person.spi;

import java.time.Instant;

/**
 * Un hecho datado del Paciente 360.
 *
 * <p>Es deliberadamente pobre, por el mismo motivo que {@code clinical.spi.EventoClinico}: el 360
 * es un indice, no un visor. Trae cuando paso, de que se trata en una linea, en que estado quedo y
 * el id con el que pedirle el detalle al modulo duenio —que lo va a autorizar por su cuenta—.
 *
 * <p><b>Nada de contenido clinico.</b> "Turno del 3 de marzo" es un hito; lo que se escribio en
 * la sesion de ese turno no lo es, y pedirlo es entrar a M09 con justificacion declarada.
 *
 * @param origen     modulo que produjo el hecho, para saber a quien pedirle el detalle
 * @param referencia id de la entidad de origen dentro de ese modulo
 */
public record HitoDeResumen(
		String origen,
		String tipo,
		Instant ocurrioEn,
		String titulo,
		String estado,
		long referencia) {
}
