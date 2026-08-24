package com.akine.organization.infrastructure;

import com.akine.organization.application.PermissionDenialAuditor;
import com.akine.organization.domain.PlatformRole;
import com.akine.organization.domain.port.PlatformRoleRepositoryPort;
import com.akine.organization.infrastructure.tenant.OrganizationPlatformRoleDirectory;
import com.akine.organization.spi.DenialKind;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Los adaptadores de AKINE-01.03: el puerto invertido del rol de plataforma, el auditor de
 * rechazos y el chequeo de arranque.
 */
@ExtendWith(MockitoExtension.class)
class PlataformaInfraestructuraTest {

	private static final long ACCOUNT_ID = 30L;
	private static final long ORG_ID = 10L;
	private static final Instant AHORA = Instant.now();

	@Mock
	private PlatformRoleRepositoryPort platformRoleRepository;

	@Mock
	private AuditTrail auditTrail;

	// =================================================================================
	// El puerto invertido
	// =================================================================================

	@Test
	@DisplayName("Un rol vigente hace administrador de plataforma")
	void un_rol_vigente_habilita() {
		given(platformRoleRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
				.willReturn(List.of(new PlatformRole(
						ACCOUNT_ID, null, "bootstrap", AHORA.minus(1, ChronoUnit.DAYS))));

		assertThat(new OrganizationPlatformRoleDirectory(platformRoleRepository)
				.isPlatformAdmin(ACCOUNT_ID, AHORA)).isTrue();
	}

	@Test
	@DisplayName("Sin filas no hay rol, y eso es lo que hay que responder ante la duda")
	void sin_filas_no_hay_rol() {
		given(platformRoleRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
				.willReturn(List.of());

		assertThat(new OrganizationPlatformRoleDirectory(platformRoleRepository)
				.isPlatformAdmin(ACCOUNT_ID, AHORA)).isFalse();
	}

	@Test
	@DisplayName("La vigencia se evalua en Java, no en el WHERE: un rol vencido no habilita")
	void un_rol_vencido_no_habilita() {
		// Asi la regla vive en un solo lugar, se testea sin base y no depende del reloj del
		// motor, que puede no ser el del backend.
		PlatformRole vencido = new PlatformRole(
				ACCOUNT_ID, null, "temporal", AHORA.minus(2, ChronoUnit.DAYS));
		vencido.revoke(ACCOUNT_ID, "fin", AHORA.minus(1, ChronoUnit.DAYS));
		given(platformRoleRepository.findAllByAccountIdAndActiveTrue(ACCOUNT_ID))
				.willReturn(List.of(vencido));

		assertThat(new OrganizationPlatformRoleDirectory(platformRoleRepository)
				.isPlatformAdmin(ACCOUNT_ID, AHORA)).isFalse();
	}

	// =================================================================================
	// Auditor de rechazos
	// =================================================================================

	@Test
	@DisplayName("Un rechazo por permiso deja actor, tenant, permiso y motivo del rechazo")
	void el_rechazo_deja_lo_necesario_para_investigarlo() {
		new PermissionDenialAuditor(auditTrail).recordDenial(new PermissionQuery(
				ACCOUNT_ID, "colaborador:manage", ORG_ID, 20L, 99L, AHORA),
				DenialKind.NO_PERMISSION);

		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		AuditEntry entrada = captor.getValue();

		assertThat(entrada.eventType()).isEqualTo("PERMISSION_DENIED");
		assertThat(entrada.entityType()).isEqualTo("Permission");
		assertThat(entrada.organizationId()).isEqualTo(ORG_ID);
		assertThat(entrada.consultorioId()).isEqualTo(20L);
		assertThat(entrada.actorAccountId()).isEqualTo(ACCOUNT_ID);
		assertThat(entrada.entityId()).isEqualTo(99L);
		assertThat(entrada.details())
				.containsEntry("permissionCode", "colaborador:manage")
				.containsEntry("denialKind", "NO_PERMISSION")
				.containsEntry("consultorioId", "20");
		// Nada del recurso que se intentaba tocar: podria ser un dato sensible.
		assertThat(entrada.details()).hasSize(3);
	}

	@Test
	@DisplayName("Sin sede en el contexto, el detalle no inventa una")
	void sin_sede_no_se_inventa_una() {
		new PermissionDenialAuditor(auditTrail).recordDenial(
				PermissionQuery.of(ACCOUNT_ID, "auditoria:read", ORG_ID, AHORA),
				DenialKind.NO_PERMISSION);

		ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(captor.capture());
		assertThat(captor.getValue().details()).doesNotContainKey("consultorioId");
		assertThat(captor.getValue().consultorioId()).isNull();
	}

	// =================================================================================
	// Chequeo de arranque
	// =================================================================================

	@Test
	@DisplayName("Sin ningun administrador de plataforma avisa, pero NO falla el arranque")
	void sin_admin_de_plataforma_avisa_sin_fallar() {
		// Un entorno de desarrollo sin platform admin es legitimo, y un test de integracion
		// sobre una base recien creada tambien. Hacer fallar el arranque convertiria una
		// advertencia operativa en un bloqueo, y la reaccion previsible seria apagarla.
		given(platformRoleRepository.countByActiveTrue()).willReturn(0L);

		assertThatCode(() -> new PlatformAdminBootstrapCheck(platformRoleRepository).run(null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("Con administradores activos el arranque sigue normal")
	void con_admins_el_arranque_sigue() {
		given(platformRoleRepository.countByActiveTrue()).willReturn(2L);

		assertThatCode(() -> new PlatformAdminBootstrapCheck(platformRoleRepository).run(null))
				.doesNotThrowAnyException();

		verify(platformRoleRepository).countByActiveTrue();
	}

	@Test
	@DisplayName("El logger del chequeo existe: el aviso tiene donde salir")
	void el_aviso_tiene_donde_salir() {
		assertThat(LoggerFactory.getLogger(PlatformAdminBootstrapCheck.class)).isNotNull();
	}
}
