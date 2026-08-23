package com.akine.organization.application;

import com.akine.organization.domain.Subscription;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.SubscriptionTransition;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.SubscriptionSuspendedException;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.domain.port.SubscriptionTransitionRepositoryPort;
import com.akine.organization.spi.LimitCode;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.akine.organization.application.Fixtures.ACCOUNT_ID;
import static com.akine.organization.application.Fixtures.ORG_ID;
import static com.akine.organization.application.Fixtures.PLAN_BASICO_ID;
import static com.akine.organization.application.Fixtures.PLAN_PRO_ID;
import static com.akine.organization.application.Fixtures.SUBSCRIPTION_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Cambio de plan, lectura de la suscripcion e historico (RF-M01-002, RN-M01-004).
 *
 * <p>La regla que este test blinda es que un downgrade NO toca datos existentes: si el uso
 * actual ya excede el limite del plan nuevo, el cambio se aplica igual y lo excedido viaja
 * como AVISO. Convertir ese aviso en un rechazo —o peor, en una desactivacion— dejaria a un
 * cliente sin acceso a informacion clinica que ya era suya.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionPlanChangeTest {

	@Mock
	private SubscriptionRepositoryPort subscriptionRepository;

	@Mock
	private SubscriptionTransitionRepositoryPort transitionRepository;

	@Mock
	private OrganizationService organizationService;

	@Mock
	private PlanCatalogService planCatalogService;

	@Mock
	private TenantUsageCounter usageCounter;

	@Mock
	private AuditTrail auditTrail;

	@InjectMocks
	private SubscriptionService subscriptionService;

	private void tenantVigenteCon(Subscription subscription) {
		given(organizationService.requireActive(ORG_ID)).willReturn(Fixtures.organizacion());
		given(subscriptionRepository.findByOrganizationId(ORG_ID))
				.willReturn(Optional.of(subscription));
	}

	private void planContratable(String code, long planId) {
		given(planCatalogService.requireContractable(code))
				.willReturn(Fixtures.plan(planId, code));
	}

	private void planLegible(long planId, String code) {
		given(planCatalogService.requireById(planId)).willReturn(Fixtures.plan(planId, code));
	}

	// ---------------------------------------------------------------------- changePlan

	@Test
	@DisplayName("Cambiar de plan persiste el plan nuevo, agrega historico y audita")
	void cambiar_de_plan_persiste_y_audita() {
		Subscription subscription = Fixtures.suscripcionActiva(PLAN_BASICO_ID);
		tenantVigenteCon(subscription);
		planContratable("PRO", PLAN_PRO_ID);
		planLegible(PLAN_BASICO_ID, "BASICO");
		planLegible(PLAN_PRO_ID, "PRO");
		given(planCatalogService.limitsOf(PLAN_PRO_ID)).willReturn(List.of());

		PlanChangeOutcome resultado =
				subscriptionService.changePlan(ORG_ID, "PRO", 0L, ACCOUNT_ID);

		assertThat(resultado.applied()).isTrue();
		assertThat(resultado.warnings()).isEmpty();
		assertThat(resultado.subscription().planCode()).isEqualTo("PRO");
		assertThat(subscription.getPlanId()).isEqualTo(PLAN_PRO_ID);
		verify(subscriptionRepository).save(subscription);

		// El historico registra un cambio de plan: los dos estados son ACTIVA y lo que cambia
		// son los planes.
		ArgumentCaptor<SubscriptionTransition> fila =
				ArgumentCaptor.forClass(SubscriptionTransition.class);
		verify(transitionRepository).save(fila.capture());
		assertThat(fila.getValue().getFromStatus()).isEqualTo(SubscriptionStatus.ACTIVA);
		assertThat(fila.getValue().getToStatus()).isEqualTo(SubscriptionStatus.ACTIVA);
		assertThat(fila.getValue().getFromPlanId()).isEqualTo(PLAN_BASICO_ID);
		assertThat(fila.getValue().getToPlanId()).isEqualTo(PLAN_PRO_ID);

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		assertThat(entrada.getValue().eventType())
				.isEqualTo(AuditEvents.SUBSCRIPTION_PLAN_CHANGED);
		assertThat(entrada.getValue().details())
				.containsEntry("fromPlan", "BASICO")
				.containsEntry("toPlan", "PRO")
				.containsEntry("limitesExcedidos", "0");
	}

	@Test
	@DisplayName("Un downgrade que ya excede un limite se aplica igual y devuelve el aviso")
	void un_downgrade_excedido_avisa_pero_no_rechaza() {
		Subscription subscription = Fixtures.suscripcionActiva(PLAN_PRO_ID);
		tenantVigenteCon(subscription);
		planContratable("BASICO", PLAN_BASICO_ID);
		planLegible(PLAN_PRO_ID, "PRO");
		planLegible(PLAN_BASICO_ID, "BASICO");
		given(planCatalogService.limitsOf(PLAN_BASICO_ID)).willReturn(List.of(
				new PlanLimitView(LimitCode.MAX_CONSULTORIOS, 1),
				new PlanLimitView(LimitCode.MAX_MIEMBROS_ACTIVOS, null)));
		given(usageCounter.count(LimitCode.MAX_CONSULTORIOS, ORG_ID)).willReturn(4L);
		given(usageCounter.count(LimitCode.MAX_MIEMBROS_ACTIVOS, ORG_ID)).willReturn(99L);

		PlanChangeOutcome resultado =
				subscriptionService.changePlan(ORG_ID, "BASICO", 0L, ACCOUNT_ID);

		// RN-M01-004: el downgrade no borra, no desactiva y no marca invalido nada. El efecto
		// es prospectivo y el cliente necesita saberlo para poder actuar.
		assertThat(resultado.applied()).isTrue();
		assertThat(resultado.warnings())
				.containsExactly(new LimitUsageView(LimitCode.MAX_CONSULTORIOS, 1, 4L));
		// Un limite sin tope nunca genera aviso, por alto que sea el uso.
		assertThat(resultado.warnings())
				.noneMatch(aviso -> aviso.code() == LimitCode.MAX_MIEMBROS_ACTIVOS);
		assertThat(resultado.subscription().limits()).hasSize(2);
	}

	@Test
	@DisplayName("Cambiar al plan que ya estaba contratado no genera historico ni auditoria")
	void cambiar_al_mismo_plan_no_tiene_efecto() {
		Subscription subscription = Fixtures.suscripcionActiva(PLAN_BASICO_ID);
		tenantVigenteCon(subscription);
		planContratable("BASICO", PLAN_BASICO_ID);
		planLegible(PLAN_BASICO_ID, "BASICO");
		given(planCatalogService.limitsOf(PLAN_BASICO_ID)).willReturn(List.of());

		PlanChangeOutcome resultado =
				subscriptionService.changePlan(ORG_ID, "BASICO", 0L, ACCOUNT_ID);

		assertThat(resultado.applied()).isFalse();
		verify(subscriptionRepository, never()).save(any());
		verifyNoInteractions(transitionRepository, auditTrail);
	}

	@Test
	@DisplayName("Cambiar de plan con una version desactualizada es conflicto y no persiste nada")
	void version_vieja_es_conflicto() {
		Subscription subscription = Fixtures.suscripcionActiva(PLAN_BASICO_ID);
		tenantVigenteCon(subscription);
		planContratable("PRO", PLAN_PRO_ID);

		assertThatThrownBy(() -> subscriptionService.changePlan(ORG_ID, "PRO", 9L, ACCOUNT_ID))
				.isInstanceOf(OptimisticLockingFailureException.class);

		assertThat(subscription.getPlanId()).isEqualTo(PLAN_BASICO_ID);
		verify(subscriptionRepository, never()).save(any());
		verifyNoInteractions(transitionRepository, auditTrail);
	}

	@Test
	@DisplayName("Con la suscripcion suspendida no se puede cambiar de plan")
	void suspendida_no_cambia_de_plan() {
		Subscription subscription =
				Fixtures.suscripcionEn(PLAN_BASICO_ID, SubscriptionStatus.SUSPENDIDA);
		tenantVigenteCon(subscription);
		planContratable("PRO", PLAN_PRO_ID);
		planLegible(PLAN_BASICO_ID, "BASICO");

		assertThatThrownBy(() -> subscriptionService.changePlan(ORG_ID, "PRO", 0L, ACCOUNT_ID))
				.isInstanceOf(SubscriptionSuspendedException.class);

		verifyNoInteractions(transitionRepository, auditTrail);
	}

	// ------------------------------------------------------------------------- lectura

	@Test
	@DisplayName("La lectura devuelve plan, consumo y las transiciones posibles desde el backend")
	void la_lectura_devuelve_el_consumo_y_los_destinos_posibles() {
		tenantVigenteCon(Fixtures.suscripcionActiva(PLAN_BASICO_ID));
		planLegible(PLAN_BASICO_ID, "BASICO");
		given(planCatalogService.limitsOf(PLAN_BASICO_ID)).willReturn(List.of(
				new PlanLimitView(LimitCode.MAX_CONSULTORIOS, 5)));
		given(usageCounter.count(LimitCode.MAX_CONSULTORIOS, ORG_ID)).willReturn(3L);

		SubscriptionView view = subscriptionService.find(ORG_ID);

		assertThat(view.id()).isEqualTo(SUBSCRIPTION_ID);
		assertThat(view.organizationId()).isEqualTo(ORG_ID);
		assertThat(view.planCode()).isEqualTo("BASICO");
		assertThat(view.planName()).isEqualTo("Plan BASICO");
		assertThat(view.status()).isEqualTo(SubscriptionStatus.ACTIVA);
		assertThat(view.startedAt()).isNotNull();
		assertThat(view.limits())
				.containsExactly(new LimitUsageView(LimitCode.MAX_CONSULTORIOS, 5, 3L));
		assertThat(view.limits().get(0).exceeded()).isFalse();
		// RNF-M01-008: la tabla de transiciones vive en el backend, no en la UI.
		assertThat(view.allowedTargets()).containsExactlyInAnyOrder(
				SubscriptionStatus.SUSPENDIDA, SubscriptionStatus.CANCELADA);
	}

	@Test
	@DisplayName("Un tenant sin fila de suscripcion se responde como inexistente")
	void sin_suscripcion_es_404() {
		given(organizationService.requireActive(ORG_ID)).willReturn(Fixtures.organizacion());
		given(subscriptionRepository.findByOrganizationId(ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> subscriptionService.find(ORG_ID))
				.isInstanceOf(OrganizationNotFoundException.class);
	}

	// ----------------------------------------------------------------------- historico

	@Test
	@DisplayName("El historico proyecta cada fila con los codigos de plan, no con los ids")
	void el_historico_proyecta_los_codigos_de_plan() {
		tenantVigenteCon(Fixtures.suscripcionActiva(PLAN_BASICO_ID));
		planLegible(PLAN_BASICO_ID, "BASICO");
		Instant momento = Instant.now();
		SubscriptionTransition fila = Fixtures.conId(new SubscriptionTransition(
				ORG_ID, SUBSCRIPTION_ID, null, SubscriptionStatus.ACTIVA,
				null, PLAN_BASICO_ID, null, null, momento), 100L);
		Pageable pagina = PageRequest.of(0, 20);
		given(transitionRepository.findAllByOrganizationIdAndSubscriptionIdOrderByOccurredAtDesc(
				ORG_ID, SUBSCRIPTION_ID, pagina))
				.willReturn(new PageImpl<>(List.of(fila), pagina, 1));

		Page<SubscriptionTransitionView> historico =
				subscriptionService.history(ORG_ID, pagina);

		assertThat(historico.getContent()).containsExactly(new SubscriptionTransitionView(
				100L, null, SubscriptionStatus.ACTIVA, null, "BASICO", null, null, momento));
	}
}
