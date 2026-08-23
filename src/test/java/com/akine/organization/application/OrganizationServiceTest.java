package com.akine.organization.application;

import com.akine.organization.domain.Consultorio;
import com.akine.organization.domain.OperationalStatus;
import com.akine.organization.domain.Organization;
import com.akine.organization.domain.Plan;
import com.akine.organization.domain.Subscription;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.SubscriptionTransition;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.port.ConsultorioRepositoryPort;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.akine.organization.application.Fixtures.ACCOUNT_ID;
import static com.akine.organization.application.Fixtures.CONSULTORIO_ID;
import static com.akine.organization.application.Fixtures.ORG_ID;
import static com.akine.organization.application.Fixtures.OTRO_CONSULTORIO_ID;
import static com.akine.organization.application.Fixtures.PLAN_BASICO_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Alta, edicion y lectura del tenant (RF-M01-001).
 *
 * <p>Los dos invariantes que se fijan aca: el alta crea SIEMPRE el primer consultorio y la
 * fila inicial del historico —sin ellos el tenant nace sin contexto seleccionable y sin
 * historia—, y la edicion con version vieja no persiste nada.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

	@Mock
	private OrganizationRepositoryPort organizationRepository;

	@Mock
	private ConsultorioRepositoryPort consultorioRepository;

	@Mock
	private SubscriptionRepositoryPort subscriptionRepository;

	@Mock
	private SubscriptionTransitionRepositoryPort transitionRepository;

	@Mock
	private PlanCatalogService planCatalogService;

	@Mock
	private AuditTrail auditTrail;

	@InjectMocks
	private OrganizationService organizationService;

	private final Plan planBasico = Fixtures.plan(PLAN_BASICO_ID, "BASICO");

	/** Devuelve las entidades ya con id, como haria la base al insertarlas. */
	private void altaEnCurso() {
		given(planCatalogService.requireContractable(any())).willReturn(planBasico);
		given(organizationRepository.save(any(Organization.class)))
				.willAnswer(invocacion -> Fixtures.conId(invocacion.getArgument(0), ORG_ID));
		given(consultorioRepository.save(any(Consultorio.class)))
				.willAnswer(invocacion ->
						Fixtures.conId(invocacion.getArgument(0), CONSULTORIO_ID));
		given(subscriptionRepository.save(any(Subscription.class)))
				.willAnswer(invocacion ->
						Fixtures.conId(invocacion.getArgument(0), Fixtures.SUBSCRIPTION_ID));
	}

	// ---------------------------------------------------------------------------- alta

	@Test
	@DisplayName("El alta crea organizacion, primera sede, suscripcion y fila inicial del historico")
	void el_alta_crea_el_tenant_completo() {
		altaEnCurso();

		OrganizationView view = organizationService.create(
				"Centro Kine Norte", null, null, "BASICO", ACCOUNT_ID);

		assertThat(view.id()).isEqualTo(ORG_ID);
		assertThat(view.name()).isEqualTo("Centro Kine Norte");
		assertThat(view.slug()).isEqualTo("centro-kine-norte");
		assertThat(view.active()).isTrue();
		assertThat(view.operationalStatus()).isEqualTo(OperationalStatus.ACTIVA);

		// El primer consultorio se crea siempre: sin sede no hay contexto seleccionable.
		ArgumentCaptor<Consultorio> sede = ArgumentCaptor.forClass(Consultorio.class);
		verify(consultorioRepository).save(sede.capture());
		assertThat(sede.getValue().getName()).isEqualTo("Centro Kine Norte");

		// Fila inicial: from = null significa "alta de la suscripcion".
		ArgumentCaptor<SubscriptionTransition> historico =
				ArgumentCaptor.forClass(SubscriptionTransition.class);
		verify(transitionRepository).save(historico.capture());
		assertThat(historico.getValue().getFromStatus()).isNull();
		assertThat(historico.getValue().getToStatus()).isEqualTo(SubscriptionStatus.ACTIVA);
		assertThat(historico.getValue().getFromPlanId()).isNull();
		assertThat(historico.getValue().getToPlanId()).isEqualTo(PLAN_BASICO_ID);
	}

	@Test
	@DisplayName("Sin zona horaria, el tenant nace en la zona por defecto: las reglas locales "
			+ "no se calculan sobre UTC")
	void sin_zona_usa_la_de_defecto() {
		altaEnCurso();

		OrganizationView view =
				organizationService.create("Centro Kine Norte", null, "  ", "BASICO", ACCOUNT_ID);

		assertThat(view.timezone()).isEqualTo(OrganizationService.TIMEZONE_POR_DEFECTO);
	}

	@Test
	@DisplayName("Un slug provisto se normaliza en lugar de derivarse del nombre")
	void un_slug_provisto_se_normaliza() {
		altaEnCurso();

		OrganizationView view = organizationService.create(
				"Centro Kine Norte", "  Kinesiologia Nuñez  ", "America/Argentina/Cordoba",
				"BASICO", ACCOUNT_ID);

		assertThat(view.slug()).isEqualTo("kinesiologia-nunez");
		// Con slug explicito no se consulta la disponibilidad: quien decide es la restriccion.
		verify(organizationRepository, never()).existsBySlug(any());
	}

	@Test
	@DisplayName("El alta queda auditada como ORGANIZATION_CREATED dentro de la transaccion")
	void el_alta_queda_auditada() {
		altaEnCurso();

		organizationService.create("Centro Kine Norte", null, null, "BASICO", ACCOUNT_ID);

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		AuditEntry evento = entrada.getValue();
		assertThat(evento.eventType()).isEqualTo(AuditEvents.ORGANIZATION_CREATED);
		assertThat(evento.entityType()).isEqualTo(AuditEvents.ENTITY_ORGANIZATION);
		assertThat(evento.entityId()).isEqualTo(ORG_ID);
		assertThat(evento.consultorioId()).isEqualTo(CONSULTORIO_ID);
		assertThat(evento.actorAccountId()).isEqualTo(ACCOUNT_ID);
		assertThat(evento.newState()).isEqualTo(SubscriptionStatus.ACTIVA.name());
		assertThat(evento.details())
				.containsEntry("slug", "centro-kine-norte")
				.containsEntry("planCode", "BASICO")
				.containsEntry("timezone", OrganizationService.TIMEZONE_POR_DEFECTO);
	}

	@Test
	@DisplayName("Con un plan no contratable el alta falla antes de escribir nada")
	void plan_no_contratable_no_escribe_nada() {
		given(planCatalogService.requireContractable("RETIRADO"))
				.willThrow(new PlanNotFoundException("RETIRADO"));

		assertThatThrownBy(() ->
				organizationService.create("Centro", null, null, "RETIRADO", ACCOUNT_ID))
				.isInstanceOf(PlanNotFoundException.class)
				.extracting("planCode")
				.isEqualTo("RETIRADO");

		verifyNoInteractions(organizationRepository, consultorioRepository,
				subscriptionRepository, transitionRepository, auditTrail);
	}

	// ------------------------------------------------------------------------- edicion

	@Test
	@DisplayName("Editar con la version esperada persiste el cambio y lo audita")
	void editar_con_la_version_correcta_persiste() {
		Organization organization = Fixtures.organizacion();
		given(organizationRepository.findByIdAndActiveTrue(ORG_ID))
				.willReturn(Optional.of(organization));
		given(subscriptionRepository.findByOrganizationId(ORG_ID))
				.willReturn(Optional.of(Fixtures.suscripcionActiva(PLAN_BASICO_ID)));

		OrganizationView view = organizationService.update(
				ORG_ID, "Centro Kine Sur", "America/Argentina/Buenos_Aires", 0L, ACCOUNT_ID);

		assertThat(view.name()).isEqualTo("Centro Kine Sur");
		assertThat(view.timezone()).isEqualTo("America/Argentina/Buenos_Aires");
		// El slug es inmutable: se usa en URLs y en soporte.
		assertThat(view.slug()).isEqualTo("centro-kine-norte");
		verify(organizationRepository).save(organization);

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		assertThat(entrada.getValue().eventType()).isEqualTo(AuditEvents.ORGANIZATION_UPDATED);
		assertThat(entrada.getValue().details())
				.containsEntry("name", "Centro Kine Norte -> Centro Kine Sur")
				.containsEntry("timezone",
						"America/Argentina/Cordoba -> America/Argentina/Buenos_Aires");
	}

	@Test
	@DisplayName("Editar con una version desactualizada es conflicto y no persiste nada")
	void editar_con_version_vieja_es_conflicto() {
		Organization organization = Fixtures.organizacion();
		ReflectionTestUtils.setField(organization, "version", 7L);
		given(organizationRepository.findByIdAndActiveTrue(ORG_ID))
				.willReturn(Optional.of(organization));

		assertThatThrownBy(() ->
				organizationService.update(ORG_ID, "Otro nombre", null, 3L, ACCOUNT_ID))
				.isInstanceOf(OptimisticLockingFailureException.class);

		assertThat(organization.getName()).isEqualTo("Centro Kine Norte");
		verify(organizationRepository, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Una edicion sin cambios igual se audita: el intento tambien es un hecho")
	void una_edicion_sin_cambios_igual_audita() {
		given(organizationRepository.findByIdAndActiveTrue(ORG_ID))
				.willReturn(Optional.of(Fixtures.organizacion()));
		given(subscriptionRepository.findByOrganizationId(ORG_ID))
				.willReturn(Optional.of(Fixtures.suscripcionActiva(PLAN_BASICO_ID)));

		organizationService.update(ORG_ID, null, null, 0L, ACCOUNT_ID);

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		assertThat(entrada.getValue().details()).isEmpty();
	}

	@Test
	@DisplayName("Editar una organizacion dada de baja se responde igual que si no existiera")
	void editar_una_organizacion_de_baja_es_404() {
		given(organizationRepository.findByIdAndActiveTrue(ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> organizationService.update(ORG_ID, "x", null, 0L, ACCOUNT_ID))
				.isInstanceOf(OrganizationNotFoundException.class);
	}

	// ------------------------------------------------------------------------- lectura

	@Test
	@DisplayName("La lectura devuelve el tenant con su estado operativo derivado")
	void la_lectura_devuelve_el_estado_derivado() {
		given(organizationRepository.findByIdAndActiveTrue(ORG_ID))
				.willReturn(Optional.of(Fixtures.organizacion()));
		given(subscriptionRepository.findByOrganizationId(ORG_ID))
				.willReturn(Optional.of(
						Fixtures.suscripcionEn(PLAN_BASICO_ID, SubscriptionStatus.SUSPENDIDA)));

		OrganizationView view = organizationService.find(ORG_ID);

		assertThat(view.operationalStatus()).isEqualTo(OperationalStatus.SUSPENDIDA);
		assertThat(view.version()).isZero();
	}

	@Test
	@DisplayName("Un tenant sin suscripcion se responde como inexistente, no como medio creado")
	void un_tenant_sin_suscripcion_es_404() {
		given(organizationRepository.findByIdAndActiveTrue(ORG_ID))
				.willReturn(Optional.of(Fixtures.organizacion()));
		given(subscriptionRepository.findByOrganizationId(ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> organizationService.find(ORG_ID))
				.isInstanceOf(OrganizationNotFoundException.class);
	}

	@Test
	@DisplayName("Las sedes activas se devuelven proyectadas, sin entities")
	void las_sedes_se_devuelven_proyectadas() {
		given(organizationRepository.findByIdAndActiveTrue(ORG_ID))
				.willReturn(Optional.of(Fixtures.organizacion()));
		given(consultorioRepository.findAllByOrganizationIdAndActiveTrue(ORG_ID))
				.willReturn(List.of(
						Fixtures.consultorio(CONSULTORIO_ID, "Sede Centro"),
						Fixtures.consultorio(OTRO_CONSULTORIO_ID, "Sede Sur")));

		assertThat(organizationService.consultorios(ORG_ID)).containsExactly(
				new ConsultorioView(CONSULTORIO_ID, ORG_ID, "Sede Centro", true),
				new ConsultorioView(OTRO_CONSULTORIO_ID, ORG_ID, "Sede Sur", true));
	}

	@Test
	@DisplayName("La baja logica de la organizacion gana sobre el estado de la suscripcion")
	void la_baja_de_la_organizacion_gana() {
		Organization organization = Fixtures.organizacion();
		organization.deactivate(Instant.now());
		given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(organization));
		given(subscriptionRepository.findByOrganizationId(ORG_ID))
				.willReturn(Optional.of(Fixtures.suscripcionActiva(PLAN_BASICO_ID)));

		assertThat(organizationService.operationalStatus(ORG_ID))
				.isEqualTo(OperationalStatus.BAJA);
	}

	@Test
	@DisplayName("El estado operativo de un tenant vigente sale de su suscripcion")
	void el_estado_operativo_sale_de_la_suscripcion() {
		given(organizationRepository.findById(ORG_ID))
				.willReturn(Optional.of(Fixtures.organizacion()));
		given(subscriptionRepository.findByOrganizationId(ORG_ID))
				.willReturn(Optional.of(
						Fixtures.suscripcionEn(PLAN_BASICO_ID, SubscriptionStatus.CANCELADA)));

		assertThat(organizationService.operationalStatus(ORG_ID))
				.isEqualTo(OperationalStatus.CANCELADA);
	}

	@Test
	@DisplayName("El estado operativo de un id inexistente es 404")
	void el_estado_operativo_de_un_id_inexistente_es_404() {
		given(organizationRepository.findById(ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> organizationService.operationalStatus(ORG_ID))
				.isInstanceOf(OrganizationNotFoundException.class);
	}
}
