package com.akine.person.domain.exception;

/**
 * Se pidio revertir un consumo sin declarar por que (400).
 *
 * <p>RF-M17-005. Es <b>400 y no 409</b>: no hay ningun estado del sistema que impida la operacion,
 * falta un dato del pedido. Confundirlos haria que la pantalla ofrezca "reintentar" donde lo que
 * corresponde es "completa el motivo".
 *
 * <p>La regla vive en tres lados y ninguno sobra: esta excepcion la explica, el constructor de
 * {@code AutorizacionMovimiento} la hace cumplir para cualquier camino de codigo, y
 * {@code ck_movimiento_motivo_exigido} de {@code V50} la hace cumplir del lado del motor para el
 * dia que aparezca un segundo camino que nadie recuerde revisar.
 */
public class ReversionSinMotivoException extends RuntimeException {

	public ReversionSinMotivoException() {
		super("Revertir un consumo de autorizacion exige un motivo declarado");
	}
}
