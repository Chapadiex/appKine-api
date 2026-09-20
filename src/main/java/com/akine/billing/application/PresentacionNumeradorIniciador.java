package com.akine.billing.application;

import com.akine.billing.domain.port.PresentacionNumeradorPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea la fila del numerador de lotes en su PROPIA transaccion, antes de confirmar.
 *
 * <p>Mismo patron y misma razon que {@code ComprobanteIniciador}, {@code AgendaSedeIniciador} y
 * {@code NumeradorIniciador}: crearla dentro de la transaccion que la bloquea produce deadlock
 * entre las primeras confirmaciones concurrentes del mismo financiador, y atrapar la excepcion no
 * alcanza porque <b>no des-marca la transaccion</b> — Spring lanza {@code UnexpectedRollbackException}
 * al commitear. Es la cuarta vez que este patron aparece en el proyecto, y siempre por lo mismo.
 */
@Component
public class PresentacionNumeradorIniciador {

	private final PresentacionNumeradorPort numerador;

	public PresentacionNumeradorIniciador(PresentacionNumeradorPort numerador) {
		this.numerador = numerador;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void asegurar(long organizationId, long consultorioId, long financiadorId) {
		numerador.crearSiFalta(organizationId, consultorioId, financiadorId);
	}
}
