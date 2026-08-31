package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.spi.TurnoDirectory;
import com.akine.scheduling.spi.TurnoSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Adaptador de {@link TurnoDirectory}. Traduccion de forma y nada mas. */
@Component
public class SchedulingTurnoDirectory implements TurnoDirectory {

	private final TurnoRepository turnos;

	public SchedulingTurnoDirectory(TurnoRepository turnos) {
		this.turnos = turnos;
	}

	@Override
	public Optional<TurnoSnapshot> find(long organizationId, long consultorioId, long turnoId) {
		return turnos.findByIdInScope(organizationId, consultorioId, turnoId)
				.map(SchedulingTurnoDirectory::proyectar);
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
