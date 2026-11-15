package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.spi.TurnoDelCasoSnapshot;
import com.akine.scheduling.spi.TurnosDelCasoDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** {@link TurnosDelCasoDirectory} sobre la tabla {@code turno}, sin filtrar los cancelados. */
@Component
class TurnosDelCasoSobreTurnos implements TurnosDelCasoDirectory {

	private final TurnoRepository turnos;

	TurnosDelCasoSobreTurnos(TurnoRepository turnos) {
		this.turnos = turnos;
	}

	@Override
	@Transactional(readOnly = true)
	public List<TurnoDelCasoSnapshot> delCaso(long organizationId, long casoId) {
		return turnos.findDelCasoConCancelados(organizationId, casoId).stream()
				.map(TurnosDelCasoSobreTurnos::proyectar)
				.toList();
	}

	private static TurnoDelCasoSnapshot proyectar(Turno turno) {
		return new TurnoDelCasoSnapshot(
				turno.getId(),
				turno.getInicio(),
				turno.getEstado().name(),
				turno.getOrigenCancelacion() == null ? null : turno.getOrigenCancelacion().name());
	}
}
