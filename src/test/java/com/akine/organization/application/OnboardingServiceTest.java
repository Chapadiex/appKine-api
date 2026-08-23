package com.akine.organization.application;

import com.akine.organization.domain.Membership;
import com.akine.organization.domain.OrganizationOnboarding;
import com.akine.organization.domain.RoleCode;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import com.akine.organization.domain.port.OrganizationOnboardingRepositoryPort;
import com.akine.organization.domain.exception.OrganizationSlugTakenException;
import com.akine.organization.spi.InitialOrganizationCommand;
import com.akine.organization.spi.OnboardingKeyTakenException;
import com.akine.organization.spi.ProvisioningResult;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Arrays;
import java.util.Optional;

import static com.akine.organization.application.Fixtures.ACCOUNT_ID;
import static com.akine.organization.application.Fixtures.CONSULTORIO_ID;
import static com.akine.organization.application.Fixtures.MEMBERSHIP_ID;
import static com.akine.organization.application.Fixtures.ORG_ID;
import static com.akine.organization.application.Fixtures.PLAN_BASICO_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Alta compuesta de una organizacion (ADR-0008).
 *
 * <p>Los dos tests que sostienen la etapa son
 * {@link #el_fundador_nace_org_admin_y_founder()} —OWNER y ADMIN no existen en la matriz
 * aprobada— y {@link #el_reintento_concurrente_sale_por_la_senal()}: si esa
 * rama se pierde, dos altas simultaneas con la misma clave le devuelven un 500 al segundo
 * cliente en lugar del tenant que ya se creo.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

	private static final String CLAVE = "idem-key-0001";
	private static final String HASH = "hash-del-payload";

	@Mock
	private OrganizationOnboardingRepositoryPort onboardingRepository;

	@Mock
	private MembershipRepositoryPort membershipRepository;

	@Mock
	private OrganizationService organizationService;

	@Mock
	private PlanCatalogService planCatalogService;

	@Mock
	private AuditTrail auditTrail;

	@InjectMocks
	private OnboardingService onboardingService;

	private static InitialOrganizationCommand comando(String planCode, String consultorioName) {
		return new InitialOrganizationCommand(
				CLAVE, HASH, ACCOUNT_ID, "Centro Kine Norte", null, consultorioName, planCode);
	}

	private final com.akine.organization.domain.Plan planBasico =
			Fixtures.plan(PLAN_BASICO_ID, "BASICO");

	/** Camino feliz: clave sin usar, plan contratable, tenant aprovisionado. */
	private void altaNueva() {
		given(onboardingRepository.findByIdempotencyKey(CLAVE)).willReturn(Optional.empty());
		given(planCatalogService.requireContractable(any())).willReturn(planBasico);
		given(organizationService.provisionTenant(
				any(), any(), any(), any(), any(), any()))
				.willReturn(new ProvisionedTenant(
						Fixtures.organizacion(),
						Fixtures.consultorio(CONSULTORIO_ID, "Sede Centro"),
						Fixtures.suscripcionActiva(PLAN_BASICO_ID)));
		given(membershipRepository.save(any(Membership.class)))
				.willAnswer(invocacion ->
						Fixtures.conId(invocacion.getArgument(0), MEMBERSHIP_ID));
	}

	@Test
	@DisplayName("El alta compuesta crea organizacion, consultorio, suscripcion y membership")
	void el_alta_crea_las_cuatro_entidades() {
		altaNueva();

		ProvisioningResult resultado = onboardingService.provision(comando("BASICO", "Sede Centro"));

		assertThat(resultado).isEqualTo(
				new ProvisioningResult(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID, true));
		// Organizacion + consultorio + suscripcion + fila inicial del historico salen de aca.
		verify(organizationService).provisionTenant(
				"Centro Kine Norte", null, null, planBasico, "Sede Centro", ACCOUNT_ID);
		verify(membershipRepository).save(any(Membership.class));
		verify(onboardingRepository).saveAndFlush(any(OrganizationOnboarding.class));
	}

	@Test
	@DisplayName("La membership del propietario nace ORG_ADMIN + is_founder, nunca OWNER ni ADMIN")
	void el_fundador_nace_org_admin_y_founder() {
		altaNueva();

		onboardingService.provision(comando(null, null));

		ArgumentCaptor<Membership> capturada = ArgumentCaptor.forClass(Membership.class);
		verify(membershipRepository).save(capturada.capture());
		Membership membership = capturada.getValue();
		assertThat(membership.getRoleCode()).isEqualTo(RoleCode.ORG_ADMIN);
		assertThat(membership.isFounder()).isTrue();
		// consultorioId nulo = alcance ORGANIZACION: el propietario no se acota a una sede.
		assertThat(membership.getConsultorioId()).isNull();
		assertThat(membership.getOrganizationId()).isEqualTo(ORG_ID);
		assertThat(membership.getAccountId()).isEqualTo(ACCOUNT_ID);
	}

	@ParameterizedTest
	@ValueSource(strings = {"OWNER", "ADMIN"})
	@DisplayName("OWNER y ADMIN no existen en la matriz de roles aprobada (RN-M05-006)")
	void owner_y_admin_no_existen(String denominacionProhibida) {
		// Si alguien los reintroduce, la condicion de fundador vuelve a ser un rol y la matriz
		// deja de ser cerrada. Que falle aca es mas barato que descubrirlo en 01.03.
		assertThat(Arrays.stream(RoleCode.values()).map(Enum::name))
				.doesNotContain(denominacionProhibida);
	}

	@Test
	@DisplayName("Sin plan pedido, la organizacion nace con el plan por defecto")
	void sin_plan_pedido_usa_el_plan_por_defecto() {
		altaNueva();

		onboardingService.provision(comando(null, null));

		verify(planCatalogService).requireContractable(InitialOrganizationCommand.PLAN_POR_DEFECTO);
	}

	@Test
	@DisplayName("Sin nombre de sede, la primera sede toma el nombre de la organizacion")
	void sin_nombre_de_sede_usa_el_de_la_organizacion() {
		altaNueva();

		onboardingService.provision(comando("BASICO", "   "));

		verify(organizationService).provisionTenant(
				any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq("Centro Kine Norte"),
				any());
	}

	@Test
	@DisplayName("Reintentar con la misma clave devuelve el resultado previo y no duplica nada")
	void el_reintento_devuelve_el_resultado_previo() {
		given(onboardingRepository.findByIdempotencyKey(CLAVE))
				.willReturn(Optional.of(Fixtures.onboarding(CLAVE, HASH)));

		ProvisioningResult resultado = onboardingService.provision(comando("BASICO", null));

		assertThat(resultado).isEqualTo(
				new ProvisioningResult(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID, false));
		assertThat(resultado.created()).isFalse();
		verifyNoInteractions(organizationService, membershipRepository, planCatalogService,
				auditTrail);
		verify(onboardingRepository, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("La misma clave con otro payload es conflicto, no reintento")
	void la_misma_clave_con_otro_payload_es_conflicto() {
		given(onboardingRepository.findByIdempotencyKey(CLAVE))
				.willReturn(Optional.of(Fixtures.onboarding(CLAVE, "hash-de-otro-payload")));

		assertThatThrownBy(() -> onboardingService.provision(comando("BASICO", null)))
				.isInstanceOf(IdempotencyKeyConflictException.class)
				.extracting("idempotencyKey")
				.isEqualTo(CLAVE);

		verifyNoInteractions(organizationService, membershipRepository);
	}

	@Test
	@DisplayName("Un registro sin hash acepta el replay: el alta por spi no tiene payload HTTP")
	void un_registro_sin_hash_acepta_el_replay() {
		given(onboardingRepository.findByIdempotencyKey(CLAVE))
				.willReturn(Optional.of(Fixtures.onboarding(CLAVE, null)));

		ProvisioningResult resultado = onboardingService.provision(comando("BASICO", null));

		assertThat(resultado.created()).isFalse();
	}

	@Test
	@DisplayName("El reintento CONCURRENTE sale por la senal del spi, sin tocar la sesion rota")
	void el_reintento_concurrente_sale_por_la_senal() {
		altaNueva();
		// El otro hilo inserto la misma clave entre nuestro SELECT y nuestro INSERT: quien
		// decide es uk_onboarding_key, no un chequeo previo.
		given(onboardingRepository.findByIdempotencyKey(CLAVE)).willReturn(Optional.empty());
		willThrow(new DataIntegrityViolationException("uk_onboarding_key"))
				.given(onboardingRepository).saveAndFlush(any(OrganizationOnboarding.class));

		assertThatThrownBy(() -> onboardingService.provision(comando("BASICO", null)))
				.isInstanceOf(OnboardingKeyTakenException.class);

		// Lo que este test fija es lo que NO se hace: releer al ganador sobre la sesion cuyo
		// flush acaba de fallar. Despues de un flush fallido el EntityManager queda en estado
		// indefinido y esa relectura terminaba en un 500 sobre un endpoint que promete un 202
		// uniforme (ADR-0018). El unico SELECT por clave es el del paso 1.
		verify(onboardingRepository, times(1)).findByIdempotencyKey(CLAVE);
		// El perdedor no audita: el hecho ya lo registro el ganador.
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("La colision del slug NO se confunde con la de la clave de idempotencia")
	void la_colision_de_slug_no_se_confunde_con_la_de_la_clave() {
		given(onboardingRepository.findByIdempotencyKey(CLAVE)).willReturn(Optional.empty());
		given(planCatalogService.requireContractable(any())).willReturn(planBasico);
		willThrow(new OrganizationSlugTakenException("centro-tomado"))
				.given(organizationService).provisionTenant(
						any(), any(), any(), any(), any(), any());

		// Dos causas distintas con dos respuestas distintas: el slug tomado es un error del
		// cliente (409) y la clave tomada es una carrera que termina en el 202 del ganador.
		assertThatThrownBy(() -> onboardingService.provision(comando("BASICO", null)))
				.isInstanceOf(OrganizationSlugTakenException.class);
	}

	@Test
	@DisplayName("El alta de la membership queda auditada dentro de la operacion")
	void el_alta_de_la_membership_queda_auditada() {
		altaNueva();

		onboardingService.provision(comando("BASICO", null));

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		AuditEntry evento = entrada.getValue();
		assertThat(evento.eventType()).isEqualTo(AuditEvents.MEMBERSHIP_CREATED);
		assertThat(evento.entityType()).isEqualTo(AuditEvents.ENTITY_MEMBERSHIP);
		assertThat(evento.entityId()).isEqualTo(MEMBERSHIP_ID);
		assertThat(evento.organizationId()).isEqualTo(ORG_ID);
		assertThat(evento.consultorioId()).isEqualTo(CONSULTORIO_ID);
		assertThat(evento.actorAccountId()).isEqualTo(ACCOUNT_ID);
		assertThat(evento.newState()).isEqualTo(RoleCode.ORG_ADMIN.name());
		assertThat(evento.details())
				.containsEntry("roleCode", "ORG_ADMIN")
				.containsEntry("isFounder", "true");
		// La auditoria jamas lleva contenido sensible: solo rol y condicion de fundador.
		assertThat(evento.details()).hasSize(2);
	}

	@Test
	@DisplayName("El comando rechaza clave, nombre y cuenta invalidos antes de llegar al servicio")
	void el_comando_valida_sus_obligatorios() {
		assertThatThrownBy(() -> new InitialOrganizationCommand(
				"  ", HASH, ACCOUNT_ID, "Centro", null, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("idempotencyKey");
		assertThatThrownBy(() -> new InitialOrganizationCommand(
				CLAVE, HASH, ACCOUNT_ID, null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("organizationName");
		assertThatThrownBy(() -> new InitialOrganizationCommand(
				CLAVE, HASH, 0L, "Centro", null, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("accountId");
	}

	@Test
	@DisplayName("El comando resuelve el plan y la sede por defecto en un solo lugar")
	void el_comando_resuelve_los_valores_por_defecto() {
		InitialOrganizationCommand vacio = comando(" ", " ");
		assertThat(vacio.planCodeOrDefault()).isEqualTo("BASICO");
		assertThat(vacio.consultorioNameOrDefault()).isEqualTo("Centro Kine Norte");

		InitialOrganizationCommand explicito = comando("PRO", "Sede Sur");
		assertThat(explicito.planCodeOrDefault()).isEqualTo("PRO");
		assertThat(explicito.consultorioNameOrDefault()).isEqualTo("Sede Sur");
	}
}
