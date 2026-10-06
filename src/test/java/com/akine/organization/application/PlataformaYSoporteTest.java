package com.akine.organization.application;

import com.akine.organization.domain.PlatformRole;
import com.akine.organization.domain.SupportAccess;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.PlatformRoleNotFoundException;
import com.akine.organization.domain.exception.SupportAccessNotFoundException;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.domain.port.PlatformRoleRepositoryPort;
import com.akine.organization.domain.port.SupportAccessRepositoryPort;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
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
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.akine.organization.application.Fixtures.ACCOUNT_ID;
import static com.akine.organization.application.Fixtures.ORG_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Rol de plataforma y acceso de soporte: las dos tablas que deciden quien opera por encima de
 * los tenants y quien entra a uno.
 *
 * <p>Lo que se prueba en las dos es lo mismo: <b>que el rol se revalide contra la base y no se
 * crea del booleano que llega de la capa web</b>, y que ninguna de las dos operaciones pueda
 * ocurrir sin motivo y sin quedar auditada. Son las dos puertas mas sensibles del sistema.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlataformaYSoporteTest {

	private static final long OTRA_CUENTA = 31L;
	private static final Instant AYER = Instant.now().minus(1, ChronoUnit.DAYS);

	@Mock
	private PermissionEvaluator permissionEvaluator;

	@Mock
	private AuditTrail auditTrail;

	private AuditEntry auditoria() {
		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		return captor.getValue();
	}

	// =================================================================================
	// Acceso de soporte
	// =================================================================================

	@Nested
	@DisplayName("Acceso de soporte")
	@ExtendWith(MockitoExtension.class)
	class Soporte {

		@Mock
		private SupportAccessRepositoryPort supportAccessRepository;

		@Mock
		private OrganizationRepositoryPort organizationRepository;

		/**
		 * El stub de {@code save} se registra UNA sola vez.
		 *
		 * <p>Registrarlo dentro de un metodo de apoyo que el test llama varias veces lo rompe:
		 * {@code given(mock.save(any()))} invoca el mock para grabar la stub, y esa invocacion pasa
		 * por el answer ya registrado con el argumento en {@code null}. Se manifiesta como un
		 * IllegalArgumentException de reflexion a tres saltos de distancia de la causa.
		 */
		@org.junit.jupiter.api.BeforeEach
		void stubsDeLaBase() {
			given(supportAccessRepository.save(any()))
					.willAnswer(i -> Fixtures.conId(i.getArgument(0), 7L));
			given(organizationRepository.findByIdAndActiveTrue(ORG_ID))
					.willReturn(Optional.of(Fixtures.organizacion()));
		}

		private SupportAccessService servicio() {
			return new SupportAccessService(
					supportAccessRepository, organizationRepository, permissionEvaluator, auditTrail);
		}

		private void esAdminDePlataforma(boolean loEs) {
			given(permissionEvaluator.isPlatformAdmin(anyLong(), any())).willReturn(loEs);
		}

		@Test
		@DisplayName("El rol se revalida contra la base, no se cree de la capa web")
		void el_rol_se_revalida() {
			// Esta es la operacion que abre la puerta a los datos de un tenant: la comprobacion
			// tiene que ser de primera mano y no un booleano que llego de arriba.
			esAdminDePlataforma(false);

			assertThatThrownBy(() -> servicio().grant(ACCOUNT_ID, ORG_ID, "incidente", null))
					.isInstanceOf(AccessDeniedException.class);

			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("Sin motivo no hay acceso de soporte")
		void sin_motivo_no_hay_soporte() {
			esAdminDePlataforma(true);

			assertThatThrownBy(() -> servicio().grant(ACCOUNT_ID, ORG_ID, "   ", null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("motivo");
		}

		@Test
		@DisplayName("Sobre una organizacion inexistente o dada de baja es 404")
		void sobre_una_organizacion_inexistente_es_404() {
			esAdminDePlataforma(true);
			given(organizationRepository.findByIdAndActiveTrue(ORG_ID)).willReturn(Optional.empty());

			SupportAccessService servicio = new SupportAccessService(
					supportAccessRepository, organizationRepository, permissionEvaluator, auditTrail);

			assertThatThrownBy(() -> servicio.grant(ACCOUNT_ID, ORG_ID, "incidente", null))
					.isInstanceOf(OrganizationNotFoundException.class);
		}

		@Test
		@DisplayName("Por defecto dura cuatro horas (decision D-3) y queda auditado en el tenant")
		void dura_cuatro_horas_y_se_audita_en_el_tenant() {
			esAdminDePlataforma(true);

			SupportAccessView vista = servicio().grant(ACCOUNT_ID, ORG_ID, "incidente 42", null);

			assertThat(Duration.between(vista.validFrom(), vista.validUntil()).toHours())
					.isEqualTo(4);
			assertThat(vista.reason()).isEqualTo("incidente 42");
			assertThat(vista.grantedByAccountId()).isEqualTo(ACCOUNT_ID);

			AuditEntry entrada = auditoria();
			assertThat(entrada.eventType()).isEqualTo("SUPPORT_ACCESS_GRANTED");
			// organizationId poblado: es lo que hace que el ORG_ADMIN del tenant vea el acceso en
			// su propia auditoria. Con null seria un evento de plataforma y el tenant no se
			// enteraria de que alguien entro a sus datos.
			assertThat(entrada.organizationId()).isEqualTo(ORG_ID);
			assertThat(entrada.reason()).isEqualTo("incidente 42");
			assertThat(entrada.details()).containsKey("validUntil");
		}

		@Test
		@DisplayName("Se puede pedir menos de cuatro horas, nunca mas")
		void el_tope_de_duracion_es_innegociable() {
			esAdminDePlataforma(true);

			assertThat(Duration.between(
					servicio().grant(ACCOUNT_ID, ORG_ID, "corto", Duration.ofHours(1)).validFrom(),
					servicio().grant(ACCOUNT_ID, ORG_ID, "corto", Duration.ofHours(1)).validUntil())
					.toHours()).isEqualTo(1);

			// Dejar elegir la duracion sin tope convertiria "acotado en tiempo" en "acotado si
			// el que entra quiere".
			assertThatThrownBy(() -> servicio().grant(
					ACCOUNT_ID, ORG_ID, "largo", Duration.ofDays(1)))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> servicio().grant(
					ACCOUNT_ID, ORG_ID, "nulo", Duration.ZERO))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> servicio().grant(
					ACCOUNT_ID, ORG_ID, "negativo", Duration.ofHours(-1)))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Revocar cierra el acceso sin borrarlo y lo audita")
		void revocar_cierra_sin_borrar() {
			esAdminDePlataforma(true);
			SupportAccess acceso = Fixtures.conId(new SupportAccess(ORG_ID, ACCOUNT_ID, "incidente",
					ACCOUNT_ID, AYER, Instant.now().plus(1, ChronoUnit.HOURS)), 7L);
			given(supportAccessRepository.findByIdAndActiveTrue(7L)).willReturn(Optional.of(acceso));

			new SupportAccessService(supportAccessRepository, organizationRepository,
					permissionEvaluator, auditTrail).revoke(ACCOUNT_ID, 7L);

			assertThat(acceso.getRevokedAt()).isNotNull();
			assertThat(auditoria().eventType()).isEqualTo("SUPPORT_ACCESS_REVOKED");
		}

		@Test
		@DisplayName("Revocar uno inexistente es 404")
		void revocar_uno_inexistente_es_404() {
			esAdminDePlataforma(true);
			given(supportAccessRepository.findByIdAndActiveTrue(anyLong()))
					.willReturn(Optional.empty());

			SupportAccessService servicio = new SupportAccessService(
					supportAccessRepository, organizationRepository, permissionEvaluator, auditTrail);

			assertThatThrownBy(() -> servicio.revoke(ACCOUNT_ID, 7L))
					.isInstanceOf(SupportAccessNotFoundException.class);
		}

		@Test
		@DisplayName("Listar y revocar tambien exigen el rol de plataforma")
		void listar_y_revocar_exigen_el_rol() {
			esAdminDePlataforma(false);
			SupportAccessService servicio = new SupportAccessService(
					supportAccessRepository, organizationRepository, permissionEvaluator, auditTrail);

			assertThatThrownBy(() -> servicio.list(ACCOUNT_ID, ORG_ID))
					.isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> servicio.revoke(ACCOUNT_ID, 7L))
					.isInstanceOf(AccessDeniedException.class);
		}

		@Test
		@DisplayName("El listado muestra motivo y vencimiento: sin eso no se puede revisar")
		void el_listado_muestra_lo_revisable() {
			esAdminDePlataforma(true);
			given(supportAccessRepository.findAllByOrganizationIdAndActiveTrue(ORG_ID))
					.willReturn(List.of(Fixtures.conId(new SupportAccess(ORG_ID, ACCOUNT_ID,
							"incidente 9", ACCOUNT_ID, AYER,
							Instant.now().plus(1, ChronoUnit.HOURS)), 9L)));

			List<SupportAccessView> vistas = new SupportAccessService(supportAccessRepository,
					organizationRepository, permissionEvaluator, auditTrail)
					.list(ACCOUNT_ID, ORG_ID);

			assertThat(vistas).singleElement()
					.satisfies(v -> {
						assertThat(v.reason()).isEqualTo("incidente 9");
						assertThat(v.validUntil()).isNotNull();
						assertThat(v.revokedAt()).isNull();
						assertThat(v.active()).isTrue();
					});
		}
	}

	// =================================================================================
	// Rol de plataforma
	// =================================================================================

	@Nested
	@DisplayName("Rol de plataforma")
	@ExtendWith(MockitoExtension.class)
	class RolDePlataforma {

		@Mock
		private PlatformRoleRepositoryPort platformRoleRepository;

		@Mock
		private AccountIdentityDirectory cuentas;

		@InjectMocks
		private PlatformRoleService servicio;

		private PlatformRoleService servicioReal() {
			return new PlatformRoleService(
					platformRoleRepository, permissionEvaluator, auditTrail, cuentas);
		}

		private void laCuentaDestinoExiste() {
			given(cuentas.identidadesDe(List.of(OTRA_CUENTA))).willReturn(Map.of(OTRA_CUENTA,
					new AccountIdentity(OTRA_CUENTA, "Sintetica DePrueba", "otra@ejemplo.test")));
		}

		private void esAdminDePlataforma(boolean loEs) {
			given(permissionEvaluator.isPlatformAdmin(anyLong(), any())).willReturn(loEs);
		}

		@Test
		@DisplayName("Solo un administrador de plataforma otorga, revoca y lista")
		void solo_plataforma_administra_plataforma() {
			esAdminDePlataforma(false);
			PlatformRoleService s = servicioReal();

			assertThatThrownBy(() -> s.grant(ACCOUNT_ID, OTRA_CUENTA, "motivo"))
					.isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> s.revoke(ACCOUNT_ID, 7L, "motivo"))
					.isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> s.list(ACCOUNT_ID))
					.isInstanceOf(AccessDeniedException.class);
		}

		@Test
		@DisplayName("Otorgar exige motivo y audita SIN tenant: es un evento de plataforma")
		void otorgar_audita_sin_tenant() {
			esAdminDePlataforma(true);
			laCuentaDestinoExiste();
			given(platformRoleRepository.saveAndFlush(any()))
					.willAnswer(i -> Fixtures.conId(i.getArgument(0), 5L));

			PlatformRoleView vista = servicioReal().grant(ACCOUNT_ID, OTRA_CUENTA, "alta de soporte");

			assertThat(vista.accountId()).isEqualTo(OTRA_CUENTA);
			assertThat(vista.roleCode()).isEqualTo("PLATFORM_ADMIN");
			assertThat(vista.grantedByAccountId()).isEqualTo(ACCOUNT_ID);

			AuditEntry entrada = auditoria();
			assertThat(entrada.eventType()).isEqualTo("PLATFORM_ROLE_GRANTED");
			// Elegir un tenant cualquiera para rellenar la columna haria que el evento
			// apareciera en la auditoria de un cliente que no tuvo nada que ver.
			assertThat(entrada.organizationId()).isNull();
			assertThat(entrada.reason()).isEqualTo("alta de soporte");
			assertThat(entrada.details())
					.containsEntry("targetAccountId", String.valueOf(OTRA_CUENTA));
		}

		@Test
		@DisplayName("Sin motivo no se otorga el permiso mas alto del sistema")
		void sin_motivo_no_se_otorga() {
			esAdminDePlataforma(true);

			assertThatThrownBy(() -> servicioReal().grant(ACCOUNT_ID, OTRA_CUENTA, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("A una cuenta que no existe no se le otorga: 400, sin fila y sin auditoria")
		void cuenta_inexistente_no_se_otorga() {
			esAdminDePlataforma(true);
			given(cuentas.identidadesDe(List.of(OTRA_CUENTA))).willReturn(Map.of());

			assertThatThrownBy(() -> servicioReal().grant(ACCOUNT_ID, OTRA_CUENTA, "motivo"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("no existe");
			verifyNoInteractions(platformRoleRepository, auditTrail);
		}

		@Test
		@DisplayName("Otorgarlo dos veces choca contra el unique y no es un 500")
		void otorgarlo_dos_veces_es_conflicto() {
			esAdminDePlataforma(true);
			laCuentaDestinoExiste();
			given(platformRoleRepository.saveAndFlush(any()))
					.willThrow(new DataIntegrityViolationException("uk_platform_role_activo"));

			assertThatThrownBy(() -> servicioReal().grant(ACCOUNT_ID, OTRA_CUENTA, "otra vez"))
					.isInstanceOf(IllegalStateException.class);

			// Y no se audita algo que no paso.
			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("Revocar deja la fila, cierra la vigencia y audita")
		void revocar_deja_la_fila() {
			esAdminDePlataforma(true);
			PlatformRole rol = Fixtures.conId(
					new PlatformRole(OTRA_CUENTA, ACCOUNT_ID, "alta", AYER), 5L);
			given(platformRoleRepository.findByIdAndActiveTrue(5L)).willReturn(Optional.of(rol));

			servicioReal().revoke(ACCOUNT_ID, 5L, "baja del equipo");

			assertThat(rol.isActive()).isFalse();
			assertThat(auditoria().eventType()).isEqualTo("PLATFORM_ROLE_REVOKED");
		}

		@Test
		@DisplayName("Revocar uno inexistente es 404")
		void revocar_uno_inexistente_es_404() {
			esAdminDePlataforma(true);
			given(platformRoleRepository.findByIdAndActiveTrue(anyLong()))
					.willReturn(Optional.empty());

			assertThatThrownBy(() -> servicioReal().revoke(ACCOUNT_ID, 5L, "motivo"))
					.isInstanceOf(PlatformRoleNotFoundException.class);
		}

		@Test
		@DisplayName("Se puede revocar el ULTIMO rol de plataforma, y es deliberado")
		void se_puede_revocar_el_ultimo() {
			// El invariante de "ultimo admin" es del tenant, donde quedarse sin administrador
			// deja a un cliente encerrado afuera. En la plataforma el rescate existe —el seed de
			// migracion, reproducible— y un invariante aca impediria revocar de urgencia una
			// cuenta comprometida, que es cuando revocar mas importa.
			esAdminDePlataforma(true);
			PlatformRole unico = Fixtures.conId(
					new PlatformRole(ACCOUNT_ID, null, "bootstrap", AYER), 5L);
			given(platformRoleRepository.findByIdAndActiveTrue(5L)).willReturn(Optional.of(unico));

			servicioReal().revoke(ACCOUNT_ID, 5L, "cuenta comprometida");

			assertThat(unico.isActive()).isFalse();
		}

		@Test
		@DisplayName("El listado devuelve los vigentes con su motivo")
		void el_listado_devuelve_los_vigentes() {
			esAdminDePlataforma(true);
			given(platformRoleRepository.findAllByActiveTrue())
					.willReturn(List.of(Fixtures.conId(
							new PlatformRole(OTRA_CUENTA, ACCOUNT_ID, "soporte nivel 2", AYER), 5L)));

			assertThat(servicioReal().list(ACCOUNT_ID)).singleElement()
					.satisfies(v -> {
						assertThat(v.accountId()).isEqualTo(OTRA_CUENTA);
						assertThat(v.reason()).isEqualTo("soporte nivel 2");
						assertThat(v.active()).isTrue();
					});
		}
	}
}
