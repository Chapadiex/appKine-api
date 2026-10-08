package com.akine.resource.spi;

import com.akine.organization.spi.ConsultorioSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Lectura de disponibilidad efectiva para modulos que no son duenos de M05.
 *
 * <p>Es la costura por la que el motor de agenda (M12) consume el calculo de 02.04 en vez de
 * reimplementarlo. La cabecera de {@code DisponibilidadEfectivaService} enumera cinco cosas que
 * ese calculo hace y que <b>ninguna falla de forma visible cuando se omite</b> —el huso, el fin
 * de dia, el filtro por sede, la vigencia dia por dia y el nombre del feriado—. Una segunda
 * implementacion del otro lado deriva de esta sin que ningun test lo denuncie: por eso hay
 * costura y no copia.
 *
 * <p><b>No autoriza.</b> El llamador trae la sede ya resuelta contra su propio tenant y aplica su
 * propio permiso. Ver {@code DisponibilidadEfectivaService#sinAutorizar}.
 */
public interface DisponibilidadDirectory {

	/**
	 * Franjas de atencion de un profesional en una sede, dia por dia, ya en instantes UTC.
	 *
	 * @param sede  snapshot ya validado contra el tenant del contexto por el llamador
	 * @param hasta fecha local <b>EXCLUSIVA</b>
	 */
	List<DiaDisponible> efectiva(
			long organizationId,
			ConsultorioSnapshot sede,
			long membershipId,
			LocalDate desde,
			LocalDate hasta);

	/**
	 * {@code true} si {@code [inicio, fin)} cae, aunque sea en parte, fuera del horario general de
	 * la sede (A-8b, DP-19). Una sede sin horario cargado nunca deja nada afuera.
	 *
	 * <p>{@link #efectiva} ya devuelve la disponibilidad recortada a ese horario: esta pregunta es
	 * para lo que NO pasa por la disponibilidad de un profesional —la reserva de una oferta sin
	 * profesional—.
	 *
	 * @param sede snapshot ya validado contra el tenant del contexto por el llamador
	 */
	boolean fueraDelHorarioDeSede(
			long organizationId, ConsultorioSnapshot sede, Instant inicio, Instant fin);

	/**
	 * Un dia resuelto.
	 *
	 * @param razonVacio {@code "FERIADO"}, {@code "CIERRE"}, {@code "VINCULO"} o
	 *                   {@code "HORARIO_SEDE"} (A-8b: el profesional tenia horario y la sede no
	 *                   abre en ninguna de esas horas); {@code null} si el
	 *                   dia tiene franjas y tambien —cuarto estado— si quedo vacio porque ninguna
	 *                   regla lo abrio. El motor de agenda traduce ese {@code null} con franjas
	 *                   vacias a {@code SIN_HORARIO}, que es un motivo propio suyo
	 */
	record DiaDisponible(LocalDate fecha, String razonVacio, List<Franja> franjas) {

		public DiaDisponible {
			franjas = franjas == null ? List.of() : List.copyOf(franjas);
		}
	}

	/** @param hasta instante de fin <b>EXCLUSIVO</b>; una franja que llega a medianoche trae el inicio del dia siguiente */
	record Franja(Instant desde, Instant hasta) {
	}
}
