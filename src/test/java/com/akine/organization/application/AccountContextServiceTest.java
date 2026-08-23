package com.akine.organization.application;

import com.akine.organization.domain.AccountActiveContext;
import com.akine.organization.domain.RoleCode;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.exception.ContextNotAuthorizedException;
import com.akine.organization.domain.port.AccountActiveContextRepositoryPort;
import com.akine.organization.domain.port.ConsultorioRepositoryPort;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.spi.ActiveContext;
import com.akine.organization.spi.AuthorizedContext;
import com.akine.organization.spi.MembershipSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static com.akine.organization.application.Fixtures.ACCOUNT_ID;
import static com.akine.organization.application.Fixtures.CONSULTORIO_ID;
import static com.akine.organization.application.Fixtures.MEMBERSHIP_ID;
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
 * Contextos de trabajo de una cuenta (RF-M01-005, ADR-0009).
 *
 * <p>La regla que este test existe para blindar es que un puntero guardado NUNCA se devuelve
 * sin revalidar: si la membership se revoco, la sede se dio de baja o la suscripcion se
 * cancelo, {@code activeContext} tiene que devolver vacio. Devolver el puntero viejo seria
 * exactamente la ventana de acceso revocado que el JavaDoc del servicio prohibe abrir.
 */
@ExtendWith(MockitoExtension.class)
class AccountContextServiceTest {

	@Mock
	private MembershipRepositoryPort membershipRepository;

	@Mock
	private OrganizationRepositoryPort organizationRepository;

	@Mock
	private ConsultorioRepositoryPort consultorioRepository;

	@Mock
	private SubscriptionRepositoryPort subscriptionRepository;

	@Mock
	private AccountActiveContextRepositoryPort activeContextRepository;

	@Mock
	private AuditTrail auditTrail;

	@InjectMocks
	private AccountContextService accountContextService;

	private void organizacionVigente() {
		given(organizationRepository.findByIdAndActiveTrue(ORG_ID))
				.willReturn(Optional.of(Fixtures.organizacion()));
	}

	private void suscripcionEn(SubscriptionStatus estado) {
		given(subscriptionRepository.findByOrganizationId(ORG_ID))
				.willReturn(Optional.of(Fixtures.suscripcionEn(PLAN_BASICO_ID, estado)));
	}

	private void sedeVigente(long consultorioId, String nombre) {
		given(consultorioRepository.findByIdAndOrganizationIdAndActiveTrue(consultorioId, ORG_ID))
				.willReturn(Optional.of(Fixtures.consultorio(consultorioId, nombre)));
	}

	// ---------------------------------------------------------------- authorizedContexts

	@Test
	@DisplayName("Una membership de alcance ORGANIZACION habilita todas las sedes activas")
	void alcance_organizacion_habilita_todas_las_sedes() {
		given(membershipRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.ACTIVA);
		given(consultorioRepository.findAllByOrganizationIdAndActiveTrue(ORG_ID))
				.willReturn(List.of(
						Fixtures.consultorio(CONSULTORIO_ID, "Sede Centro"),
						Fixtures.consultorio(OTRO_CONSULTORIO_ID, "Sede Sur")));

		List<AuthorizedContext> contextos = accountContextService.authorizedContexts(ACCOUNT_ID);

		assertThat(contextos).containsExactly(
				new AuthorizedContext(ORG_ID, "Centro Kine Norte", CONSULTORIO_ID, "Sede Centro"),
				new AuthorizedContext(ORG_ID, "Centro Kine Norte", OTRO_CONSULTORIO_ID, "Sede Sur"));
	}

	@Test
	@DisplayName("Una membership acotada a una sede habilita solo esa sede")
	void alcance_de_sede_habilita_solo_esa_sede() {
		given(membershipRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
				.willReturn(List.of(
						Fixtures.membershipDeSede(CONSULTORIO_ID, RoleCode.PROFESIONAL)));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.ACTIVA);
		sedeVigente(CONSULTORIO_ID, "Sede Centro");

		assertThat(accountContextService.authorizedContexts(ACCOUNT_ID))
				.extracting(AuthorizedContext::consultorioId)
				.containsExactly(CONSULTORIO_ID);
		verify(consultorioRepository, never()).findAllByOrganizationIdAndActiveTrue(any());
	}

	@Test
	@DisplayName("Una membership con valid_until en el pasado queda excluida")
	void una_membership_vencida_no_ofrece_contexto() {
		given(membershipRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVencida()));

		assertThat(accountContextService.authorizedContexts(ACCOUNT_ID)).isEmpty();
		// Ni siquiera se consulta la organizacion: la vigencia se corta antes.
		verifyNoInteractions(organizationRepository, consultorioRepository);
	}

	@Test
	@DisplayName("Una membership que todavia no empezo a regir queda excluida")
	void una_membership_futura_no_ofrece_contexto() {
		given(membershipRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
				.willReturn(List.of(Fixtures.conId(new com.akine.organization.domain.Membership(
						ORG_ID, null, ACCOUNT_ID, RoleCode.ORG_ADMIN, false,
						Instant.now().plus(1, ChronoUnit.DAYS)), MEMBERSHIP_ID)));

		assertThat(accountContextService.authorizedContexts(ACCOUNT_ID)).isEmpty();
	}

	@Test
	@DisplayName("Un consultorio dado de baja no aparece entre los contextos")
	void un_consultorio_de_baja_no_aparece() {
		given(membershipRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.ACTIVA);
		given(consultorioRepository.findAllByOrganizationIdAndActiveTrue(ORG_ID))
				.willReturn(List.of());

		assertThat(accountContextService.authorizedContexts(ACCOUNT_ID)).isEmpty();
	}

	@Test
	@DisplayName("Una organizacion dada de baja no ofrece contextos")
	void una_organizacion_de_baja_no_ofrece_contextos() {
		given(membershipRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		given(organizationRepository.findByIdAndActiveTrue(ORG_ID)).willReturn(Optional.empty());

		assertThat(accountContextService.authorizedContexts(ACCOUNT_ID)).isEmpty();
	}

	@Test
	@DisplayName("Una suscripcion CANCELADA no ofrece contextos: el estado es terminal")
	void una_suscripcion_cancelada_no_ofrece_contextos() {
		given(membershipRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.CANCELADA);

		assertThat(accountContextService.authorizedContexts(ACCOUNT_ID)).isEmpty();
	}

	@Test
	@DisplayName("Una suscripcion SUSPENDIDA si ofrece contextos: el modo restringido deja entrar")
	void una_suscripcion_suspendida_si_ofrece_contextos() {
		given(membershipRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.SUSPENDIDA);
		given(consultorioRepository.findAllByOrganizationIdAndActiveTrue(ORG_ID))
				.willReturn(List.of(Fixtures.consultorio(CONSULTORIO_ID, "Sede Centro")));

		assertThat(accountContextService.authorizedContexts(ACCOUNT_ID)).hasSize(1);
	}

	@Test
	@DisplayName("Sin suscripcion registrada no hay contexto seleccionable")
	void sin_suscripcion_no_hay_contexto() {
		given(membershipRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		given(subscriptionRepository.findByOrganizationId(ORG_ID)).willReturn(Optional.empty());

		assertThat(accountContextService.authorizedContexts(ACCOUNT_ID)).isEmpty();
	}

	// ---------------------------------------------------------------- isContextAuthorized

	@Test
	@DisplayName("El contexto se autoriza con membership vigente, sede activa y tenant usable")
	void contexto_valido_se_autoriza() {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.ACTIVA);
		sedeVigente(CONSULTORIO_ID, "Sede Centro");

		assertThat(accountContextService.isContextAuthorized(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID))
				.isTrue();
	}

	@Test
	@DisplayName("Una membership acotada a otra sede no autoriza el contexto pedido")
	void membership_de_otra_sede_no_autoriza() {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(
						Fixtures.membershipDeSede(OTRO_CONSULTORIO_ID, RoleCode.PROFESIONAL)));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.ACTIVA);

		assertThat(accountContextService.isContextAuthorized(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID))
				.isFalse();
		verify(consultorioRepository, never())
				.findByIdAndOrganizationIdAndActiveTrue(any(), any());
	}

	@Test
	@DisplayName("Sin membership en el tenant el contexto no se autoriza")
	void sin_membership_no_se_autoriza() {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of());

		assertThat(accountContextService.isContextAuthorized(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID))
				.isFalse();
		assertThat(accountContextService.hasActiveMembership(ACCOUNT_ID, ORG_ID)).isFalse();
	}

	@Test
	@DisplayName("Con la sede dada de baja el contexto no se autoriza")
	void sede_de_baja_no_autoriza() {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.ACTIVA);
		given(consultorioRepository.findByIdAndOrganizationIdAndActiveTrue(CONSULTORIO_ID, ORG_ID))
				.willReturn(Optional.empty());

		assertThat(accountContextService.isContextAuthorized(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID))
				.isFalse();
	}

	@Test
	@DisplayName("hasActiveMembership responde true con membership vigente y tenant usable")
	void has_active_membership_responde_true() {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.ACTIVA);

		assertThat(accountContextService.hasActiveMembership(ACCOUNT_ID, ORG_ID)).isTrue();
	}

	// ---------------------------------------------------------------- activeContext

	@Test
	@DisplayName("El contexto activo se devuelve cuando el puntero guardado sigue siendo valido")
	void el_puntero_valido_se_devuelve() {
		given(activeContextRepository.findByAccountId(ACCOUNT_ID))
				.willReturn(Optional.of(Fixtures.puntero(ORG_ID, CONSULTORIO_ID)));
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.ACTIVA);
		sedeVigente(CONSULTORIO_ID, "Sede Centro");

		assertThat(accountContextService.activeContext(ACCOUNT_ID))
				.contains(new ActiveContext(ORG_ID, CONSULTORIO_ID));
	}

	@Test
	@DisplayName("Sin puntero guardado no hay contexto activo")
	void sin_puntero_no_hay_contexto_activo() {
		given(activeContextRepository.findByAccountId(ACCOUNT_ID)).willReturn(Optional.empty());

		assertThat(accountContextService.activeContext(ACCOUNT_ID)).isEmpty();
	}

	@Test
	@DisplayName("Un puntero con la membership revocada no se devuelve")
	void puntero_con_membership_revocada_no_se_devuelve() {
		given(activeContextRepository.findByAccountId(ACCOUNT_ID))
				.willReturn(Optional.of(Fixtures.puntero(ORG_ID, CONSULTORIO_ID)));
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVencida()));

		assertThat(accountContextService.activeContext(ACCOUNT_ID)).isEmpty();
	}

	@Test
	@DisplayName("Un puntero a una sede dada de baja no se devuelve")
	void puntero_a_sede_de_baja_no_se_devuelve() {
		given(activeContextRepository.findByAccountId(ACCOUNT_ID))
				.willReturn(Optional.of(Fixtures.puntero(ORG_ID, CONSULTORIO_ID)));
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.ACTIVA);
		given(consultorioRepository.findByIdAndOrganizationIdAndActiveTrue(CONSULTORIO_ID, ORG_ID))
				.willReturn(Optional.empty());

		assertThat(accountContextService.activeContext(ACCOUNT_ID)).isEmpty();
	}

	@Test
	@DisplayName("Un puntero de un tenant con la suscripcion cancelada no se devuelve")
	void puntero_con_suscripcion_cancelada_no_se_devuelve() {
		given(activeContextRepository.findByAccountId(ACCOUNT_ID))
				.willReturn(Optional.of(Fixtures.puntero(ORG_ID, CONSULTORIO_ID)));
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.CANCELADA);

		assertThat(accountContextService.activeContext(ACCOUNT_ID)).isEmpty();
	}

	// ---------------------------------------------------------------- selectContext

	@Test
	@DisplayName("Seleccionar un contexto valido persiste el puntero y audita CONTEXT_SELECTED")
	void seleccionar_persiste_y_audita() {
		given(activeContextRepository.findByAccountId(ACCOUNT_ID)).willReturn(Optional.empty());
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.ACTIVA);
		sedeVigente(CONSULTORIO_ID, "Sede Centro");

		ActiveContext contexto =
				accountContextService.selectContext(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID);

		assertThat(contexto).isEqualTo(new ActiveContext(ORG_ID, CONSULTORIO_ID));
		verify(activeContextRepository).save(any(AccountActiveContext.class));

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		AuditEntry evento = entrada.getValue();
		assertThat(evento.eventType()).isEqualTo(AuditEvents.CONTEXT_SELECTED);
		assertThat(evento.entityType()).isEqualTo(AuditEvents.ENTITY_ACTIVE_CONTEXT);
		assertThat(evento.previousState()).isNull();
		assertThat(evento.newState()).isEqualTo(String.valueOf(ORG_ID));
		assertThat(evento.details())
				.containsEntry("toOrganizationId", String.valueOf(ORG_ID))
				.containsEntry("toConsultorioId", String.valueOf(CONSULTORIO_ID))
				.doesNotContainKey("fromOrganizationId");
	}

	@Test
	@DisplayName("Cambiar de sede registra de donde venia el puntero")
	void cambiar_de_sede_registra_el_origen() {
		given(activeContextRepository.findByAccountId(ACCOUNT_ID))
				.willReturn(Optional.of(Fixtures.puntero(ORG_ID, OTRO_CONSULTORIO_ID)));
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.ACTIVA);
		sedeVigente(CONSULTORIO_ID, "Sede Centro");

		accountContextService.selectContext(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID);

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		assertThat(entrada.getValue().details())
				.containsEntry("fromOrganizationId", String.valueOf(ORG_ID))
				.containsEntry("fromConsultorioId", String.valueOf(OTRO_CONSULTORIO_ID));
		assertThat(entrada.getValue().previousState()).isEqualTo(String.valueOf(ORG_ID));
	}

	@Test
	@DisplayName("Reseleccionar el mismo contexto no genera ruido en la auditoria")
	void reseleccionar_lo_mismo_no_audita() {
		given(activeContextRepository.findByAccountId(ACCOUNT_ID))
				.willReturn(Optional.of(Fixtures.puntero(ORG_ID, CONSULTORIO_ID)));
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(SubscriptionStatus.ACTIVA);
		sedeVigente(CONSULTORIO_ID, "Sede Centro");

		accountContextService.selectContext(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID);

		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Un contexto no autorizado se rechaza y deja el puntero anterior intacto")
	void contexto_no_autorizado_no_toca_el_puntero() {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of());

		assertThatThrownBy(() ->
				accountContextService.selectContext(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID))
				.isInstanceOf(ContextNotAuthorizedException.class);

		verify(activeContextRepository, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	// ---------------------------------------------------------------- membership snapshot

	@Test
	@DisplayName("El snapshot de la membership expone rol, fundador y vigencia")
	void el_snapshot_mapea_la_membership() {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));

		Optional<MembershipSnapshot> snapshot =
				accountContextService.membership(ACCOUNT_ID, ORG_ID);

		assertThat(snapshot).isPresent();
		MembershipSnapshot valor = snapshot.orElseThrow();
		assertThat(valor.membershipId()).isEqualTo(MEMBERSHIP_ID);
		assertThat(valor.roleCode()).isEqualTo(RoleCode.ORG_ADMIN.name());
		assertThat(valor.founder()).isTrue();
		assertThat(valor.active()).isTrue();
		assertThat(valor.validUntil()).isNull();
		assertThat(valor.validAt(Instant.now())).isTrue();
		assertThat(valor.validAt(valor.validFrom().minus(1, ChronoUnit.DAYS))).isFalse();
	}

	@Test
	@DisplayName("El snapshot de una membership vencida no habilita nada")
	void el_snapshot_vencido_no_habilita() {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVencida()));

		MembershipSnapshot snapshot =
				accountContextService.membership(ACCOUNT_ID, ORG_ID).orElseThrow();

		assertThat(snapshot.validAt(Instant.now())).isFalse();
	}

	@Test
	@DisplayName("Sin membership en el tenant no hay snapshot")
	void sin_membership_no_hay_snapshot() {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of());

		assertThat(accountContextService.membership(ACCOUNT_ID, ORG_ID)).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(SubscriptionStatus.class)
	@DisplayName("Solo CANCELADA impide seleccionar un contexto; el resto de estados deja entrar")
	void solo_cancelada_impide_entrar(SubscriptionStatus estado) {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));
		organizacionVigente();
		suscripcionEn(estado);
		if (estado != SubscriptionStatus.CANCELADA) {
			sedeVigente(CONSULTORIO_ID, "Sede Centro");
		}

		boolean autorizado =
				accountContextService.isContextAuthorized(ACCOUNT_ID, ORG_ID, CONSULTORIO_ID);

		assertThat(autorizado).isEqualTo(estado != SubscriptionStatus.CANCELADA);
	}
}
