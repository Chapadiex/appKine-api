package com.akine.person.domain.exception;

import java.time.LocalDate;

/**
 * La autorizacion no habilita ese dia: esta vencida, no empezo, o no esta APROBADA (409).
 *
 * <p><b>Vencida no es un estado persistido</b>, y eso es lo que hace que esta excepcion exista.
 * {@code autorizacion.estado} solo toma los cuatro valores que decide una persona; VENCIDA y
 * AGOTADA se calculan al leer, contra la fecha que se pregunta. Materializarlas exigiria un job, y
 * un job que no corre deja autorizaciones vencidas que el sistema cree vigentes.
 *
 * <p>Va con el dia contra el que se evaluo porque sin el la respuesta es inentendible: "vencida"
 * depende de cuando se pregunta, y una reversion de un consumo de hace dos meses se evalua contra
 * aquel dia, no contra hoy.
 */
public class AutorizacionVencidaException extends RuntimeException {

	private final long autorizacionId;
	private final LocalDate fecha;

	public AutorizacionVencidaException(long autorizacionId, LocalDate fecha) {
		super("La autorizacion no habilita el " + fecha);
		this.autorizacionId = autorizacionId;
		this.fecha = fecha;
	}

	public long getAutorizacionId() {
		return autorizacionId;
	}

	public LocalDate getFecha() {
		return fecha;
	}
}
