package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * Queda plata reclamada sin explicar. <b>409</b>. RF-M21-008.
 *
 * <p>Conciliar exige <b>saldo cero</b>. No hay ajuste automatico, no hay cierre con diferencia y no
 * hay write-off silencioso:
 *
 * <ul>
 *   <li><b>Conciliar con residual y anotarlo</b> haria que el residual dejara de estar en ninguna
 *       parte como lo que es —plata que se reclamo y no se cobro— y que la cuenta corriente
 *       cuadrara por definicion.</li>
 *   <li><b>Prorratear el residual entre los items</b> inventaria un debito que el financiador nunca
 *       informo, sobre prestaciones elegidas por una formula.</li>
 * </ul>
 *
 * <p>Lo que el sistema hace es <b>nombrar el residual y negarse a fingir</b>. El administrativo
 * tiene dos salidas honestas y las dos ya existen: registrar los debitos que faltan, o registrar el
 * pago que falta. Mismo criterio con el que 07.03 se niega a ajustar la diferencia de arqueo con un
 * movimiento que la iguale.
 */
public class PresentacionNoConciliaException extends RuntimeException {

	private final long presentacionId;
	private final BigDecimal residual;

	public PresentacionNoConciliaException(long presentacionId, BigDecimal residual) {
		super("La presentacion " + presentacionId + " tiene " + residual
				+ " sin explicar: falta registrar el debito o el pago");
		this.presentacionId = presentacionId;
		this.residual = residual;
	}

	public long getPresentacionId() {
		return presentacionId;
	}

	public BigDecimal getResidual() {
		return residual;
	}
}
