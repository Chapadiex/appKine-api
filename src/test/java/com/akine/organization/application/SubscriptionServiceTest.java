package com.akine.organization.application;

import com.akine.organization.domain.Subscription;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.SubscriptionTransition;
import com.akine.organization.domain.exception.InvalidSubscriptionTransitionException;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.domain.port.SubscriptionTransitionRepositoryPort;
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

import java.util.List;
import java.util.Optional;

import static com.akine.organization.application.Fixtures.ORG_ID;
import static com.akine.organization.application.Fixtures.PLAN_BASICO_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Transiciones de estado de la suscripcion (RF-M01-003).
 *
 * <p>El par de tests que sostiene la regla es "la valida persiste y audita" contra "la invalida
 * no persiste NI audita". Un rechazo que igual deja una fila de historico o un evento de
 * auditoria le miente a quien despues investiga por que un cliente dejo de operar.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

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

	/**
	 * Toda transicion lee la suscripcion CON BLOQUEO: es lo que evita el deadlock S -&gt; X
	 * entre el UPDATE diferido y el INSERT del historico, y lo que hace que la comparacion de
	 * {@code expectedStatus} no sea una carrera. Si alguien vuelve a la lectura sin bloqueo,
	 * estos tests fallan y ahi esta el aviso.
	 */
	private void tenantVigenteCon(Subscription subscription) {
		given(organizationService.requireActive(ORG_ID)).willReturn(Fixtures.organizacion());
		given(subscriptionRepository.findByOrganizationIdForUpdate(ORG_ID))
				.willReturn(Optional.of(subscription));
	}

	private void planLegible() {
		given(planCatalogService.requireById(PLAN_BASICO_ID))
				.willReturn(Fixtures.plan(PLAN_BASICO_ID, "BASICO"));
		given(planCatalogService.limitsOf(PLAN_BASICO_ID)).willReturn(List.of());
	}

	@Test
	@DisplayName("Una transicion valida persiste el estado, agrega historico y audita")
	void transicion_valida_persiste_y_audita() {
		Subscription subscription = Fixtures.suscripcionActiva(PLAN_BASICO_ID);
		tenantVigenteCon(subscription);
		planLegible();

		SubscriptionView vista = subscriptionService.transition(
				ORG_ID, SubscriptionStatus.SUSPENDIDA, SubscriptionStatus.ACTIVA,
				"Falta de pago del periodo", Fixtures.ACCOUNT_ID);

		assertThat(vista.status()).isEqualTo(SubscriptionStatus.SUSPENDIDA);
		assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDIDA);
		verify(subscriptionRepository).save(subscription);

		ArgumentCaptor<SubscriptionTransition> historico =
				ArgumentCaptor.forClass(SubscriptionTransition.class);
		verify(transitionRepository).save(historico.capture());
		assertThat(historico.getValue().getFromStatus()).isEqualTo(SubscriptionStatus.ACTIVA);
		assertThat(historico.getValue().getToStatus()).isEqualTo(SubscriptionStatus.SUSPENDIDA);
		assertThat(historico.getValue().getReason()).isEqualTo("Falta de pago del periodo");

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo("SUBSCRIPTION_TRANSITIONED");
		assertThat(auditoria.getValue().previousState()).isEqualTo("ACTIVA");
		assertThat(auditoria.getValue().newState()).isEqualTo("SUSPENDIDA");
		assertThat(auditoria.getValue().organizationId()).isEqualTo(ORG_ID);
	}

	@Test
	@DisplayName("Una transicion invalida lanza y no deja ni historico ni auditoria")
	void transicion_invalida_no_persiste_ni_audita() {
		Subscription cancelada = Fixtures.suscripcionActiva(PLAN_BASICO_ID);
		cancelada.transitionTo(SubscriptionStatus.CANCELADA);
		tenantVigenteCon(cancelada);

		assertThatThrownBy(() -> subscriptionService.transition(
				ORG_ID, SubscriptionStatus.ACTIVA, null, null, Fixtures.ACCOUNT_ID))
				.isInstanceOf(InvalidSubscriptionTransitionException.class);

		assertThat(cancelada.getStatus()).isEqualTo(SubscriptionStatus.CANCELADA);
		verify(subscriptionRepository, never()).save(any());
		verify(transitionRepository, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Repetir la transicion ya aplicada se rechaza: un reintento no puede tener efecto")
	void repetir_el_estado_actual_se_rechaza() {
		tenantVigenteCon(Fixtures.suscripcionActiva(PLAN_BASICO_ID));

		assertThatThrownBy(() -> subscriptionService.transition(
				ORG_ID, SubscriptionStatus.ACTIVA, null, null, Fixtures.ACCOUNT_ID))
				.isInstanceOf(InvalidSubscriptionTransitionException.class);

		verify(transitionRepository, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Suspender sin motivo se rechaza antes de tocar nada")
	void suspender_exige_motivo() {
		tenantVigenteCon(Fixtures.suscripcionActiva(PLAN_BASICO_ID));

		assertThatThrownBy(() -> subscriptionService.transition(
				ORG_ID, SubscriptionStatus.SUSPENDIDA, null, "   ", Fixtures.ACCOUNT_ID))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("motivo");

		verify(subscriptionRepository, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Si el estado actual no es el esperado se responde conflicto, no se sobrescribe")
	void estado_esperado_desactualizado_es_conflicto() {
		tenantVigenteCon(Fixtures.suscripcionActiva(PLAN_BASICO_ID));

		assertThatThrownBy(() -> subscriptionService.transition(
				ORG_ID, SubscriptionStatus.CANCELADA, SubscriptionStatus.SUSPENDIDA,
				"Baja pedida por el cliente", Fixtures.ACCOUNT_ID))
				.isInstanceOf(OptimisticLockingFailureException.class);

		verify(subscriptionRepository, never()).save(any());
		verifyNoInteractions(auditTrail);
	}
}
