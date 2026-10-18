package com.akine.encounter.infrastructure;

import com.akine.encounter.spi.CierresClinicosDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Adaptador de {@link CierresClinicosDirectory} sobre {@code sesion} (AKINE-09.01b). */
@Component
public class EncounterCierresClinicosDirectory implements CierresClinicosDirectory {

	private final SesionRepository sesiones;

	public EncounterCierresClinicosDirectory(SesionRepository sesiones) {
		this.sesiones = sesiones;
	}

	@Override
	@Transactional(readOnly = true)
	public List<CierreClinico> altasDelPeriodo(
			long organizationId, long consultorioId, Instant desde, Instant hasta) {
		return proyectar(sesiones.altasDelPeriodoEnElReporte(organizationId, consultorioId, desde, hasta));
	}

	@Override
	@Transactional(readOnly = true)
	public List<CierreClinico> cerradasDespuesDe(
			long organizationId, long consultorioId, Collection<Long> historiaClinicaIds, Instant desde) {
		if (historiaClinicaIds.isEmpty()) {
			return List.of();
		}
		return proyectar(sesiones.cerradasDespuesDeEnElReporte(
				organizationId, consultorioId, Set.copyOf(historiaClinicaIds), desde));
	}

	private static List<CierreClinico> proyectar(List<Object[]> filas) {
		return filas.stream()
				.map(f -> new CierreClinico(
						((Number) f[0]).longValue(),
						((Number) f[1]).longValue(),
						Instant.EPOCH.plus(((Number) f[2]).longValue(), ChronoUnit.MICROS)))
				.toList();
	}
}
