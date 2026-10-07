package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.AutorizacionVinculadaACaso;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanItemRepositoryPort;
import com.akine.clinical.spi.AutorizacionesDelCaso;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementa {@link AutorizacionesDelCaso} sobre {@code plan_item.autorizacion_id} (AKINE C-4).
 */
@Component
public class ClinicalAutorizacionesDelCaso implements AutorizacionesDelCaso {

	private final CasoClinicoRepositoryPort casos;
	private final PlanItemRepositoryPort items;

	public ClinicalAutorizacionesDelCaso(CasoClinicoRepositoryPort casos, PlanItemRepositoryPort items) {
		this.casos = casos;
		this.items = items;
	}

	@Override
	@Transactional(readOnly = true)
	public Set<Long> deOtrosCasos(long organizationId, long casoId) {
		return casos.findByIdAndOrganizationId(casoId, organizationId)
				.map(caso -> excluidas(
						items.autorizacionesConCasoDeLaHistoria(
								organizationId, caso.getHistoriaClinicaId()),
						casoId))
				.orElse(Set.of());
	}

	private static Set<Long> excluidas(List<AutorizacionVinculadaACaso> vinculos, long casoId) {
		Set<Long> deEsteCaso = vinculos.stream()
				.filter(vinculo -> vinculo.casoId() == casoId)
				.map(AutorizacionVinculadaACaso::autorizacionId)
				.collect(Collectors.toSet());
		return vinculos.stream()
				.filter(vinculo -> vinculo.casoId() != casoId)
				.map(AutorizacionVinculadaACaso::autorizacionId)
				.filter(autorizacionId -> !deEsteCaso.contains(autorizacionId))
				.collect(Collectors.toUnmodifiableSet());
	}
}
