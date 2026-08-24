package com.akine.organization.application;

import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.PermissionDeniedException;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.tenant.TenantOperationalStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static com.akine.organization.application.Fixtures.ACCOUNT_ID;
import static com.akine.organization.application.Fixtures.ORG_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * El punto unico de autorizacion de la capa {@code api} del modulo, ya sobre el evaluador de la
 * matriz (AKINE-01.03).
 *
 * <p>Lo que se prueba aca es <b>la traduccion</b>: que cada firma pida el permiso correcto, que
 * el contexto se compare siempre, y que los dos codigos que no se pueden confundir sigan
 * saliendo por el lado correcto. Que un rol concreto tenga o no un permiso <b>no se prueba
 * aca</b>: eso es {@code PermissionEvaluatorServiceTest}, y duplicarlo haria que la matriz
 * viviera en dos lugares.
 *
 * <p>El test que sostiene el aislamiento cross-tenant es
 * {@link #ser_org_admin_en_otro_tenant_no_alcanza()}: sin la comparacion contra el contexto
 * validado, un {@code ORG_ADMIN} de la organizacion A obtendria acceso administrativo poniendo
 * el id de A en la URL mientras opera con un token acotado a B (RN-M01-003).
 */
@ExtendWith(MockitoExtension.class)
class AuthorizationGuardTest {

	private static final long OTRA_ORG_ID = 11L;
	private static final long CONSULTORIO_ID = 77L;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AccountContextService accountContextService;

	@Mock
	private TenantContextHolder tenantContextHolder;

	@Mock
	private SupportAccessReadAuditor supportAccessReadAuditor;

	@InjectMocks
	private AuthorizationGuard guard;

	/** Concesion de un rol de tenant: nunca se apoya en soporte. */
	private void conPermiso() {
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("ORGANIZACION", false));
	}

	// ------------------------------------------------------------------ PLATFORM_ADMIN

	@Test
	@DisplayName("PLATFORM_ADMIN se resuelve por el flag del principal, sin consultar tablas")
	void platform_admin_es_un_flag() {
		assertThatCode(() -> guard.requirePlatformAdmin(true)).doesNotThrowAnyException();
		verifyNoInteractions(permissionGuard, accountContextService);
	}

	@Test
	@DisplayName("Sin el flag de plataforma la operacion reservada se rechaza")
	void sin_flag_de_plataforma_se_rechaza() {
		assertThatThrownBy(() -> guard.requirePlatformAdmin(false))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("PLATFORM_ADMIN tambien pasa por el evaluador: ya no hay atajo sin rastro")
	void platform_admin_tambien_se_evalua() {
		// Este test reemplaza a uno que afirmaba lo contrario ("administra cualquier
		// organizacion SIN evaluar permisos") y que sostenia el agujero: con el `return`
		// temprano, un administrador de plataforma leia el perfil y las sedes de cualquier
		// tenant sin support_access y sin dejar una sola fila de auditoria.
		conPermiso();

		guard.requireOrgAdmin(ACCOUNT_ID, ORG_ID, null, true);
		guard.requireMember(ACCOUNT_ID, ORG_ID, null, true);

		ArgumentCaptor<PermissionQuery> pedido = ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard, times(2)).requirePermission(pedido.capture());
		assertThat(pedido.getAllValues())
				.allSatisfy(q -> assertThat(q.permissionCode()).isEqualTo("tenant:read"));

		// Lo unico que se saltea es la comparacion de contexto: no tiene membership en ningun
		// tenant (matriz §1.3) y por lo tanto tampoco contexto de trabajo. Quien decide si pasa
		// es el evaluador, donde tenant:read es de alcance SOPORTE para su rol.
		verifyNoInteractions(accountContextService);
	}

	@Test
	@DisplayName("El uso de soporte deja SUPPORT_ACCESS_USED, y por transaccion propia")
	void el_uso_de_soporte_se_registra() {
		// La matriz §7 pide auditar CADA operacion amparada por soporte. Va por el auditor de
		// transaccion propia y no por el AuditTrail: estos metodos son readOnly y ahi el flush
		// de Hibernate queda en MANUAL. Que la fila LLEGUE a la base lo prueba
		// SoporteEnLecturasIT contra MySQL real; aca solo se prueba que se delega.
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("SOPORTE", true));

		guard.requireMember(ACCOUNT_ID, ORG_ID, null, true);

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(supportAccessReadAuditor).record(entrada.capture());
		assertThat(entrada.getValue().eventType()).isEqualTo("SUPPORT_ACCESS_USED");
		assertThat(entrada.getValue().organizationId()).isEqualTo(ORG_ID);
		assertThat(entrada.getValue().actorAccountId()).isEqualTo(ACCOUNT_ID);
		assertThat(entrada.getValue().details()).containsEntry("permissionCode", "tenant:read");
	}

	@Test
	@DisplayName("Sin soporte no hay fila: la decision del evaluador es la que manda")
	void sin_soporte_no_se_registra_nada() {
		conPermiso();

		guard.requireOrgAdmin(ACCOUNT_ID, ORG_ID, ORG_ID, false);

		verifyNoInteractions(supportAccessReadAuditor);
	}

	// ------------------------------------------------------------------------ ORG_ADMIN

	@Test
	@DisplayName("Administrar la organizacion se traduce a tenant:read con alcance organizacion")
	void administrar_pide_tenant_read() {
		conPermiso();
		guard.requireOrgAdmin(ACCOUNT_ID, ORG_ID, ORG_ID, false);

		ArgumentCaptor<PermissionQuery> pedido = ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard).requirePermission(pedido.capture());

		assertThat(pedido.getValue().permissionCode()).isEqualTo("tenant:read");
		assertThat(pedido.getValue().accountId()).isEqualTo(ACCOUNT_ID);
		assertThat(pedido.getValue().organizationId()).isEqualTo(ORG_ID);
		// Sin consultorio: la decision es sobre el tenant entero, y con consultorio una
		// membership acotada a una sede podria administrarlo. Es la escalada que hay que evitar.
		assertThat(pedido.getValue().consultorioId()).isNull();
	}

	@Test
	@DisplayName("Administrar desde el contexto de otra organizacion responde 404, no 403")
	void ser_org_admin_en_otro_tenant_no_alcanza() {
		// 403 confirmaria que ORG_ID existe: bastarian ids consecutivos para enumerar clientes.
		assertThatThrownBy(() -> guard.requireOrgAdmin(ACCOUNT_ID, ORG_ID, OTRA_ORG_ID, false))
				.isInstanceOf(OrganizationNotFoundException.class);

		// Ni siquiera se evalua el permiso: el contexto ya decidio.
		verifyNoInteractions(permissionGuard);
	}

	@Test
	@DisplayName("Un request sin contexto validado no puede administrar ninguna organizacion")
	void sin_contexto_no_administra() {
		// Aca si es 403 y no 404: no eligio donde trabaja todavia, y el frontend necesita
		// distinguir "elegi un consultorio" de "eso no existe".
		assertThatThrownBy(() -> guard.requireOrgAdmin(ACCOUNT_ID, ORG_ID, null, false))
				.isInstanceOf(AccessDeniedException.class)
				.hasMessageContaining("contexto de trabajo activo");

		verifyNoInteractions(permissionGuard);
	}

	@Test
	@DisplayName("El rechazo del evaluador se propaga tal cual: el guard no lo reinterpreta")
	void el_rechazo_del_evaluador_se_propaga() {
		willThrow(new PermissionDeniedException("tenant:read", ACCOUNT_ID, ORG_ID))
				.given(permissionGuard).requirePermission(any());

		assertThatThrownBy(() -> guard.requireOrgAdmin(ACCOUNT_ID, ORG_ID, ORG_ID, false))
				.isInstanceOf(PermissionDeniedException.class);
	}

	// --------------------------------------------------------------------- requireMember

	@Test
	@DisplayName("Un miembro vigente accede a su organizacion, con cualquier rol (enmienda a la matriz)")
	void un_miembro_vigente_accede() {
		// La matriz seccion 6 da tenant:read solo a ORG_ADMIN. Esta lectura basica esta
		// habilitada para cualquier miembro vigente por la enmienda declarada en
		// docs/seguridad/matriz-permisos-minima.md: sin ella, un PROFESIONAL no puede ver el
		// nombre de su organizacion ni sus sedes, y el selector de contexto no funciona.
		given(accountContextService.hasActiveMembership(ACCOUNT_ID, ORG_ID)).willReturn(true);

		assertThatCode(() -> guard.requireMember(ACCOUNT_ID, ORG_ID, ORG_ID, false))
				.doesNotThrowAnyException();

		// Y no pasa por el evaluador: la enmienda es sobre pertenencia, no sobre permisos.
		verifyNoInteractions(permissionGuard);
	}

	@Test
	@DisplayName("Pedir una organizacion ajena responde 404, no 403: 403 confirmaria que existe")
	void una_organizacion_ajena_responde_404() {
		assertThatThrownBy(() -> guard.requireMember(ACCOUNT_ID, ORG_ID, OTRA_ORG_ID, false))
				.isInstanceOf(OrganizationNotFoundException.class);

		verifyNoInteractions(accountContextService);
	}

	@Test
	@DisplayName("Un request sin contexto no accede a ninguna organizacion")
	void sin_contexto_no_accede() {
		assertThatThrownBy(() -> guard.requireMember(ACCOUNT_ID, ORG_ID, null, false))
				.isInstanceOf(OrganizationNotFoundException.class);
	}

	@Test
	@DisplayName("Con el contexto correcto pero sin membership vigente tambien es 404")
	void sin_membership_vigente_es_404() {
		given(accountContextService.hasActiveMembership(ACCOUNT_ID, ORG_ID)).willReturn(false);

		assertThatThrownBy(() -> guard.requireMember(ACCOUNT_ID, ORG_ID, ORG_ID, false))
				.isInstanceOf(OrganizationNotFoundException.class);
	}

	// ------------------------------------------------------------ sede del contexto

	@Test
	@DisplayName("La sede sale del contexto ya revalidado, nunca de un parametro del cliente")
	void la_sede_sale_del_contexto() {
		given(tenantContextHolder.current()).willReturn(Optional.of(new RequestTenantContext(
				ACCOUNT_ID, ORG_ID, CONSULTORIO_ID, "ORG_ADMIN", TenantOperationalStatus.ACTIVA)));

		assertThat(guard.consultorioDelContexto()).isEqualTo(CONSULTORIO_ID);
	}

	@Test
	@DisplayName("Sin contexto publicado no hay sede, y eso es un estado legitimo")
	void sin_contexto_no_hay_sede() {
		// Un PLATFORM_ADMIN opera sin contexto de tenant. Con null, el evaluador solo puede
		// conceder permisos de alcance organizacion o global: fail-closed.
		given(tenantContextHolder.current()).willReturn(Optional.empty());

		assertThat(guard.consultorioDelContexto()).isNull();
	}
}
