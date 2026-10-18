package com.akine.activity.domain.exception;

/** El borrador de honorarios no tiene ninguna clase liquidable en el periodo (AKINE-08.09b). 409. */
public class LiquidacionDeHonorariosVaciaException extends RuntimeException {

	public LiquidacionDeHonorariosVaciaException() {
		super("No hay clases para liquidar en el periodo: todas estan liquidadas, sin tarifa o sin presentes");
	}
}
