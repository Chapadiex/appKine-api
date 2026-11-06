package com.akine.scheduling.domain.exception;

/**
 * La sede existe y esta activa pero todavia no esta habilitada para agenda (V140,
 * {@code agendaHabilitada = false}): no admite turnos nuevos, series ni traslados hacia ella.
 *
 * <p>Es distinto de {@link SedeDadaDeBajaException}: la sede de baja no vuelve; esta esta a medio
 * configurar y va a abrir. Por eso tiene su propio 409 {@code consultorio-sin-agenda}, para que la
 * pantalla pueda decir "termina de configurar la sede" en vez de "la sede no existe".
 *
 * <p>Se evalua sobre el snapshot leido ANTES del lock de la agenda, sin lock propio: habilitar la
 * agenda es un acto administrativo poco frecuente y una reserva que se cuele en el milisegundo en
 * que se apaga no rompe ningun invariante, a diferencia de la baja.
 */
public class SedeSinAgendaException extends RuntimeException {

	private final long consultorioId;

	public SedeSinAgendaException(long consultorioId) {
		super("La sede todavia no esta habilitada para agenda y turnos");
		this.consultorioId = consultorioId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}
}
