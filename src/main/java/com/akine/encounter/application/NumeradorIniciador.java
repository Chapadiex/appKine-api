package com.akine.encounter.application;

import com.akine.encounter.domain.port.SesionNumeradorPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea la fila del numerador en su PROPIA transaccion, antes de que el cierre empiece.
 *
 * <p>Mismo patron y misma razon que {@code scheduling.application.AgendaSedeIniciador}: crearla
 * dentro de la transaccion del cierre produce un deadlock entre los primeros N cierres concurrentes
 * de una misma historia clinica, y atrapar la excepcion no alcanza porque no des-marca la
 * transaccion.
 *
 * <p>{@code REQUIRES_NEW} hace que el INSERT se commitee de inmediato y libere su lock, en vez de
 * retenerlo hasta el final del cierre.
 */
@Component
public class NumeradorIniciador {

	private final SesionNumeradorPort numerador;

	public NumeradorIniciador(SesionNumeradorPort numerador) {
		this.numerador = numerador;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void asegurar(long organizationId, long historiaClinicaId) {
		numerador.crearSiFalta(organizationId, historiaClinicaId);
	}
}
