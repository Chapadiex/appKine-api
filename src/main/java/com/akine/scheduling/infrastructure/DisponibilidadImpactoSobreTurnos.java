package com.akine.scheduling.infrastructure;

import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.person.spi.PacienteDirectory;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.resource.spi.DisponibilidadImpactProbe;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementacion de {@code resource.spi.DisponibilidadImpactProbe} sobre la tabla {@code turno}
 * (paquete E-1, reescrita en A-11).
 *
 * <h2>Que devuelve, y que NO decide</h2>
 *
 * <p>Los turnos pendientes de la sede —de un profesional, o de todos si la pregunta viene de una
 * excepcion de alcance sede— que <b>empiezan</b> dentro de la ventana que {@code resource}
 * calcula. No decide cuales quedan en conflicto: eso lo hace {@code resource} comparando la
 * disponibilidad efectiva antes y despues del cambio, porque es el unico modulo que conoce las
 * reglas. Hasta A-11 esta clase devolvia la cuenta de la ventana, y {@code resource} la informaba
 * como cota superior; ver el javadoc de la interfaz.
 *
 * <p>Desde la agenda operativa devuelve tambien la persona y el estado de cada turno, que estan en
 * la misma fila, y resuelve los nombres <b>solo</b> de los afectados que {@code resource} va a mostrar
 * (checklist de agenda §6.2): una consulta para pacientes y dos para profesionales, nunca una por turno.
 */
@Component
public class DisponibilidadImpactoSobreTurnos implements DisponibilidadImpactProbe {

	private final TurnoRepositoryPort turnos;
	private final PacienteDirectory pacientes;
	private final ConsultorioMembershipDirectory memberships;
	private final AccountIdentityDirectory identidades;

	/** Sin directorios de nombres: los dobles de test que solo miran los pendientes. */
	public DisponibilidadImpactoSobreTurnos(TurnoRepositoryPort turnos) {
		this(turnos, null, null, null);
	}

	@Autowired
	public DisponibilidadImpactoSobreTurnos(
			TurnoRepositoryPort turnos,
			PacienteDirectory pacientes,
			ConsultorioMembershipDirectory memberships,
			AccountIdentityDirectory identidades) {

		this.turnos = turnos;
		this.pacientes = pacientes;
		this.memberships = memberships;
		this.identidades = identidades;
	}

	@Override
	public List<TurnoPendiente> pendientesEn(
			long organizationId, long consultorioId, Long membershipId, Instant desde, Instant hasta) {

		if (!hasta.isAfter(desde)) {
			return List.of();
		}
		List<Turno> pendientes = membershipId == null
				? turnos.findPendientesEnLaSede(organizationId, consultorioId, desde, hasta)
				: turnos.findPendientesDelProfesionalEnLaSede(
						organizationId, consultorioId, membershipId, desde, hasta);
		return pendientes.stream()
				.map(turno -> new TurnoPendiente(
						turno.getId(), turno.getProfesionalMembershipId(), turno.getInicio(), turno.getFin(),
						turno.getPersonaId(), turno.getEstado().name()))
				.toList();
	}

	@Override
	public NombresDeTurnos nombres(
			long organizationId, Collection<Long> personaIds, Collection<Long> membershipIds) {

		if (pacientes == null) {
			return NombresDeTurnos.NINGUNO;
		}
		Map<Long, String> personas = new HashMap<>();
		if (!personaIds.isEmpty()) {
			pacientes.findAll(organizationId, personaIds)
					.forEach((id, paciente) -> personas.put(id, paciente.nombreCompleto()));
		}
		Map<Long, String> profesionales = new HashMap<>();
		if (!membershipIds.isEmpty()) {
			Map<Long, ConsultorioMembershipSnapshot> vinculos = memberships.findTodas(organizationId, membershipIds);
			Map<Long, AccountIdentity> cuentas = identidades.identidadesDe(
					vinculos.values().stream().map(ConsultorioMembershipSnapshot::accountId).toList());
			vinculos.forEach((id, vinculo) -> {
				AccountIdentity cuenta = cuentas.get(vinculo.accountId());
				if (cuenta != null && cuenta.nombreCompleto() != null) {
					profesionales.put(id, cuenta.nombreCompleto());
				}
			});
		}
		return new NombresDeTurnos(personas, profesionales);
	}
}
