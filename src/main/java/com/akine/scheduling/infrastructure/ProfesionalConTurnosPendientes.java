package com.akine.scheduling.infrastructure;

import com.akine.organization.spi.ColaboradorDesvinculacionProbe;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Implementacion de {@code organization.spi.ColaboradorDesvinculacionProbe} sobre la tabla
 * {@code turno} (paquete E-1): los turnos que quedarian sin profesional (RN-M05-004).
 *
 * <h2>Informa, no bloquea</h2>
 *
 * <p>RN-M05-004 pide que los turnos afectados queden <b>visibles para resolucion</b>, no que la
 * desvinculacion se impida. Por eso esta sonda no lanza nada: alimenta el analisis de impacto
 * ({@code GET .../desvinculacion-impacto}) y los detalles del evento de auditoria de la
 * revocacion. El javadoc de la interfaz lo dice en mayusculas y vale repetirlo: convertir esto en
 * un {@code 409} es una regla nueva con su propio RF, no un {@code throw} agregado aca.
 *
 * <h2>Por que va primero</h2>
 *
 * <p>{@code MembershipService} recorre las sondas y devuelve <b>la primera con contenido</b>, no
 * la suma —sumar turnos con bloques de disponibilidad da un numero de nada—. Conviven dos:
 * esta y {@code resource.infrastructure.ResourceDesvinculacionProbe}, que cuenta bloques y
 * excepciones de disponibilidad. Los turnos son lo que alguien tiene que reasignar con nombre y
 * apellido, y un bloque de horario sin profesional no deja a ningun paciente plantado, asi que
 * cuando hay turnos son ellos los que se muestran. {@link Order} lo fija; sin el, el orden lo
 * decidiria el classpath.
 *
 * <p>Alcance ORGANIZACION: la membership se indexa por organizacion y un profesional puede tener
 * turnos en mas de una sede del mismo centro. {@code accountId} no se usa: el turno guarda la
 * membership, no la cuenta.
 */
@Component
@Order(0)
public class ProfesionalConTurnosPendientes implements ColaboradorDesvinculacionProbe {

	/** En plural y en lenguaje del usuario, como pide la interfaz. */
	static final String TIPO = "turnos";

	private final TurnoRepositoryPort turnos;

	public ProfesionalConTurnosPendientes(TurnoRepositoryPort turnos) {
		this.turnos = turnos;
	}

	@Override
	public Impacto pendingWorkOn(long organizationId, long membershipId, long accountId, Instant at) {
		List<Turno> pendientes = turnos.findPendientesDelProfesional(organizationId, membershipId, at);
		if (pendientes.isEmpty()) {
			return Impacto.ninguno();
		}
		Instant primero = pendientes.stream().map(Turno::getInicio).min(Instant::compareTo).orElseThrow();
		return new Impacto(TIPO, pendientes.size(), primero);
	}
}
