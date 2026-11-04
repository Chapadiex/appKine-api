package com.akine.scheduling.spi;

import java.time.Instant;

/**
 * Un intervalo de agenda ya tomado por un evento, con el recurso que ocupa.
 *
 * <p>Es la forma minima que necesita quien pregunta "esto esta libre": no lleva paciente, ni estado
 * interno, ni nada mas de la entidad que lo produjo. Un modulo que necesitara mas datos para
 * decidir una exclusion estaria decidiendo con reglas que no son suyas.
 *
 * @param tipo     discriminador del evento que ocupa. {@code TURNO} para M12
 * @param eventoId id dentro de su propio agregado. <b>No es unico entre tipos</b>: un turno 7 y una
 *                 clase 7 existen a la vez
 * @param fin      exclusivo, igual que en todo el proyecto: un evento que termina exactamente
 *                 cuando empieza el otro <b>no</b> se cruza con el
 * @param consultorioId sede del evento; puede ser otra que la del llamador cuando la pregunta es por
 *                 persona (agenda de clases y avisos)
 */
public record OcupacionDeAgenda(
		String tipo,
		long eventoId,
		Long profesionalMembershipId,
		Long espacioId,
		Instant inicio,
		Instant fin,
		Long consultorioId) {

	/** Sin sede: para quien no la necesita (la sede la conoce el llamador). */
	public OcupacionDeAgenda(
			String tipo, long eventoId, Long profesionalMembershipId, Long espacioId,
			Instant inicio, Instant fin) {
		this(tipo, eventoId, profesionalMembershipId, espacioId, inicio, fin, null);
	}

	/** {@code true} si esta ocupacion se cruza con {@code [desde, hasta)}. */
	public boolean seCruzaCon(Instant desde, Instant hasta) {
		return inicio.isBefore(hasta) && desde.isBefore(fin);
	}
}
