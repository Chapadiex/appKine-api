package com.akine.scheduling.domain.exception;

import java.time.Instant;

/**
 * Una ocurrencia de una operacion de serie no tiene lugar, y por eso no se hizo NADA (AKINE E-3).
 *
 * <p>Envuelve la causa real —{@link SlotNoDisponibleException}, {@link SlotCompletoException},
 * {@link RecursoOcupadoException} u {@link OfertaNoAgendableException}— y le agrega el instante de
 * la ocurrencia que fallo. El problema HTTP conserva el {@code problemType} de la causa: para la
 * pantalla la accion es la misma que en una reserva suelta, con una fecha mas para mostrar.
 */
public class OcurrenciaSinLugarException extends RuntimeException {

	private final Instant ocurrenciaInicio;

	public OcurrenciaSinLugarException(Instant ocurrenciaInicio, RuntimeException causa) {
		super("La ocurrencia del " + ocurrenciaInicio + " no tiene lugar: " + causa.getMessage(), causa);
		this.ocurrenciaInicio = ocurrenciaInicio;
	}

	public Instant getOcurrenciaInicio() {
		return ocurrenciaInicio;
	}

	public RuntimeException getCausa() {
		return (RuntimeException) getCause();
	}
}
