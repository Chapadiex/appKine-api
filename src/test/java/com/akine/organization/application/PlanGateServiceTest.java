package com.akine.organization.application;

import com.akine.organization.domain.Subscription;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.exception.FeatureNotAvailableException;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.PlanLimitExceededException;
import com.akine.organization.domain.exception.SubscriptionSuspendedException;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.domain.port.PlanFeatureRepositoryPort;
import com.akine.organization.domain.port.PlanLimitRepositoryPort;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.spi.FeatureCode;
import com.akine.organization.spi.LimitCode;
import com.akine.organization.spi.PlanDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.function.LongSupplier;

import static com.akine.organization.application.Fixtures.ORG_ID;
import static com.akine.organization.application.Fixtures.PLAN_BASICO_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Control de limites y features del plan (RF-M01-004).
 *
 * <p>El test que importa de verdad es
 * {@link #el_conteo_ocurre_despues_del_bloqueo_de_la_suscripcion()}: si alguien invierte esas
 * dos lineas, el limite se viola bajo concurrencia y ningun otro test lo nota, porque con un
 * solo hilo el resultado es identico.
 */
@ExtendWith(MockitoExtension.class)
class PlanGateServiceTest {

	@Mock
	private OrganizationRepositoryPort organizationRepository;

	@Mock
	private SubscriptionRepositoryPort subscriptionRepository;

	@Mock
	private PlanLimitRepositoryPort planLimitRepository;

	@Mock
	private PlanFeatureRepositoryPort planFeatureRepository;

	@Mock
	private PlanLimitRejectionAuditor rejectionAuditor;

	@Mock
	private LongSupplier contador;

	/**
	 * Solo para construir el servicio: {@code createWithinLimit} abre su transaccion con el.
	 * Los tests de esta clase invocan el gate SIN transaccion activa —es un objeto plano, sin
	 * proxy—, asi que la verificacion de isolation no se dispara y no hay nada que simular.
	 */
	@Mock
	private org.springframework.transaction.PlatformTransactionManager transactionManager;

	@InjectMocks
	private PlanGateService gate;

	private void organizacionVigenteConPlan(long planId) {
		given(organizationRepository.findByIdAndActiveTrue(ORG_ID))
				.willReturn(Optional.of(Fixtures.organizacion()));
		given(subscriptionRepository.findByOrganizationIdForUpdate(ORG_ID))
				.willReturn(Optional.of(Fixtures.suscripcionActiva(planId)));
	}

	@Test
	@DisplayName("El conteo del recurso ocurre DESPUES de bloquear la suscripcion (B-3)")
	void el_conteo_ocurre_despues_del_bloqueo_de_la_suscripcion() {
		organizacionVigenteConPlan(PLAN_BASICO_ID);
		given(contador.getAsLong()).willReturn(2L);
		given(planLimitRepository.findByPlanIdAndLimitCodeAndActiveTrue(
				PLAN_BASICO_ID, LimitCode.MAX_CONSULTORIOS))
				.willReturn(Optional.of(Fixtures.limite(
						PLAN_BASICO_ID, LimitCode.MAX_CONSULTORIOS, 5)));

		gate.evaluateCreationAndLock(ORG_ID, LimitCode.MAX_CONSULTORIOS, contador);

		// Este orden ES la correccion de B-3. Contar antes del SELECT ... FOR UPDATE deja que
		// dos altas concurrentes lean el mismo numero y pasen las dos el mismo control.
		InOrder orden = inOrder(subscriptionRepository, contador);
		orden.verify(subscriptionRepository).findByOrganizationIdForUpdate(ORG_ID);
		orden.verify(contador).getAsLong();
	}

	@Test
	@DisplayName("Con el limite alcanzado se rechaza el alta y queda auditado el rechazo")
	void limite_alcanzado_rechaza() {
		organizacionVigenteConPlan(PLAN_BASICO_ID);
		given(contador.getAsLong()).willReturn(5L);
		given(planLimitRepository.findByPlanIdAndLimitCodeAndActiveTrue(
				PLAN_BASICO_ID, LimitCode.MAX_CONSULTORIOS))
				.willReturn(Optional.of(Fixtures.limite(
						PLAN_BASICO_ID, LimitCode.MAX_CONSULTORIOS, 5)));

		assertThatThrownBy(() ->
				gate.evaluateCreationAndLock(ORG_ID, LimitCode.MAX_CONSULTORIOS, contador))
				.isInstanceOf(PlanLimitExceededException.class)
				.extracting("limitCode", "limitValue", "currentUsage")
				.containsExactly(LimitCode.MAX_CONSULTORIOS, 5, 5L);

		verify(rejectionAuditor).recordRejection(ORG_ID, LimitCode.MAX_CONSULTORIOS, 5, 5L);
	}

	@Test
	@DisplayName("Justo debajo del tope el alta entra: el limite es el tope, no el tope menos uno")
	void un_lugar_libre_permite() {
		organizacionVigenteConPlan(PLAN_BASICO_ID);
		given(contador.getAsLong()).willReturn(4L);
		given(planLimitRepository.findByPlanIdAndLimitCodeAndActiveTrue(
				PLAN_BASICO_ID, LimitCode.MAX_CONSULTORIOS))
				.willReturn(Optional.of(Fixtures.limite(
						PLAN_BASICO_ID, LimitCode.MAX_CONSULTORIOS, 5)));

		PlanDecision decision =
				gate.evaluateCreationAndLock(ORG_ID, LimitCode.MAX_CONSULTORIOS, contador);

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.currentUsage()).isEqualTo(4L);
		verify(rejectionAuditor, never()).recordRejection(anyLong(), any(), any(), anyLong());
	}

	@Test
	@DisplayName("limit_value NULL es ilimitado explicito: permite con cualquier uso")
	void limite_nulo_es_ilimitado() {
		organizacionVigenteConPlan(PLAN_BASICO_ID);
		given(contador.getAsLong()).willReturn(9_999L);
		given(planLimitRepository.findByPlanIdAndLimitCodeAndActiveTrue(
				PLAN_BASICO_ID, LimitCode.MAX_CONSULTORIOS))
				.willReturn(Optional.of(Fixtures.limite(
						PLAN_BASICO_ID, LimitCode.MAX_CONSULTORIOS, null)));

		PlanDecision decision =
				gate.evaluateCreationAndLock(ORG_ID, LimitCode.MAX_CONSULTORIOS, contador);

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.unlimited()).isTrue();
	}

	@Test
	@DisplayName("Sin fila de limite el limite no aplica al plan, que no es lo mismo que ilimitado")
	void limite_ausente_permite() {
		organizacionVigenteConPlan(PLAN_BASICO_ID);
		given(contador.getAsLong()).willReturn(120L);
		given(planLimitRepository.findByPlanIdAndLimitCodeAndActiveTrue(
				PLAN_BASICO_ID, LimitCode.MAX_MIEMBROS_ACTIVOS))
				.willReturn(Optional.empty());

		PlanDecision decision =
				gate.evaluateCreationAndLock(ORG_ID, LimitCode.MAX_MIEMBROS_ACTIVOS, contador);

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.limitValue()).isNull();
	}

	@Test
	@DisplayName("Con la suscripcion suspendida no se evalua el limite: el alta se rechaza antes")
	void suscripcion_suspendida_rechaza_sin_contar() {
		given(organizationRepository.findByIdAndActiveTrue(ORG_ID))
				.willReturn(Optional.of(Fixtures.organizacion()));
		Subscription suspendida = Fixtures.suscripcionActiva(PLAN_BASICO_ID);
		suspendida.transitionTo(SubscriptionStatus.SUSPENDIDA);
		given(subscriptionRepository.findByOrganizationIdForUpdate(ORG_ID))
				.willReturn(Optional.of(suspendida));

		assertThatThrownBy(() ->
				gate.evaluateCreationAndLock(ORG_ID, LimitCode.MAX_CONSULTORIOS, contador))
				.isInstanceOf(SubscriptionSuspendedException.class);

		verify(contador, never()).getAsLong();
	}

	@Test
	@DisplayName("Una organizacion inexistente o de otro tenant no llega ni a tomar el bloqueo")
	void organizacion_inexistente_no_bloquea() {
		given(organizationRepository.findByIdAndActiveTrue(ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() ->
				gate.evaluateCreationAndLock(ORG_ID, LimitCode.MAX_CONSULTORIOS, contador))
				.isInstanceOf(OrganizationNotFoundException.class);

		verify(subscriptionRepository, never()).findByOrganizationIdForUpdate(anyLong());
	}

	@Test
	@DisplayName("Una feature ausente del plan se rechaza como regla de negocio, no como permiso")
	void feature_ausente_rechaza() {
		given(subscriptionRepository.findByOrganizationId(ORG_ID))
				.willReturn(Optional.of(Fixtures.suscripcionActiva(PLAN_BASICO_ID)));
		given(planFeatureRepository.existsByPlanIdAndFeatureCodeAndActiveTrue(
				PLAN_BASICO_ID, FeatureCode.REPORTES_AVANZADOS))
				.willReturn(false);

		assertThatThrownBy(() ->
				gate.requireFeature(ORG_ID, FeatureCode.REPORTES_AVANZADOS))
				.isInstanceOf(FeatureNotAvailableException.class)
				.extracting("featureCode")
				.isEqualTo(FeatureCode.REPORTES_AVANZADOS);
	}

	@Test
	@DisplayName("Una feature incluida en el plan pasa sin tomar ningun bloqueo")
	void feature_incluida_pasa() {
		given(subscriptionRepository.findByOrganizationId(ORG_ID))
				.willReturn(Optional.of(Fixtures.suscripcionActiva(PLAN_BASICO_ID)));
		given(planFeatureRepository.existsByPlanIdAndFeatureCodeAndActiveTrue(
				PLAN_BASICO_ID, FeatureCode.NOTIFICACIONES_PACIENTE))
				.willReturn(true);

		gate.requireFeature(ORG_ID, FeatureCode.NOTIFICACIONES_PACIENTE);

		verify(subscriptionRepository, never()).findByOrganizationIdForUpdate(eq(ORG_ID));
	}

	@Test
	@DisplayName("Una organizacion dada de baja no admite mutaciones aunque la suscripcion este activa")
	void organizacion_de_baja_no_admite_mutaciones() {
		given(organizationRepository.findByIdAndActiveTrue(ORG_ID)).willReturn(Optional.empty());

		assertThat(gate.allowsBusinessMutations(ORG_ID)).isFalse();
	}
}
