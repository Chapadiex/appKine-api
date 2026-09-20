package com.akine.clinical.application;

import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanNumeradorPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea la fila del numerador de planes en su PROPIA transaccion, antes de que la que la bloquea
 * empiece.
 *
 * <h2>Por que existe esta clase en vez de un lazy-create adentro</h2>
 *
 * <p>Mismo patron y misma razon que {@link CasoNumeradorIniciador},
 * {@code encounter.application.NumeradorIniciador} y {@code scheduling.application.AgendaSedeIniciador}:
 * crear la fila dentro de la transaccion que despues la bloquea produce un <b>deadlock</b> entre las
 * primeras N escrituras concurrentes, y atrapar la excepcion no alcanza porque no des-marca la
 * transaccion — Spring lanza {@code UnexpectedRollbackException} al commitear, con un mensaje que
 * no nombra la causa. <b>Este repositorio ya lo pago cuatro veces</b> ({@code agenda_sede},
 * {@code consultorio_calendario}, {@code sesion_numerador}, {@code autorizacion_persona_lock}). No
 * se paga una quinta.
 *
 * <p>{@code REQUIRES_NEW} hace que el INSERT se commitee de inmediato y libere su lock, en vez de
 * retenerlo hasta el final de la operacion que lo llamo.
 *
 * <h2>Por que no se fusiona con {@code CasoNumeradorIniciador}</h2>
 *
 * <p>Porque son tres numeradores con tres claves distintas y fusionarlos escondería justamente lo
 * que hay que tener presente: son tres locks diferentes. Y hay una diferencia de forma: los dos de
 * aquella clase se piden desde la misma etapa, este desde otra. Una clase por numerador deja la
 * dependencia explicita en el constructor del servicio que lo usa.
 */
@Component
public class PlanNumeradorIniciador {

	private final PlanNumeradorPort planes;

	public PlanNumeradorIniciador(PlanNumeradorPort planes) {
		this.planes = planes;
	}

	/** La secuencia de planes de ese Caso Clinico. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void asegurarPlanes(long organizationId, long casoClinicoId) {
		planes.crearSiFalta(organizationId, casoClinicoId);
	}
}
