package com.akine.activity.application;

import java.time.LocalDate;
import java.util.List;

/**
 * Lo que paso con cada fecha de una serie de clases. <b>No es todo o nada</b>: un feriado o un box
 * ocupado un martes no impide programar los demas martes. Cada fecha que no se pudo programar dice
 * por que, para que quien programa la resuelva a mano.
 *
 * @param programadas las clases de la serie, creadas ahora o —en un reintento con la misma clave— ya
 *                    existentes
 * @param omitidas    las fechas que no se programaron, en orden
 */
public record ResultadoDeSerieDeClases(List<ClaseView> programadas, List<FechaOmitida> omitidas) {

	public ResultadoDeSerieDeClases {
		programadas = List.copyOf(programadas);
		omitidas = List.copyOf(omitidas);
	}

	/**
	 * @param motivo  causa codificada, para que la pantalla agrupe y traduzca
	 * @param detalle el mensaje que daria la misma fecha programada como clase suelta
	 */
	public record FechaOmitida(LocalDate fecha, MotivoDeOmision motivo, String detalle) {
	}

	/** Por que una fecha de la serie no se programo. Cada uno es el 409 de una clase suelta, o previo. */
	public enum MotivoDeOmision {
		/** La fecha ya paso en la zona de la sede. */
		PASADO,
		/** Feriado que cierra la sede para el profesional (U-22, igual que las series de turnos). */
		FERIADO,
		/** La oferta no esta vigente ese dia ({@code clase-no-programable}). */
		OFERTA_NO_VIGENTE,
		/** El profesional no atiende, no esta habilitado o hay un cierre ({@code slot-no-disponible}). */
		HORARIO_NO_DISPONIBLE,
		/** El profesional o el box ya tienen un turno o una clase que se cruza ({@code recurso-ocupado}). */
		RECURSO_OCUPADO,
		/** El cupo pedido supera el de la oferta o el del box ({@code capacidad-no-admitida}). */
		CAPACIDAD,
		/** La misma clave de idempotencia ya programo otra clase distinta esa fecha. */
		CLAVE_REUSADA
	}
}
