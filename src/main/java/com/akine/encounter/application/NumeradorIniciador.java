package com.akine.encounter.application;

import com.akine.platform.spi.transaccion.FilaLockPrevia;
import com.akine.encounter.domain.port.SesionNumeradorPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Crea la fila del numerador en su PROPIA transaccion, antes de que el cierre empiece.
 *
 * <p>Mismo patron y misma razon que {@code scheduling.application.AgendaSedeIniciador}: crearla
 * dentro de la transaccion del cierre produce un deadlock entre los primeros N cierres concurrentes
 * de una misma historia clinica, y atrapar la excepcion no alcanza porque no des-marca la
 * transaccion.
 *
 * <p>La transaccion propia hace que el INSERT se commitee de inmediato y libere su lock, en vez de
 * retenerlo hasta el final del cierre.
 */
@Component
public class NumeradorIniciador {

	private final SesionNumeradorPort numerador;
	private final FilaLockPrevia filaSesionNumerador;

	public NumeradorIniciador(SesionNumeradorPort numerador,
			PlatformTransactionManager transactionManager) {
		this.numerador = numerador;
		this.filaSesionNumerador = new FilaLockPrevia("sesion_numerador", transactionManager);
	}

	public void asegurar(long organizationId, long historiaClinicaId) {
		filaSesionNumerador.asegurar(
				() -> numerador.existeFila(organizationId, historiaClinicaId),
				() -> numerador.crearSiFalta(organizationId, historiaClinicaId));
	}
}
