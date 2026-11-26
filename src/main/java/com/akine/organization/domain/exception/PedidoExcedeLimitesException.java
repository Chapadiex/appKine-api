package com.akine.organization.domain.exception;

import com.akine.organization.spi.LimitCode;

import java.util.List;

/**
 * El centro tiene mas sedes o profesionales de los que permite el plan pedido: no se aprueba hasta
 * que se ajuste (409, 26/11/2026).
 */
public class PedidoExcedeLimitesException extends RuntimeException {

	/** Un tope del plan pedido que el centro hoy supera. */
	public record Exceso(LimitCode limitCode, int tope, long uso) {
	}

	private final List<Exceso> excesos;

	public PedidoExcedeLimitesException(List<Exceso> excesos) {
		super("El centro supera los topes del plan pedido");
		this.excesos = List.copyOf(excesos);
	}

	public List<Exceso> getExcesos() {
		return excesos;
	}
}
