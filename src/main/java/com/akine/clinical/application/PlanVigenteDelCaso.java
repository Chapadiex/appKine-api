package com.akine.clinical.application;

import com.akine.clinical.domain.PlanTratamiento;
import com.akine.clinical.domain.PlanTratamientoVersion;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoVersionRepositoryPort;
import com.akine.clinical.spi.PlanVigenteDirectory;
import com.akine.clinical.spi.PlanVigenteSnapshot;
import com.akine.clinical.spi.PlanVigenteSnapshot.ItemDelPlan;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * {@link PlanVigenteDirectory} sobre el mismo calculo de avance que la pantalla del plan.
 *
 * <p>En {@code application} porque reusa {@link PlanTratamientoService#calcularAvance}, que es
 * privado del paquete: el avance tiene una sola implementacion.
 */
@Component
class PlanVigenteDelCaso implements PlanVigenteDirectory {

	private final PlanTratamientoRepositoryPort planes;
	private final PlanTratamientoVersionRepositoryPort versiones;
	private final PlanTratamientoService planService;

	PlanVigenteDelCaso(
			PlanTratamientoRepositoryPort planes,
			PlanTratamientoVersionRepositoryPort versiones,
			PlanTratamientoService planService) {
		this.planes = planes;
		this.versiones = versiones;
		this.planService = planService;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PlanVigenteSnapshot> delCaso(long organizationId, long casoId) {
		Optional<PlanTratamiento> encontrado = planes.buscarQueOcupaElLugarDelCaso(organizationId, casoId);
		if (encontrado.isEmpty()) {
			return Optional.empty();
		}
		PlanTratamiento plan = encontrado.get();
		Optional<PlanTratamientoVersion> vigente = versiones.buscarVigente(organizationId, plan.getId());
		if (vigente.isEmpty()) {
			return Optional.empty();
		}
		PlanTratamientoVersion version = vigente.get();
		AvanceDelPlanView avance = planService.calcularAvance(plan, version);

		return Optional.of(new PlanVigenteSnapshot(
				plan.getId(),
				plan.getEstado().name(),
				plan.getActivadoEn(),
				version.getNumeroVersion(),
				version.getRegistradaEn(),
				version.getObjetivos(),
				version.getIndicaciones(),
				version.getFrecuenciaSemanal(),
				version.getDuracionSemanas(),
				avance.items().stream()
						.map(item -> new ItemDelPlan(item.ofertaId(), item.ofertaNombre(),
								item.planificadas(), item.realizadas(), item.canceladas(),
								item.completo()))
						.toList(),
				avance.completo()));
	}
}
