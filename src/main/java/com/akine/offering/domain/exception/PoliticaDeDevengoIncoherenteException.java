package com.akine.offering.domain.exception;

/**
 * La combinacion de esquema de cobro y momento de devengo no existe (RN-M27-006).
 *
 * <p>Es {@code 422 validation-error} y no {@code 409}: no es un estado del mundo que impide la
 * operacion, es un pedido que no tiene sentido. Lo mapea {@code OfferingProblemHandler}, que es el
 * advice del modulo: mapearlo en {@code GlobalExceptionHandler} pondria una regla de M27 en una
 * clase que no es de nadie.
 */
public class PoliticaDeDevengoIncoherenteException extends RuntimeException {

	public PoliticaDeDevengoIncoherenteException(String motivo) {
		super("Politica de devengo incoherente: " + motivo);
	}
}
