package com.akine.scheduling.domain.exception;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Una ocurrencia de una operacion de serie no tiene lugar, y por eso no se hizo NADA (AKINE E-3).
 *
 * <p>Envuelve la causa real —{@link SlotNoDisponibleException}, {@link SlotCompletoException},
 * {@link RecursoOcupadoException} u {@link OfertaNoAgendableException}— y le agrega el instante de
 * la ocurrencia que fallo. El problema HTTP conserva el {@code problemType} de la causa: para la
 * pantalla la accion es la misma que en una reserva suelta, con una fecha mas para mostrar.
 */
public class OcurrenciaSinLugarException extends RuntimeException {

	private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

	private final Instant ocurrenciaInicio;
	private final ZoneId zona;

	/** Sin zona: la ocurrencia se escribe en UTC. Solo para dobles de test. */
	public OcurrenciaSinLugarException(Instant ocurrenciaInicio, RuntimeException causa) {
		this(ocurrenciaInicio, ZoneOffset.UTC, causa);
	}

	/**
	 * @param zona la de la sede: el {@code detail} escribe la ocurrencia en hora local (dd/MM/yyyy HH:mm),
	 *             igual que las franjas de {@code ConflictoDeAgenda}, y no el instante UTC crudo.
	 */
	public OcurrenciaSinLugarException(Instant ocurrenciaInicio, ZoneId zona, RuntimeException causa) {
		super(prefijo(ocurrenciaInicio, zona) + causa.getMessage(), causa);
		this.ocurrenciaInicio = ocurrenciaInicio;
		this.zona = zona;
	}

	private static String prefijo(Instant ocurrenciaInicio, ZoneId zona) {
		return "La ocurrencia del " + LOCAL.format(ocurrenciaInicio.atZone(zona)) + " no tiene lugar: ";
	}

	/** El {@code detail}: la ocurrencia en hora de la sede y el texto que la causa da en una reserva suelta. */
	public String detalleCon(String detalleDeLaCausa) {
		return prefijo(ocurrenciaInicio, zona)
				+ (detalleDeLaCausa == null ? getCause().getMessage() : detalleDeLaCausa);
	}

	public Instant getOcurrenciaInicio() {
		return ocurrenciaInicio;
	}

	public RuntimeException getCausa() {
		return (RuntimeException) getCause();
	}
}
