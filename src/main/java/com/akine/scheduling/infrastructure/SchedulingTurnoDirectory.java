package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.ModalidadRecepcion;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.RecepcionRepositoryPort;
import com.akine.scheduling.spi.TurnoDirectory;
import com.akine.scheduling.spi.TurnoSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Adaptador de {@link TurnoDirectory}. Traduccion de forma y nada mas. */
@Component
public class SchedulingTurnoDirectory implements TurnoDirectory {

	private final TurnoRepository turnos;
	private final RecepcionRepositoryPort recepciones;

	public SchedulingTurnoDirectory(TurnoRepository turnos, RecepcionRepositoryPort recepciones) {
		this.turnos = turnos;
		this.recepciones = recepciones;
	}

	/**
	 * La recepcion vigente se busca por organizacion y turno —como la indexa
	 * {@code uk_recepcion_turno_vigente}— y la sede se exige aparte: un turno de otra sede no
	 * responde {@code true} aunque el id coincida.
	 */
	@Override
	public boolean atendidoComoParticular(long organizationId, long consultorioId, long turnoId) {
		return recepciones.findVigente(organizationId, turnoId)
				.filter(recepcion -> recepcion.getConsultorioId() == consultorioId)
				.map(recepcion -> recepcion.getModalidad() == ModalidadRecepcion.PARTICULAR)
				.orElse(false);
	}

	@Override
	public Optional<TurnoSnapshot> find(long organizationId, long consultorioId, long turnoId) {
		return turnos.findByIdInScope(organizationId, consultorioId, turnoId)
				.map(SchedulingTurnoDirectory::proyectar);
	}

	@Override
	public boolean existeTurnoVivoDeProfesionalConPersona(
			long organizationId, long consultorioId, long profesionalMembershipId, long personaId) {
		return turnos.existeTurnoVivoDeProfesionalConPersona(
				organizationId, consultorioId, profesionalMembershipId, personaId);
	}

	private static TurnoSnapshot proyectar(Turno turno) {
		return new TurnoSnapshot(
				turno.getId(),
				turno.getOrganizationId(),
				turno.getConsultorioId(),
				turno.getOfertaId(),
				turno.getPersonaId(),
				turno.getProfesionalMembershipId(),
				turno.getEspacioId(),
				turno.getInicio(),
				turno.getFin(),
				turno.getEstado().name(),
				turno.estaVivo());
	}
}
