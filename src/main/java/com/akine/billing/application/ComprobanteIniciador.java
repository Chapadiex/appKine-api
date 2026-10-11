package com.akine.billing.application;

import com.akine.platform.spi.transaccion.FilaLockPrevia;
import com.akine.billing.domain.port.ComprobanteNumeradorPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

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
	private final FilaLockPrevia filaComprobanteNumerador;

	public ComprobanteIniciador(ComprobanteNumeradorPort numerador,
			PlatformTransactionManager transactionManager) {
		this.numerador = numerador;
		this.filaComprobanteNumerador = new FilaLockPrevia("comprobante_numerador", transactionManager);
	}

	public void asegurar(long organizationId, long consultorioId) {
		filaComprobanteNumerador.asegurar(
				() -> numerador.existeFila(organizationId, consultorioId),
				() -> numerador.crearSiFalta(organizationId, consultorioId));
	}
}
