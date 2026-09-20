package com.akine.billing.domain.exception;

/**
 * La sede no tiene jornada de caja abierta y la operacion exige una. <b>409</b>.
 *
 * <p><b>El caso que importa es el cobro en efectivo.</b> La plata entra al cajon exista o no la
 * jornada; si el sistema no sabe a que jornada pertenece, el arqueo de ese dia no cuadra contra
 * nada y el dinero queda fuera de todo control. Abrir la caja son diez segundos; recuperar un
 * arqueo roto no lo es.
 *
 * <p>Las dos alternativas se descartaron a proposito: abrir la jornada automaticamente inventaria
 * una apertura sin responsable y con saldo inicial cero —y "apertura auditada" dejaria de
 * significar algo—, y aceptar el efectivo sin jornada es exactamente el agujero que M20 existe para
 * tapar.
 *
 * <p><b>Un cobro que NO incluye efectivo no llega aca</b>: esa plata nunca toco el cajon, su
 * movimiento se asienta sin jornada y no participa de ningun arqueo.
 *
 * <p>409 y no 400: el cuerpo era valido y lo que falta es un estado del servidor que el operador
 * puede crear en el acto.
 */
public class CajaNoAbiertaException extends RuntimeException {

	private final long consultorioId;

	public CajaNoAbiertaException(long consultorioId) {
		super("La sede " + consultorioId + " no tiene una caja abierta: el efectivo no puede "
				+ "registrarse fuera de una jornada");
		this.consultorioId = consultorioId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}
}
