package com.akine.organization.application;

import com.akine.organization.domain.Membership;
import com.akine.organization.domain.MembershipEstado;
import com.akine.organization.domain.MembershipGrant;
import com.akine.organization.domain.PermissionCode;
import com.akine.organization.domain.PermissionScope;
import com.akine.organization.domain.RoleCode;
import com.akine.organization.domain.exception.FounderRevocationNotAllowedException;
import com.akine.organization.domain.exception.GrantAlreadyActiveException;
import com.akine.organization.domain.exception.LastAdminException;
import com.akine.organization.domain.exception.MembershipAlreadyExistsException;
import com.akine.organization.domain.exception.MembershipNotAccessibleException;
import com.akine.organization.domain.exception.MembershipNotActiveException;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.SelfRevokeNotAllowedException;
import com.akine.organization.domain.exception.UnknownPermissionCodeException;
import com.akine.organization.domain.port.ConsultorioRepositoryPort;
import com.akine.organization.domain.port.MembershipGrantRepositoryPort;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.spi.DirectMembershipCommand;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static com.akine.organization.application.Fixtures.ACCOUNT_ID;
import static com.akine.organization.application.Fixtures.CONSULTORIO_ID;
import static com.akine.organization.application.Fixtures.MEMBERSHIP_ID;
import static com.akine.organization.application.Fixtures.ORG_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Administracion de colaboradores: los invariantes y el orden del protocolo, sin base de datos.
 *
 * <h2>Que puede y que no puede probar este test</h2>
 *
 * <p>Puede probar que el bloqueo del tenant es <b>la primera sentencia</b>, que los invariantes
 * se consultan con la lectura con lock y no con un conteo comun, y que la auditoria se escribe
 * en la transaccion del negocio. <b>No puede probar que el invariante se sostenga bajo
 * concurrencia</b>: eso necesita dos hilos reales contra MySQL real y esta en
 * {@code MembershipConcurrenteIT}. Un test con mocks pasa con el diseño roto, que es exactamente
 * por que los bugs de concurrencia de 01.01/01.02 no aparecieron antes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MembershipServiceTest {

	private static final long OTRA_CUENTA = 31L;
	private static final String MOTIVO = "reorganizacion del equipo";

	@Mock
	private MembershipRepositoryPort membershipRepository;

	@Mock
	private MembershipGrantRepositoryPort grantRepository;

	@Mock
	private SubscriptionRepositoryPort subscriptionRepository;

	@Mock
	private ConsultorioRepositoryPort consultorioRepository;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private PermissionEvaluator permissionEvaluator;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private SupportAccessReadAuditor supportAccessReadAuditor;

	@Mock
	private AccountIdentityDirectory accountDirectory;

	@InjectMocks
	private MembershipService service;

	private final OperatingActor actor = new OperatingActor(ACCOUNT_ID, false, CONSULTORIO_ID);

	@BeforeEach
	void elTenantExisteYElPermisoAlcanza() {
		given(subscriptionRepository.findByOrganizationIdForUpdate(ORG_ID))
				.willReturn(Optional.of(Fixtures.suscripcionActiva(Fixtures.PLAN_BASICO_ID)));
		given(permissionGuard.requirePermission(any())).willReturn(
				PermissionDecision.concedida(PermissionScope.ORGANIZACION.name(), false));
		given(membershipRepository.save(any())).willAnswer(i -> i.getArgument(0));
		given(consultorioRepository.findByIdAndOrganizationIdAndActiveTrue(CONSULTORIO_ID, ORG_ID))
				.willReturn(Optional.of(Fixtures.consultorio(CONSULTORIO_ID, "Sede Centro")));
	}

	private void objetivo(Membership membership) {
		given(membershipRepository.findByIdAndOrganizationId(MEMBERSHIP_ID, ORG_ID))
				.willReturn(Optional.of(membership));
	}

	private void quedanAdmins(long cuantos) {
		given(membershipRepository.countActiveOrgAdminsForShare(anyLong(), any(), anyLong()))
				.willReturn(cuantos);
	}

	private AuditEntry auditoria() {
		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		return captor.getValue();
	}

	private static Membership deOtraCuenta(RoleCode rol) {
		return Fixtures.conId(new Membership(ORG_ID, null, OTRA_CUENTA, rol, false,
				Instant.now().minus(30, ChronoUnit.DAYS)), MEMBERSHIP_ID);
	}

	// =================================================================================
	// El orden del protocolo
	// =================================================================================

	@Test
	@DisplayName("El bloqueo de la suscripcion es la PRIMERA sentencia de toda mutacion")
	void el_bloqueo_del_tenant_es_lo_primero() {
		// Objetivo ORG_ADMIN a proposito: es el unico caso que llega al conteo del invariante,
		// que es la ultima sentencia del protocolo y la que hay que ver al final del orden.
		objetivo(deOtraCuenta(RoleCode.ORG_ADMIN));
		quedanAdmins(2);

		service.revoke(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO);

		var enOrden = org.mockito.Mockito.inOrder(
				subscriptionRepository, membershipRepository, permissionGuard);
		// El bloqueo va primero. Si antes hubiera cualquier lectura de JPA no bloqueante, esa
		// lectura fijaria el snapshot de la transaccion y el conteo del invariante veria datos
		// anteriores al commit del competidor aunque el lock ya estuviera tomado: el lock
		// serializa el acceso, no la visibilidad.
		enOrden.verify(subscriptionRepository).findByOrganizationIdForUpdate(ORG_ID);
		// La membership objetivo se carga DESPUES del lock y ANTES de evaluar el permiso: el
		// evaluador necesita saber sobre QUE cuenta se opera (targetAccountId, ADR-0019) y eso
		// sale de la fila. Cargarla antes del lock si seria el bug.
		enOrden.verify(membershipRepository).findByIdAndOrganizationId(MEMBERSHIP_ID, ORG_ID);
		enOrden.verify(permissionGuard).requirePermission(any());
		// Y el conteo del invariante, ultimo: sobre datos que el lock ya serializo.
		enOrden.verify(membershipRepository).countActiveOrgAdminsForShare(any(), any(), any());
	}

	@Test
	@DisplayName("Si el tenant no existe, no se evalua ningun permiso")
	void sin_tenant_no_se_evalua_nada() {
		given(subscriptionRepository.findByOrganizationIdForUpdate(ORG_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.revoke(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO))
				.isInstanceOf(OrganizationNotFoundException.class);

		verifyNoInteractions(permissionGuard, auditTrail);
	}

	@Test
	@DisplayName("Una membership de otro tenant es 404: se busca por (id, organizacion)")
	void una_membership_ajena_es_404() {
		given(membershipRepository.findByIdAndOrganizationId(MEMBERSHIP_ID, ORG_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.revoke(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO))
				.isInstanceOf(MembershipNotAccessibleException.class);
	}

	// =================================================================================
	// Invariante: ultimo admin
	// =================================================================================

	@Nested
	@DisplayName("Ultimo administrador")
	class UltimoAdmin {

		@Test
		@DisplayName("Revocar al ultimo ORG_ADMIN se rechaza con el conteo con lock en cero")
		void no_se_revoca_al_ultimo() {
			objetivo(deOtraCuenta(RoleCode.ORG_ADMIN));
			quedanAdmins(0);

			assertThatThrownBy(() -> service.revoke(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO))
					.isInstanceOf(LastAdminException.class);

			// Y la mutacion no ocurrio: nada que auditar.
			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("Con otro administrador vigente, la revocacion pasa")
		void con_otro_admin_pasa() {
			objetivo(deOtraCuenta(RoleCode.ORG_ADMIN));
			quedanAdmins(1);

			assertThat(service.revoke(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO).estado())
					.isEqualTo(MembershipEstado.REVOCADA.name());
		}

		@Test
		@DisplayName("El conteo EXCLUYE la fila objetivo, para no escalar S a X sobre ella")
		void el_conteo_excluye_al_objetivo() {
			objetivo(deOtraCuenta(RoleCode.ORG_ADMIN));
			quedanAdmins(1);

			service.revoke(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO);

			verify(membershipRepository).countActiveOrgAdminsForShare(
					org.mockito.ArgumentMatchers.eq(ORG_ID), any(),
					org.mockito.ArgumentMatchers.eq(MEMBERSHIP_ID));
		}

		@Test
		@DisplayName("Suspender pasa por el mismo invariante: saca igual que revocar")
		void suspender_tambien_cuenta() {
			objetivo(deOtraCuenta(RoleCode.ORG_ADMIN));
			quedanAdmins(0);

			assertThatThrownBy(() -> service.suspend(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO))
					.isInstanceOf(LastAdminException.class);
		}

		@Test
		@DisplayName("Bajarle el rol al ultimo admin es la misma carrera y se rechaza igual")
		void bajar_el_rol_tambien_cuenta() {
			objetivo(deOtraCuenta(RoleCode.ORG_ADMIN));
			quedanAdmins(0);

			assertThatThrownBy(() -> service.changeRole(
					actor, ORG_ID, MEMBERSHIP_ID, "PROFESIONAL", false, null, MOTIVO))
					.isInstanceOf(LastAdminException.class);
		}

		@Test
		@DisplayName("Subir a alguien a ORG_ADMIN no dispara el invariante")
		void subir_de_rol_no_dispara_el_invariante() {
			objetivo(deOtraCuenta(RoleCode.PROFESIONAL));

			service.changeRole(actor, ORG_ID, MEMBERSHIP_ID, "ORG_ADMIN", false, null, MOTIVO);

			verify(membershipRepository, never()).countActiveOrgAdminsForShare(any(), any(), any());
		}

		@Test
		@DisplayName("El invariante es sobre la organizacion, no sobre el consultorio")
		void un_consultorio_puede_quedarse_sin_admin() {
			// Una sede puede quedarse sin CONSULTORIO_ADMIN: la administra el ORG_ADMIN. 01.03
			// no inventa un segundo invariante que la matriz no pide.
			objetivo(Fixtures.conId(new Membership(ORG_ID, CONSULTORIO_ID, OTRA_CUENTA,
					RoleCode.CONSULTORIO_ADMIN, false,
					Instant.now().minus(30, ChronoUnit.DAYS)), MEMBERSHIP_ID));

			service.revoke(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO);

			verify(membershipRepository, never()).countActiveOrgAdminsForShare(any(), any(), any());
		}
	}

	// =================================================================================
	// Invariante: self-revoke
	// =================================================================================

	@Nested
	@DisplayName("Self-revoke")
	class SelfRevoke {

		private Membership propia(RoleCode rol) {
			return Fixtures.conId(new Membership(ORG_ID, null, ACCOUNT_ID, rol, false,
					Instant.now().minus(30, ChronoUnit.DAYS)), MEMBERSHIP_ID);
		}

		@Test
		@DisplayName("Quitarse el ultimo rol administrativo propio se rechaza AUNQUE queden otros admins")
		void no_se_quita_su_ultimo_rol_administrativo() {
			// Despues de la operacion el actor no puede deshacerla. Una accion irreversible por
			// distraccion no deberia ser un PATCH cualquiera.
			objetivo(propia(RoleCode.ORG_ADMIN));
			quedanAdmins(5);
			given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(
					ORG_ID, ACCOUNT_ID)).willReturn(List.of(propia(RoleCode.ORG_ADMIN)));

			assertThatThrownBy(() -> service.revoke(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO))
					.isInstanceOf(SelfRevokeNotAllowedException.class);
		}

		@Test
		@DisplayName("Si le queda OTRO rol administrativo en la organizacion, puede")
		void con_otro_rol_administrativo_propio_puede() {
			objetivo(propia(RoleCode.ORG_ADMIN));
			quedanAdmins(5);
			given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(
					ORG_ID, ACCOUNT_ID)).willReturn(List.of(
							propia(RoleCode.ORG_ADMIN),
							Fixtures.conId(new Membership(ORG_ID, CONSULTORIO_ID, ACCOUNT_ID,
									RoleCode.CONSULTORIO_ADMIN, false,
									Instant.now().minus(30, ChronoUnit.DAYS)), 61L)));

			assertThat(service.revoke(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO).estado())
					.isEqualTo(MembershipEstado.REVOCADA.name());
		}

		@Test
		@DisplayName("Quitarse un rol NO administrativo propio no dispara el invariante")
		void quitarse_un_rol_no_administrativo_pasa() {
			objetivo(propia(RoleCode.PROFESIONAL));

			assertThat(service.revoke(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO).estado())
					.isEqualTo(MembershipEstado.REVOCADA.name());
		}
	}

	// =================================================================================
	// Invariante: el fundador
	// =================================================================================

	@Nested
	@DisplayName("Fundador")
	class Fundador {

		private Membership fundador() {
			return Fixtures.conId(new Membership(ORG_ID, null, OTRA_CUENTA, RoleCode.ORG_ADMIN,
					true, Instant.now().minus(30, ChronoUnit.DAYS)), MEMBERSHIP_ID);
		}

		@Test
		@DisplayName("Otro administrador no puede desvincular al fundador: 403, no 409")
		void otro_admin_no_desvincula_al_fundador() {
			objetivo(fundador());
			quedanAdmins(3);

			assertThatThrownBy(() -> service.revoke(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO))
					.isInstanceOf(FounderRevocationNotAllowedException.class);
		}

		@Test
		@DisplayName("El fundador si puede revocarse a si mismo, sujeto a los otros invariantes")
		void el_fundador_se_revoca_a_si_mismo() {
			Membership propia = Fixtures.conId(new Membership(ORG_ID, null, ACCOUNT_ID,
					RoleCode.PROFESIONAL, true, Instant.now().minus(30, ChronoUnit.DAYS)),
					MEMBERSHIP_ID);
			objetivo(propia);

			assertThat(service.revoke(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO).estado())
					.isEqualTo(MembershipEstado.REVOCADA.name());
		}

		@Test
		@DisplayName("Un PLATFORM_ADMIN sin acceso de soporte tampoco puede: no hay puerta trasera")
		void plataforma_sin_soporte_no_desvincula_al_fundador() {
			objetivo(fundador());
			quedanAdmins(3);
			given(permissionEvaluator.hasSupportAccess(anyLong(), anyLong(), any()))
					.willReturn(false);

			OperatingActor plataforma = new OperatingActor(ACCOUNT_ID, true, null);
			assertThatThrownBy(() -> service.revoke(plataforma, ORG_ID, MEMBERSHIP_ID, MOTIVO))
					.isInstanceOf(FounderRevocationNotAllowedException.class);
		}

		@Test
		@DisplayName("Un PLATFORM_ADMIN con acceso de soporte vigente si puede: es el unico rescate")
		void plataforma_con_soporte_rescata_el_tenant() {
			objetivo(fundador());
			quedanAdmins(3);
			given(permissionEvaluator.hasSupportAccess(anyLong(), anyLong(), any()))
					.willReturn(true);

			OperatingActor plataforma = new OperatingActor(ACCOUNT_ID, true, null);
			assertThat(service.revoke(plataforma, ORG_ID, MEMBERSHIP_ID, MOTIVO).estado())
					.isEqualTo(MembershipEstado.REVOCADA.name());
		}

		@Test
		@DisplayName("Suspender al fundador esta igual de protegido que revocarlo")
		void suspender_al_fundador_tambien_esta_protegido() {
			objetivo(fundador());
			quedanAdmins(3);

			assertThatThrownBy(() -> service.suspend(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO))
					.isInstanceOf(FounderRevocationNotAllowedException.class);
		}
	}

	// =================================================================================
	// Estados y motivo
	// =================================================================================

	@Nested
	@DisplayName("Transiciones de estado")
	class Transiciones {

		@Test
		@DisplayName("Revocar dos veces la misma membership es 409, no un no-op silencioso")
		void revocar_dos_veces_es_conflicto() {
			Membership revocada = deOtraCuenta(RoleCode.PROFESIONAL);
			revocada.revocar(ACCOUNT_ID, "primera", Instant.now().minusSeconds(10));
			objetivo(revocada);

			assertThatThrownBy(() -> service.revoke(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO))
					.isInstanceOf(MembershipNotActiveException.class);
		}

		@Test
		@DisplayName("Cambiarle el rol a una membership revocada es 409 membership-not-active")
		void cambiar_rol_de_una_revocada_es_conflicto() {
			// Es la carrera de §6.5-bis: la segunda transaccion entra con el estado ya cambiado
			// y decide contra el, no contra el que el actor creia.
			Membership revocada = deOtraCuenta(RoleCode.PROFESIONAL);
			revocada.revocar(ACCOUNT_ID, "primera", Instant.now().minusSeconds(10));
			objetivo(revocada);

			assertThatThrownBy(() -> service.changeRole(
					actor, ORG_ID, MEMBERSHIP_ID, "ADMINISTRATIVO", false, null, MOTIVO))
					.isInstanceOf(MembershipNotActiveException.class);
		}

		@Test
		@DisplayName("Reactivar algo que no esta suspendido es 409")
		void reactivar_lo_no_suspendido_es_conflicto() {
			objetivo(deOtraCuenta(RoleCode.PROFESIONAL));

			assertThatThrownBy(() -> service.reactivate(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO))
					.isInstanceOf(MembershipNotActiveException.class);
		}

		@Test
		@DisplayName("Suspender y reactivar deja la membership habilitando de nuevo")
		void suspender_y_reactivar() {
			Membership objetivo = deOtraCuenta(RoleCode.PROFESIONAL);
			objetivo(objetivo);

			service.suspend(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO);
			assertThat(objetivo.getEstado()).isEqualTo(MembershipEstado.SUSPENDIDA);

			assertThat(service.reactivate(actor, ORG_ID, MEMBERSHIP_ID, MOTIVO).estado())
					.isEqualTo(MembershipEstado.ACTIVA.name());
		}

		@Test
		@DisplayName("Revocar, suspender y cambiar de rol exigen motivo declarado")
		void las_mutaciones_exigen_motivo() {
			objetivo(deOtraCuenta(RoleCode.PROFESIONAL));

			assertThatThrownBy(() -> service.revoke(actor, ORG_ID, MEMBERSHIP_ID, "  "))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> service.suspend(actor, ORG_ID, MEMBERSHIP_ID, null))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> service.changeRole(
					actor, ORG_ID, MEMBERSHIP_ID, "PROFESIONAL", false, null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	// =================================================================================
	// Cambio de rol y de alcance
	// =================================================================================

	@Nested
	@DisplayName("Cambio de rol y de alcance")
	class Cambios {

		@Test
		@DisplayName("El cambio de rol audita la transicion real, con estado anterior y nuevo")
		void el_cambio_de_rol_audita_la_transicion() {
			objetivo(deOtraCuenta(RoleCode.PROFESIONAL));

			service.changeRole(actor, ORG_ID, MEMBERSHIP_ID, "ADMINISTRATIVO", false, null, MOTIVO);

			AuditEntry entrada = auditoria();
			assertThat(entrada.eventType()).isEqualTo("MEMBERSHIP_ROLE_CHANGED");
			assertThat(entrada.previousState()).isEqualTo("PROFESIONAL");
			assertThat(entrada.newState()).isEqualTo("ADMINISTRATIVO");
			assertThat(entrada.reason()).isEqualTo(MOTIVO);
			assertThat(entrada.organizationId()).isEqualTo(ORG_ID);
			assertThat(entrada.actorAccountId()).isEqualTo(ACCOUNT_ID);
			assertThat(entrada.entityId()).isEqualTo(MEMBERSHIP_ID);
		}

		@Test
		@DisplayName("Pedir el mismo rol no audita nada: un no-op no puede ensuciar el historial")
		void el_mismo_rol_no_audita() {
			objetivo(deOtraCuenta(RoleCode.PROFESIONAL));

			service.changeRole(actor, ORG_ID, MEMBERSHIP_ID, "PROFESIONAL", false, null, MOTIVO);

			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("El cambio de alcance se audita aparte del cambio de rol")
		void el_alcance_se_audita_aparte() {
			// Mover a alguien de sede y cambiarle lo que puede hacer son dos decisiones, y
			// mezclarlas en un evento hace la auditoria ilegible.
			objetivo(deOtraCuenta(RoleCode.PROFESIONAL));

			service.changeRole(actor, ORG_ID, MEMBERSHIP_ID, null, true, CONSULTORIO_ID, MOTIVO);

			assertThat(auditoria().eventType()).isEqualTo("MEMBERSHIP_SCOPE_CHANGED");
		}

		@Test
		@DisplayName("No se puede mover una membership a una sede de otro tenant")
		void la_sede_tiene_que_ser_del_tenant() {
			// La clave foranea impediria una sede inexistente, no una AJENA.
			objetivo(deOtraCuenta(RoleCode.PROFESIONAL));
			given(consultorioRepository.findByIdAndOrganizationIdAndActiveTrue(999L, ORG_ID))
					.willReturn(Optional.empty());

			assertThatThrownBy(() -> service.changeRole(
					actor, ORG_ID, MEMBERSHIP_ID, null, true, 999L, MOTIVO))
					.isInstanceOf(OrganizationNotFoundException.class);
		}

		@Test
		@DisplayName("PLATFORM_ADMIN no se puede asignar a una membership")
		void no_se_asigna_platform_admin() {
			objetivo(deOtraCuenta(RoleCode.PROFESIONAL));

			assertThatThrownBy(() -> service.changeRole(
					actor, ORG_ID, MEMBERSHIP_ID, "PLATFORM_ADMIN", false, null, MOTIVO))
					.isInstanceOf(UnknownPermissionCodeException.class)
					.hasMessageContaining("platform_role");
		}

		@Test
		@DisplayName("Un rol inexistente es 400, no 500")
		void un_rol_inexistente_es_400() {
			objetivo(deOtraCuenta(RoleCode.PROFESIONAL));

			assertThatThrownBy(() -> service.changeRole(
					actor, ORG_ID, MEMBERSHIP_ID, "JEFE_SUPREMO", false, null, MOTIVO))
					.isInstanceOf(UnknownPermissionCodeException.class);
		}
	}

	// =================================================================================
	// Alta directa
	// =================================================================================

	@Nested
	@DisplayName("Alta directa")
	class AltaDirecta {

		@Test
		@DisplayName("Crea la membership y la audita con el motivo")
		void crea_y_audita() {
			given(membershipRepository.saveAndFlush(any()))
					.willAnswer(i -> Fixtures.conId(i.getArgument(0), MEMBERSHIP_ID));

			long id = service.createDirect(ACCOUNT_ID, false, ORG_ID,
					new DirectMembershipCommand(OTRA_CUENTA, CONSULTORIO_ID, "PROFESIONAL", MOTIVO));

			assertThat(id).isEqualTo(MEMBERSHIP_ID);
			AuditEntry entrada = auditoria();
			assertThat(entrada.eventType()).isEqualTo("MEMBERSHIP_CREATED");
			assertThat(entrada.newState()).isEqualTo("PROFESIONAL");
			assertThat(entrada.reason()).isEqualTo(MOTIVO);
			assertThat(entrada.details()).containsEntry("accountId", String.valueOf(OTRA_CUENTA));
		}

		@Test
		@DisplayName("Una clave duplicada es 409 y NO se vuelve a tocar JPA")
		void la_clave_duplicada_es_409() {
			// Una sesion de JPA reusada despues de un flush fallido tira AssertionFailure y
			// convierte un 409 legitimo en un 500. Por eso ni auditoria ni lecturas despues.
			given(membershipRepository.saveAndFlush(any()))
					.willThrow(new DataIntegrityViolationException("uk_membership_org_account_scope"));

			assertThatThrownBy(() -> service.createDirect(ACCOUNT_ID, false, ORG_ID,
					new DirectMembershipCommand(OTRA_CUENTA, null, "PROFESIONAL", MOTIVO)))
					.isInstanceOf(MembershipAlreadyExistsException.class);

			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("El alta exige motivo")
		void el_alta_exige_motivo() {
			assertThatThrownBy(() -> service.createDirect(ACCOUNT_ID, false, ORG_ID,
					new DirectMembershipCommand(OTRA_CUENTA, null, "PROFESIONAL", "")))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("No se da de alta un PLATFORM_ADMIN como membership")
		void no_se_da_de_alta_un_platform_admin() {
			assertThatThrownBy(() -> service.createDirect(ACCOUNT_ID, false, ORG_ID,
					new DirectMembershipCommand(OTRA_CUENTA, null, "PLATFORM_ADMIN", MOTIVO)))
					.isInstanceOf(UnknownPermissionCodeException.class);
		}

		@Test
		@DisplayName("La membership nace SIN is_founder: el fundador es uno solo y ya existe")
		void el_alta_directa_no_crea_fundadores() {
			ArgumentCaptor<Membership> captor = ArgumentCaptor.forClass(Membership.class);
			given(membershipRepository.saveAndFlush(any()))
					.willAnswer(i -> Fixtures.conId(i.getArgument(0), MEMBERSHIP_ID));

			service.createDirect(ACCOUNT_ID, false, ORG_ID,
					new DirectMembershipCommand(OTRA_CUENTA, null, "ORG_ADMIN", MOTIVO));

			verify(membershipRepository).saveAndFlush(captor.capture());
			assertThat(captor.getValue().isFounder()).isFalse();
			assertThat(captor.getValue().getEstado()).isEqualTo(MembershipEstado.ACTIVA);
		}
	}

	// =================================================================================
	// Permisos adicionales
	// =================================================================================

	@Nested
	@DisplayName("Permisos adicionales")
	class Grants {

		@Test
		@DisplayName("Otorgar un permiso adicional lo persiste y lo audita")
		void otorgar_persiste_y_audita() {
			objetivo(deOtraCuenta(RoleCode.CONSULTORIO_ADMIN));
			given(grantRepository.saveAndFlush(any()))
					.willAnswer(i -> Fixtures.conId(i.getArgument(0), 71L));

			MembershipGrantView vista = service.assignGrant(actor, ORG_ID, MEMBERSHIP_ID,
					"auditoria:read-clinica", "revision anual", null);

			assertThat(vista.permissionCode()).isEqualTo("auditoria:read-clinica");
			assertThat(vista.reason()).isEqualTo("revision anual");
			assertThat(auditoria().eventType()).isEqualTo("GRANT_ASSIGNED");
		}

		@Test
		@DisplayName("Un grant duplicado es 409 traducido de la clave, sin volver a tocar JPA")
		void un_grant_duplicado_es_409() {
			objetivo(deOtraCuenta(RoleCode.CONSULTORIO_ADMIN));
			given(grantRepository.saveAndFlush(any()))
					.willThrow(new DataIntegrityViolationException("uk_membership_grant_activo"));

			assertThatThrownBy(() -> service.assignGrant(actor, ORG_ID, MEMBERSHIP_ID,
					"auditoria:read-clinica", "revision", null))
					.isInstanceOf(GrantAlreadyActiveException.class);

			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("Un permiso fuera del catalogo es 400")
		void un_permiso_fuera_del_catalogo_es_400() {
			objetivo(deOtraCuenta(RoleCode.CONSULTORIO_ADMIN));

			assertThatThrownBy(() -> service.assignGrant(actor, ORG_ID, MEMBERSHIP_ID,
					"no:existe", "motivo", null))
					.isInstanceOf(UnknownPermissionCodeException.class);
		}

		@Test
		@DisplayName("Un permiso del catalogo que no es otorgable en F1 tambien es 400")
		void un_permiso_no_otorgable_en_f1_es_400() {
			// La matriz §6 marca un solo "No por defecto (grant)" en la fase. Escribir la fila
			// igual dejaria un permiso que el evaluador nunca va a mirar: peor que un rechazo.
			objetivo(deOtraCuenta(RoleCode.CONSULTORIO_ADMIN));

			assertThatThrownBy(() -> service.assignGrant(actor, ORG_ID, MEMBERSHIP_ID,
					"tenant:manage", "motivo", null))
					.isInstanceOf(UnknownPermissionCodeException.class)
					.hasMessageContaining("no es otorgable");
		}

		@Test
		@DisplayName("No se otorgan permisos sobre una membership que ya no esta activa")
		void no_se_otorga_sobre_una_inactiva() {
			Membership revocada = deOtraCuenta(RoleCode.PROFESIONAL);
			revocada.revocar(ACCOUNT_ID, "baja", Instant.now().minusSeconds(10));
			objetivo(revocada);

			assertThatThrownBy(() -> service.assignGrant(actor, ORG_ID, MEMBERSHIP_ID,
					"auditoria:read-clinica", "motivo", null))
					.isInstanceOf(MembershipNotActiveException.class);
		}

		@Test
		@DisplayName("Revocar un grant vigente lo da de baja logica y lo audita")
		void revocar_un_grant_vigente() {
			objetivo(deOtraCuenta(RoleCode.CONSULTORIO_ADMIN));
			MembershipGrant grant = new MembershipGrant(ORG_ID, MEMBERSHIP_ID,
					PermissionCode.AUDITORIA_READ_CLINICA, ACCOUNT_ID, "alta",
					Instant.now().minus(1, ChronoUnit.DAYS), null);
			given(grantRepository.findByMembershipIdAndPermissionCodeAndActiveTrue(
					MEMBERSHIP_ID, PermissionCode.AUDITORIA_READ_CLINICA))
					.willReturn(Optional.of(grant));

			service.revokeGrant(actor, ORG_ID, MEMBERSHIP_ID, "auditoria:read-clinica", MOTIVO);

			assertThat(grant.isActive()).isFalse();
			assertThat(auditoria().eventType()).isEqualTo("GRANT_REVOKED");
		}

		@Test
		@DisplayName("Revocar un grant que ya no esta es un no-op, no un 409")
		void revocar_un_grant_ausente_es_no_op() {
			// "No lo tenia" y "se lo acaban de quitar" son indistinguibles para el usuario: un
			// 409 lo obligaria a distinguir dos situaciones que significan lo mismo.
			objetivo(deOtraCuenta(RoleCode.CONSULTORIO_ADMIN));
			given(grantRepository.findByMembershipIdAndPermissionCodeAndActiveTrue(any(), any()))
					.willReturn(Optional.empty());

			service.revokeGrant(actor, ORG_ID, MEMBERSHIP_ID, "auditoria:read-clinica", MOTIVO);

			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("Revocar un codigo inexistente es 400")
		void revocar_un_codigo_inexistente_es_400() {
			objetivo(deOtraCuenta(RoleCode.CONSULTORIO_ADMIN));

			assertThatThrownBy(() -> service.revokeGrant(
					actor, ORG_ID, MEMBERSHIP_ID, "no:existe", MOTIVO))
					.isInstanceOf(UnknownPermissionCodeException.class);
		}
	}

	// =================================================================================
	// Lecturas y uso de soporte
	// =================================================================================

	@Test
	@DisplayName("El listado incluye las memberships revocadas: la baja logica existe para eso")
	void el_listado_incluye_las_revocadas() {
		Membership revocada = deOtraCuenta(RoleCode.PROFESIONAL);
		revocada.revocar(ACCOUNT_ID, "baja", Instant.now().minusSeconds(10));
		given(membershipRepository.findAllByOrganizationId(
				org.mockito.ArgumentMatchers.eq(ORG_ID), any()))
				.willReturn(new org.springframework.data.domain.PageImpl<>(List.of(revocada)));

		var pagina = service.list(actor, ORG_ID,
				org.springframework.data.domain.PageRequest.of(0, 20));

		assertThat(pagina.getContent()).hasSize(1);
		assertThat(pagina.getContent().get(0).estado()).isEqualTo("REVOCADA");
		assertThat(pagina.getContent().get(0).revokedReason()).isEqualTo("baja");
	}

	@Test
	@DisplayName("Una LECTURA amparada por soporte audita por fuera de la transaccion de lectura")
	void el_uso_del_soporte_en_una_lectura_se_audita_aparte() {
		// La matriz §7 exige auditar CADA operacion amparada, no solo el otorgamiento.
		//
		// Este test antes verificaba `auditTrail.record(...)` y pasaba en verde con la fila
		// perdida: las lecturas son readOnly, ahi Hibernate deja el flush en MANUAL y el insert
		// nunca llegaba a la base. Por eso ahora verifica el auditor de transaccion propia, y
		// por eso la garantia de verdad —que la fila EXISTE— se afirma contra MySQL en
		// SoporteEnLecturasIT. Con mocks no se puede distinguir una de la otra.
		given(permissionGuard.requirePermission(any())).willReturn(
				PermissionDecision.concedida(PermissionScope.GLOBAL.name(), true));
		given(membershipRepository.findByIdAndOrganizationId(MEMBERSHIP_ID, ORG_ID))
				.willReturn(Optional.of(deOtraCuenta(RoleCode.PROFESIONAL)));

		service.find(new OperatingActor(ACCOUNT_ID, true, null), ORG_ID, MEMBERSHIP_ID);

		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(supportAccessReadAuditor).record(captor.capture());
		assertThat(captor.getValue().eventType()).isEqualTo("SUPPORT_ACCESS_USED");
		verifyNoInteractions(auditTrail);
	}
}
