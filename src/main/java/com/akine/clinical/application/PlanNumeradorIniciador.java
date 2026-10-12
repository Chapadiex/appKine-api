package com.akine.clinical.application;

import com.akine.platform.spi.transaccion.FilaLockPrevia;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanNumeradorPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

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
 * <p>La transaccion propia hace que el INSERT se commitee de inmediato y libere su lock, en vez de
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
	private final FilaLockPrevia filaPlanNumerador;

	public PlanNumeradorIniciador(PlanNumeradorPort planes,
			PlatformTransactionManager transactionManager) {
		this.planes = planes;
		this.filaPlanNumerador = new FilaLockPrevia("plan_numerador", transactionManager);
	}

	/** La secuencia de planes de ese Caso Clinico. */
	public void asegurarPlanes(long organizationId, long casoClinicoId) {
		filaPlanNumerador.asegurar(
				() -> planes.existeFila(organizationId, casoClinicoId),
				() -> planes.crearSiFalta(organizationId, casoClinicoId));
	}
}
