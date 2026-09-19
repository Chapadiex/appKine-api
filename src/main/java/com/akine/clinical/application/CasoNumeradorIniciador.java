package com.akine.clinical.application;

import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoNumeradorPort;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoSesionNumeradorPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea la fila de los numeradores del Caso en su PROPIA transaccion, antes de que la que los
 * bloquea empiece.
 *
 * <h2>Por que existe esta clase en vez de un lazy-create adentro</h2>
 *
 * <p>Mismo patron y misma razon que {@code encounter.application.NumeradorIniciador} y
 * {@code scheduling.application.AgendaSedeIniciador}: crear la fila dentro de la transaccion que
 * despues la bloquea produce un <b>deadlock</b> entre las primeras N escrituras concurrentes, y
 * atrapar la excepcion no alcanza porque no des-marca la transaccion — Spring lanza
 * {@code UnexpectedRollbackException} al commitear, con un mensaje que no nombra la causa.
 * <b>Este repositorio ya lo pago cuatro veces</b> (agenda_sede, consultorio_calendario,
 * sesion_numerador, autorizacion_persona_lock). No se paga una quinta.
 *
 * <p>{@code REQUIRES_NEW} hace que el INSERT se commitee de inmediato y libere su lock, en vez de
 * retenerlo hasta el final de la operacion que lo llamo.
 *
 * <h2>Los dos metodos, y por que no es uno solo</h2>
 *
 * <p>Son dos numeradores distintos con dos claves distintas —la historia y el caso— y dos momentos
 * distintos: el de la historia se asegura al <b>abrir</b> un caso, y el del caso al <b>cerrar</b>
 * una sesion, desde otro modulo y por el spi. Fusionarlos escondería que son dos locks, que es
 * justamente lo que hay que tener presente: el cierre de sesion es la primera transaccion de este
 * sistema que toma dos numeradores, y su orden es fijo (challenge seccion 8.4).
 */
@Component
public class CasoNumeradorIniciador {

	private final CasoNumeradorPort casos;
	private final CasoSesionNumeradorPort sesionesDelCaso;

	public CasoNumeradorIniciador(CasoNumeradorPort casos, CasoSesionNumeradorPort sesionesDelCaso) {
		this.casos = casos;
		this.sesionesDelCaso = sesionesDelCaso;
	}

	/** La secuencia de casos de esa historia clinica. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void asegurarCasos(long organizationId, long historiaClinicaId) {
		casos.crearSiFalta(organizationId, historiaClinicaId);
	}

	/** La secuencia de sesiones de ese caso. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void asegurarSesionesDelCaso(long organizationId, long casoId) {
		sesionesDelCaso.crearSiFalta(organizationId, casoId);
	}
}
