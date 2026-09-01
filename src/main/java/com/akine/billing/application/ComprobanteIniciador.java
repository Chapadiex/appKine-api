package com.akine.billing.application;

import com.akine.billing.domain.port.ComprobanteNumeradorPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea la fila del numerador de comprobantes en su PROPIA transaccion, antes del cobro.
 *
 * <p>Mismo patron y misma razon que {@code AgendaSedeIniciador} y {@code NumeradorIniciador}:
 * crearla dentro de la transaccion del cobro produce un deadlock entre los primeros N cobros
 * concurrentes de una sede, y atrapar la excepcion no alcanza porque no des-marca la transaccion.
 * Es la tercera vez que este patron aparece en el proyecto, y siempre por lo mismo.
 */
@Component
public class ComprobanteIniciador {

	private final ComprobanteNumeradorPort numerador;

	public ComprobanteIniciador(ComprobanteNumeradorPort numerador) {
		this.numerador = numerador;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void asegurar(long organizationId, long consultorioId) {
		numerador.crearSiFalta(organizationId, consultorioId);
	}
}
