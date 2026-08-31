package com.akine.scheduling.application;

import com.akine.scheduling.domain.exception.VentanaDeAgendaDemasiadoAmpliaException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Tope de la ventana que el buscador de turnos acepta.
 *
 * <h2>Por que 62 dias y no los 366 de la disponibilidad</h2>
 *
 * <p>{@code resource.VentanaConsultable} permite un anio porque responde "que horario tiene
 * cargado este profesional", que es una fila por dia. Esto responde "que huecos concretos hay",
 * que son <b>decenas de filas por dia y por profesional</b>: una oferta de 30 minutos con cinco
 * profesionales atendiendo ocho horas produce 80 slots diarios, o sea casi 30.000 en un anio.
 *
 * <p>El costo no es solo el tamano de la respuesta. El motor pide la disponibilidad efectiva
 * <b>una vez por profesional habilitado</b>, y cada una de esas llamadas lee bloques, excepciones
 * y feriados de la ventana entera. Con la ventana de un anio, una sola consulta puede barrer la
 * agenda completa de una sede.
 *
 * <p>Dos meses cubren de sobra el caso real —un paciente elige turno para las proximas semanas, y
 * una recepcionista reprograma dentro del mes— y dejan el peor caso en un tamano que se puede
 * medir. Si alguna vez hace falta mas, el numero se sube aca y no en cinco lugares.
 */
public final class VentanaDeAgenda {

	public static final int VENTANA_MAXIMA_DIAS = 62;

	private VentanaDeAgenda() {
	}

	public static void exigirValida(LocalDate desde, LocalDate hasta) {
		if (desde == null || hasta == null) {
			throw new IllegalArgumentException(
					"La ventana de agenda exige un inicio y un fin: " + desde + " -> " + hasta);
		}
		if (!hasta.isAfter(desde)) {
			// El extremo superior es EXCLUSIVO, igual que en M05. Aceptar hasta == desde
			// devolveria cero dias, y el cliente lo leeria como "no hay turnos".
			throw new IllegalArgumentException(
					"El fin de la ventana debe ser posterior a su inicio, y es EXCLUSIVO: "
							+ desde + " -> " + hasta);
		}
		long dias = ChronoUnit.DAYS.between(desde, hasta);
		if (dias > VENTANA_MAXIMA_DIAS) {
			throw new VentanaDeAgendaDemasiadoAmpliaException(desde, hasta, VENTANA_MAXIMA_DIAS);
		}
	}
}
