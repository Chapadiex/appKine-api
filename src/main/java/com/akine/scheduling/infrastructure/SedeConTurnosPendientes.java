package com.akine.scheduling.infrastructure;

import com.akine.organization.spi.ConsultorioDeactivationProbe;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Implementacion de {@code organization.spi.ConsultorioDeactivationProbe} sobre la tabla
 * {@code turno} (paquete E-1): una sede con turnos pendientes no se da de baja (RN-M04-002).
 *
 * <h2>Por que existe recien ahora</h2>
 *
 * <p>02.01 declaro la costura y reservo {@code 409 consultorio-has-active-references} en el
 * contrato. F5 creo los turnos y nadie enchufo la sonda, asi que una sede con la agenda llena se
 * daba de baja con 200 y sus turnos quedaban apuntando a un lugar inactivo. Es la misma leccion
 * que {@link ReservaProbeSobreTurnos}: una costura vacia no avisa cuando le llega el momento.
 *
 * <h2>Que cuenta</h2>
 *
 * <p>Los turnos <b>pendientes</b> de la sede: vivos, en un estado que todavia compromete a alguien
 * y que no terminaron. Cancelado y ausente no cuentan; el turno en curso si. El predicado esta
 * escrito en {@code TurnoRepository#contarPendientesDeLaSede}.
 *
 * <p>No abre transaccion propia: se une a la de la baja, que ya bloqueo el tenant.
 *
 * <p><b>Que hacer con esos turnos sigue siendo decision abierta (D-8).</b> Esta sonda solo impide
 * la baja; no cancela nada en cascada, que ADR-0011 prohibe sin confirmacion y motivo por turno.
 */
@Component
public class SedeConTurnosPendientes implements ConsultorioDeactivationProbe {

	/** Vocabulario estable: viaja al cliente en el Problem Details. Sin datos de personas. */
	static final String TIPO = "turnos-futuros";

	private final TurnoRepositoryPort turnos;

	public SedeConTurnosPendientes(TurnoRepositoryPort turnos) {
		this.turnos = turnos;
	}

	@Override
	public ActiveReferences activeReferencesOn(long organizationId, long consultorioId, Instant at) {
		long pendientes = turnos.contarPendientesDeLaSede(organizationId, consultorioId, at);
		return pendientes == 0 ? ActiveReferences.ninguna() : new ActiveReferences(TIPO, pendientes);
	}
}
