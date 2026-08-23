package com.akine.organization.application;

import com.akine.organization.domain.Plan;
import com.akine.organization.domain.PlanFeature;
import com.akine.organization.domain.port.PlanFeatureRepositoryPort;
import com.akine.organization.domain.port.PlanLimitRepositoryPort;
import com.akine.organization.domain.port.PlanRepositoryPort;
import com.akine.organization.spi.FeatureCode;
import com.akine.organization.spi.LimitCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.akine.organization.application.Fixtures.PLAN_BASICO_ID;
import static com.akine.organization.application.Fixtures.PLAN_PRO_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * Catalogo comercial de planes (RF-M01-004).
 *
 * <p>Lo que se fija aca: un plan retirado y uno inexistente se responden IGUAL, y el catalogo
 * se arma solo con las filas activas de {@code plan_limit} y {@code plan_feature} —las dadas
 * de baja siguen en la tabla para reconstruir la historia, pero no son la oferta de hoy.
 */
@ExtendWith(MockitoExtension.class)
class PlanCatalogServiceTest {

	@Mock
	private PlanRepositoryPort planRepository;

	@Mock
	private PlanLimitRepositoryPort planLimitRepository;

	@Mock
	private PlanFeatureRepositoryPort planFeatureRepository;

	@InjectMocks
	private PlanCatalogService planCatalogService;

	@Test
	@DisplayName("El catalogo devuelve cada plan con sus limites y sus funcionalidades")
	void el_catalogo_arma_cada_plan_con_lo_que_incluye() {
		given(planRepository.findAllByActiveTrueOrderByCodeAsc())
				.willReturn(List.of(Fixtures.plan(PLAN_BASICO_ID, "BASICO")));
		given(planLimitRepository.findAllByPlanIdAndActiveTrue(PLAN_BASICO_ID))
				.willReturn(List.of(
						Fixtures.limite(PLAN_BASICO_ID, LimitCode.MAX_CONSULTORIOS, 1),
						Fixtures.limite(PLAN_BASICO_ID, LimitCode.MAX_MIEMBROS_ACTIVOS, null)));
		given(planFeatureRepository.findAllByPlanIdAndActiveTrue(PLAN_BASICO_ID))
				.willReturn(List.of(
						new PlanFeature(PLAN_BASICO_ID, FeatureCode.NOTIFICACIONES_PACIENTE)));

		List<PlanView> catalogo = planCatalogService.catalog();

		assertThat(catalogo).containsExactly(new PlanView(
				PLAN_BASICO_ID, "BASICO", "Plan BASICO",
				List.of(
						new PlanLimitView(LimitCode.MAX_CONSULTORIOS, 1),
						new PlanLimitView(LimitCode.MAX_MIEMBROS_ACTIVOS, null)),
				List.of(FeatureCode.NOTIFICACIONES_PACIENTE)));
	}

	@Test
	@DisplayName("Un limite presente con tope nulo significa ilimitado, no ausente")
	void un_tope_nulo_es_ilimitado() {
		assertThat(new PlanLimitView(LimitCode.MAX_CONSULTORIOS, null).unlimited()).isTrue();
		assertThat(new PlanLimitView(LimitCode.MAX_CONSULTORIOS, 3).unlimited()).isFalse();
	}

	@Test
	@DisplayName("Un plan del catalogo se lee por su codigo")
	void un_plan_se_lee_por_codigo() {
		given(planRepository.findByCodeAndActiveTrue("PRO"))
				.willReturn(Optional.of(Fixtures.plan(PLAN_PRO_ID, "PRO")));
		given(planLimitRepository.findAllByPlanIdAndActiveTrue(PLAN_PRO_ID))
				.willReturn(List.of());
		given(planFeatureRepository.findAllByPlanIdAndActiveTrue(PLAN_PRO_ID))
				.willReturn(List.of());

		PlanView view = planCatalogService.plan("PRO");

		assertThat(view.code()).isEqualTo("PRO");
		assertThat(view.name()).isEqualTo("Plan PRO");
		assertThat(view.limits()).isEmpty();
		assertThat(view.features()).isEmpty();
	}

	@Test
	@DisplayName("Un plan retirado y uno inexistente se responden igual: 404 sin distinguirlos")
	void retirado_e_inexistente_son_el_mismo_404() {
		given(planRepository.findByCodeAndActiveTrue("RETIRADO")).willReturn(Optional.empty());

		assertThatThrownBy(() -> planCatalogService.plan("RETIRADO"))
				.isInstanceOf(PlanNotFoundException.class)
				.hasMessage("Plan no encontrado o no contratable")
				.extracting("planCode")
				.isEqualTo("RETIRADO");
	}

	@Test
	@DisplayName("Por id se resuelven tambien los planes retirados: una suscripcion vieja los usa")
	void por_id_se_resuelven_los_retirados() {
		Plan retirado = Fixtures.plan(PLAN_PRO_ID, "PRO_2023");
		given(planRepository.findById(PLAN_PRO_ID)).willReturn(Optional.of(retirado));

		assertThat(planCatalogService.requireById(PLAN_PRO_ID)).isSameAs(retirado);
	}

	@Test
	@DisplayName("Un id de plan inexistente tambien es 404, con el id como referencia")
	void un_id_inexistente_es_404() {
		given(planRepository.findById(PLAN_PRO_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> planCatalogService.requireById(PLAN_PRO_ID))
				.isInstanceOf(PlanNotFoundException.class)
				.extracting("planCode")
				.isEqualTo(String.valueOf(PLAN_PRO_ID));
	}

	@Test
	@DisplayName("Los limites vigentes de un plan se proyectan sin la entity")
	void los_limites_se_proyectan_sin_entity() {
		given(planLimitRepository.findAllByPlanIdAndActiveTrue(PLAN_BASICO_ID))
				.willReturn(List.of(
						Fixtures.limite(PLAN_BASICO_ID, LimitCode.MAX_CONSULTORIOS, 5)));

		assertThat(planCatalogService.limitsOf(PLAN_BASICO_ID))
				.containsExactly(new PlanLimitView(LimitCode.MAX_CONSULTORIOS, 5));
	}
}
