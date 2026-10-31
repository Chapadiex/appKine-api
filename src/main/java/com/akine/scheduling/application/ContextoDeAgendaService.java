package com.akine.scheduling.application;

import com.akine.clinical.spi.ContextoClinicoDeAgenda;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.CasoAgendable;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.person.spi.PacienteDirectory;
import com.akine.scheduling.application.ContextoDeAgendaView.CasoDeAgendaView;
import com.akine.scheduling.application.ContextoDeAgendaView.PlanDeAgendaView;
import com.akine.scheduling.application.ContextoDeAgendaView.SesionesDePrestacionView;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.PersonaNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * El contexto de una persona para programarle turnos. Exige {@code turno:read} y no {@code hc:read}:
 * recepcion programa sesiones sin leer la historia clinica, y por eso de aca no sale nada clinico.
 */
@Service
public class ContextoDeAgendaService {

	private final ContextoClinicoDeAgenda contexto;
	private final SesionesDelPlan sesionesDelPlan;
	private final ConsultorioDirectory consultorios;
	private final PacienteDirectory pacientes;
	private final OfertaDirectory ofertas;
	private final NombresDeAgenda nombres;
	private final TurnoRepositoryPort turnos;
	private final SoporteEnAgenda soporte;

	public ContextoDeAgendaService(
			ContextoClinicoDeAgenda contexto,
			SesionesDelPlan sesionesDelPlan,
			ConsultorioDirectory consultorios,
			PacienteDirectory pacientes,
			OfertaDirectory ofertas,
			NombresDeAgenda nombres,
			TurnoRepositoryPort turnos,
			SoporteEnAgenda soporte) {

		this.contexto = contexto;
		this.sesionesDelPlan = sesionesDelPlan;
		this.consultorios = consultorios;
		this.pacientes = pacientes;
		this.ofertas = ofertas;
		this.nombres = nombres;
		this.turnos = turnos;
		this.soporte = soporte;
	}

	/** @throws PersonaNotAccessibleException la persona no existe en el tenant (404) */
	@Transactional(readOnly = true)
	public ContextoDeAgendaView dePersona(OperatingActor actor, long consultorioId, long personaId) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La agenda requiere un contexto de trabajo activo");
		}
		long organizationId = actor.contextOrganizationId();
		consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
		soporte.exigir(actor, PermissionCodes.TURNO_READ, organizationId, consultorioId);
		pacientes.find(organizationId, personaId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));

		List<CasoAgendable> casos = contexto.casosActivosDe(organizationId, personaId);
		Map<Long, String> responsables = nombres.profesionales(organizationId,
				casos.stream().map(CasoAgendable::responsableMembershipId)
						.filter(Objects::nonNull).collect(Collectors.toSet()));
		Map<Long, OfertaSnapshot> ofertasDeCaso = ofertas.findTodas(organizationId, consultorioId,
				casos.stream().map(CasoAgendable::ofertaId).filter(Objects::nonNull).distinct().toList());

		List<CasoDeAgendaView> vistas = casos.stream()
				.map(caso -> new CasoDeAgendaView(
						caso.casoId(),
						caso.numeroCaso(),
						caso.ofertaId(),
						caso.ofertaId() == null || !ofertasDeCaso.containsKey(caso.ofertaId())
								? null : ofertasDeCaso.get(caso.ofertaId()).nombreComercial(),
						caso.responsableMembershipId(),
						caso.responsableMembershipId() == null ? null : responsables.get(caso.responsableMembershipId()),
						caso.planes().stream()
								.map(plan -> {
									var resumen = sesionesDelPlan.resumir(organizationId, plan);
									return new PlanDeAgendaView(
											plan.planId(),
											plan.numeroPlan(),
											plan.frecuenciaSemanal(),
											plan.duracionSemanas(),
											resumen.items().stream()
													.filter(item -> item.consultorioId() == consultorioId)
													.map(item -> new SesionesDePrestacionView(
															item.ofertaId(), item.ofertaNombre(), item.planificadas(),
															item.realizadas(), item.programadas(),
															item.ausenciasQueConsumen(), item.pendientes()))
													.toList());
								})
								.toList()))
				.toList();

		Long ultimoProfesional = turnos
				.findDeLaPersonaEnSede(organizationId, consultorioId, personaId, false, Instant.now(), 0, 1)
				.stream()
				.map(Turno::getProfesionalMembershipId)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null);

		return new ContextoDeAgendaView(personaId, ultimoProfesional, vistas);
	}
}
