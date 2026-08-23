package com.akine.organization.application;

import com.akine.organization.domain.Plan;
import com.akine.organization.domain.PlanFeature;
import com.akine.organization.domain.PlanLimit;
import com.akine.organization.domain.port.PlanFeatureRepositoryPort;
import com.akine.organization.domain.port.PlanLimitRepositoryPort;
import com.akine.organization.domain.port.PlanRepositoryPort;
import com.akine.organization.spi.FeatureCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Lectura del catalogo comercial: planes, sus limites y sus funcionalidades (RF-M01-004).
 *
 * <p>El catalogo es global de plataforma y no lleva {@code organization_id}: es una de las
 * excepciones documentadas a la regla de alcance tenant. Darle tenant duplicaria la oferta
 * entera por organizacion y haria imposible cambiarla en un solo lugar.
 *
 * <p>Solo lectura: la administracion del catalogo no esta en 01.01. Los planes los siembra la
 * migracion de seed.
 */
@Service
public class PlanCatalogService {

	private final PlanRepositoryPort planRepository;
	private final PlanLimitRepositoryPort planLimitRepository;
	private final PlanFeatureRepositoryPort planFeatureRepository;

	public PlanCatalogService(
			PlanRepositoryPort planRepository,
			PlanLimitRepositoryPort planLimitRepository,
			PlanFeatureRepositoryPort planFeatureRepository) {
		this.planRepository = planRepository;
		this.planLimitRepository = planLimitRepository;
		this.planFeatureRepository = planFeatureRepository;
	}

	/** Oferta vigente. Los planes retirados no aparecen: existen, pero no se contratan. */
	@Transactional(readOnly = true)
	public List<PlanView> catalog() {
		return planRepository.findAllByActiveTrueOrderByCodeAsc().stream()
				.map(this::toView)
				.toList();
	}

	/**
	 * Un plan contratable del catalogo.
	 *
	 * @throws PlanNotFoundException si no existe o ya fue retirado de la oferta
	 */
	@Transactional(readOnly = true)
	public PlanView plan(String code) {
		return toView(requireContractable(code));
	}

	/**
	 * Plan contratable como entity, para el uso interno del modulo.
	 *
	 * <p>Package-private a proposito: si fuera publico, la capa {@code api} podria llamarlo y
	 * quedaria dependiendo de una entity JPA, que es justo lo que
	 * {@code entities_no_salen_por_la_api} prohibe. La visibilidad hace cumplir la regla antes
	 * de que ArchUnit tenga que reportarla.
	 */
	Plan requireContractable(String code) {
		return planRepository.findByCodeAndActiveTrue(code)
				.orElseThrow(() -> new PlanNotFoundException(code));
	}

	/** Plan por id, incluidos los retirados: una suscripcion vieja puede referenciar uno. */
	Plan requireById(long planId) {
		return planRepository.findById(planId)
				.orElseThrow(() -> new PlanNotFoundException(String.valueOf(planId)));
	}

	/** Limites vigentes de un plan, para mostrar y para calcular avisos de downgrade. */
	List<PlanLimitView> limitsOf(long planId) {
		return planLimitRepository.findAllByPlanIdAndActiveTrue(planId).stream()
				.map(limit -> new PlanLimitView(limit.getLimitCode(), limit.getLimitValue()))
				.toList();
	}

	private PlanView toView(Plan plan) {
		List<PlanLimitView> limits = planLimitRepository
				.findAllByPlanIdAndActiveTrue(plan.getId()).stream()
				.map(this::toLimitView)
				.toList();
		List<FeatureCode> features = planFeatureRepository
				.findAllByPlanIdAndActiveTrue(plan.getId()).stream()
				.map(PlanFeature::getFeatureCode)
				.toList();
		return new PlanView(plan.getId(), plan.getCode(), plan.getName(), limits, features);
	}

	private PlanLimitView toLimitView(PlanLimit limit) {
		return new PlanLimitView(limit.getLimitCode(), limit.getLimitValue());
	}
}
