package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.CasoProfesional;
import com.akine.clinical.domain.PlanTratamiento;
import com.akine.clinical.domain.PlanTratamientoVersion;
import com.akine.clinical.domain.RolEnCaso;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoProfesionalRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanItemRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoVersionRepositoryPort;
import com.akine.clinical.spi.ContextoClinicoDeAgenda;
import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.clinical.spi.HistoriaClinicaSnapshot;
import com.akine.clinical.spi.RealizadoEnElCasoProbe;
import com.akine.clinical.spi.RealizadoPorOferta;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * La implementacion de {@link ContextoClinicoDeAgenda}, del lado de {@code clinical}.
 *
 * <p><b>No autoriza ni audita</b>, igual que {@code ClinicalCasoDirectory}: quien llama evaluo su
 * permiso de agenda y lo que sale de aca no es contenido clinico. El dia que este spi devuelva
 * diagnostico u objetivos esa afirmacion deja de ser cierta.
 */
@Component
public class ClinicalContextoDeAgenda implements ContextoClinicoDeAgenda {

	private final CasoClinicoRepositoryPort casos;
	private final CasoProfesionalRepositoryPort equipo;
	private final PlanTratamientoRepositoryPort planes;
	private final PlanTratamientoVersionRepositoryPort versiones;
	private final PlanItemRepositoryPort items;
	private final HistoriaClinicaDirectory historias;
	private final RealizadoEnElCasoProbe realizado;

	public ClinicalContextoDeAgenda(
			CasoClinicoRepositoryPort casos,
			CasoProfesionalRepositoryPort equipo,
			PlanTratamientoRepositoryPort planes,
			PlanTratamientoVersionRepositoryPort versiones,
			PlanItemRepositoryPort items,
			HistoriaClinicaDirectory historias,
			RealizadoEnElCasoProbe realizado) {

		this.casos = casos;
		this.equipo = equipo;
		this.planes = planes;
		this.versiones = versiones;
		this.items = items;
		this.historias = historias;
		this.realizado = realizado;
	}

	@Override
	@Transactional(readOnly = true)
	public List<CasoAgendable> casosActivosDe(long organizationId, long personaId) {
		Optional<HistoriaClinicaSnapshot> historia = historias.find(organizationId, personaId);
		if (historia.isEmpty()) {
			// Sin historia no hay casos: la primera consulta de un paciente nuevo es el caso normal.
			return List.of();
		}
		return casos.buscarDeHistoria(organizationId, historia.get().id(), true).stream()
				.map(caso -> proyectar(organizationId, caso, personaId))
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CasoAgendable> caso(long organizationId, long casoId) {
		return casos.findByIdAndOrganizationId(casoId, organizationId)
				.map(caso -> proyectar(organizationId, caso, personaDe(organizationId, caso)));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PlanAgendable> plan(long organizationId, long planId) {
		return planes.findByIdAndOrganizationId(planId, organizationId).flatMap(plan -> casos
				.findByIdAndOrganizationId(plan.getCasoClinicoId(), organizationId)
				.map(caso -> proyectar(organizationId, plan, personaDe(organizationId, caso))));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, Integer> numerosDeCaso(long organizationId, Collection<Long> casoIds) {
		Map<Long, Integer> numeros = new HashMap<>();
		for (Long casoId : casoIds) {
			casos.findByIdAndOrganizationId(casoId, organizationId)
					.ifPresent(caso -> numeros.put(casoId, caso.getNumeroCaso()));
		}
		return numeros;
	}

	private CasoAgendable proyectar(long organizationId, CasoClinico caso, long personaId) {
		Long responsable = equipo.buscarDeCaso(organizationId, caso.getId(), true).stream()
				.filter(participacion -> participacion.getRol() == RolEnCaso.RESPONSABLE)
				.map(CasoProfesional::getProfesionalMembershipId)
				.findFirst()
				.orElse(null);
		List<PlanAgendable> activos = planes.buscarDeCaso(organizationId, caso.getId(), true).stream()
				.filter(PlanTratamiento::estaActivo)
				.map(plan -> proyectar(organizationId, plan, personaId))
				.toList();
		return new CasoAgendable(caso.getId(), personaId, caso.getNumeroCaso(), caso.getOfertaId(),
				caso.estaActivo(), responsable, activos);
	}

	private PlanAgendable proyectar(long organizationId, PlanTratamiento plan, long personaId) {
		Map<Long, Integer> realizadas = realizado
				.contarPorOfertaEnElCasoDesde(organizationId, plan.getCasoClinicoId(), plan.getActivadoEn()).stream()
				.collect(Collectors.toMap(RealizadoPorOferta::ofertaId, RealizadoPorOferta::realizadas));
		Optional<PlanTratamientoVersion> vigente = versiones.buscarVigente(organizationId, plan.getId());
		List<ItemAgendable> deLaVersion = vigente
				.map(version -> items.buscarDeVersion(organizationId, version.getId()).stream()
						.map(item -> new ItemAgendable(
								item.getOfertaId(),
								item.getOfertaConsultorioId(),
								item.getOfertaNombre(),
								item.getCantidadPlanificada(),
								realizadas.getOrDefault(item.getOfertaId(), 0)))
						.toList())
				.orElse(List.of());
		return new PlanAgendable(
				plan.getId(),
				plan.getCasoClinicoId(),
				personaId,
				plan.getNumeroPlan(),
				plan.getEstado().name(),
				vigente.map(PlanTratamientoVersion::getFrecuenciaSemanal).orElse(null),
				vigente.map(PlanTratamientoVersion::getDuracionSemanas).orElse(null),
				deLaVersion);
	}

	private long personaDe(long organizationId, CasoClinico caso) {
		return historias.findPorId(organizationId, caso.getHistoriaClinicaId())
				.map(HistoriaClinicaSnapshot::personaId)
				.orElseThrow(() -> new IllegalStateException(
						"El caso " + caso.getId() + " cuelga de una historia que no existe"));
	}
}
