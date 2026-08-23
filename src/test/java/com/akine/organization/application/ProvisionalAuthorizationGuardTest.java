package com.akine.organization.application;

import com.akine.organization.domain.RoleCode;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static com.akine.organization.application.Fixtures.ACCOUNT_ID;
import static com.akine.organization.application.Fixtures.ORG_ID;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Autorizacion gruesa y provisional del modulo (deuda declarada, se reemplaza en 01.03).
 *
 * <p>El test que sostiene el aislamiento cross-tenant es
 * {@link #ser_org_admin_en_otro_tenant_no_alcanza()}: sin la comparacion contra el contexto
 * validado, un {@code ORG_ADMIN} de la organizacion A obtendria acceso administrativo poniendo
 * el id de A en la URL mientras opera con un token acotado a B (RN-M01-003).
 */
@ExtendWith(MockitoExtension.class)
class ProvisionalAuthorizationGuardTest {

	private static final long OTRA_ORG_ID = 11L;

	@Mock
	private MembershipRepositoryPort membershipRepository;

	@Mock
	private AccountContextService accountContextService;

	@InjectMocks
	private ProvisionalAuthorizationGuard guard;

	// ------------------------------------------------------------------ PLATFORM_ADMIN

	@Test
	@DisplayName("PLATFORM_ADMIN se resuelve por el flag del principal, sin consultar tablas")
	void platform_admin_es_un_flag() {
		assertThatCode(() -> guard.requirePlatformAdmin(true)).doesNotThrowAnyException();
		verifyNoInteractions(membershipRepository, accountContextService);
	}

	@Test
	@DisplayName("Sin el flag de plataforma la operacion reservada se rechaza")
	void sin_flag_de_plataforma_se_rechaza() {
		assertThatThrownBy(() -> guard.requirePlatformAdmin(false))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("PLATFORM_ADMIN administra cualquier organizacion sin membership")
	void platform_admin_administra_cualquier_organizacion() {
		assertThatCode(() -> guard.requireOrgAdmin(ACCOUNT_ID, ORG_ID, null, true))
				.doesNotThrowAnyException();
		assertThatCode(() -> guard.requireMember(ACCOUNT_ID, ORG_ID, null, true))
				.doesNotThrowAnyException();
		verifyNoInteractions(membershipRepository, accountContextService);
	}

	// ------------------------------------------------------------------------ ORG_ADMIN

	@Test
	@DisplayName("Una membership ORG_ADMIN vigente en la organizacion del contexto habilita")
	void org_admin_vigente_en_el_contexto_habilita() {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigente()));

		assertThatCode(() -> guard.requireOrgAdmin(ACCOUNT_ID, ORG_ID, ORG_ID, false))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("Administrar desde el contexto de otra organizacion responde 404, no 403")
	void ser_org_admin_en_otro_tenant_no_alcanza() {
		// 403 confirmaria que ORG_ID existe: bastarian ids consecutivos para enumerar clientes.
		assertThatThrownBy(() -> guard.requireOrgAdmin(ACCOUNT_ID, ORG_ID, OTRA_ORG_ID, false))
				.isInstanceOf(OrganizationNotFoundException.class);

		// Ni siquiera se pregunta por la membership: el contexto ya decidio.
		verifyNoInteractions(membershipRepository);
	}

	@Test
	@DisplayName("Un request sin contexto validado no puede administrar ninguna organizacion")
	void sin_contexto_no_administra() {
		// Aca si es 403 y no 404: no eligio donde trabaja todavia, y el frontend necesita
		// distinguir "elegi un consultorio" de "eso no existe".
		assertThatThrownBy(() -> guard.requireOrgAdmin(ACCOUNT_ID, ORG_ID, null, false))
				.isInstanceOf(AccessDeniedException.class)
				.hasMessageContaining("contexto de trabajo activo");
	}

	@Test
	@DisplayName("Una membership vencida no administra, aunque el rol sea el correcto")
	void una_membership_vencida_no_administra() {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVencida()));

		assertThatThrownBy(() -> guard.requireOrgAdmin(ACCOUNT_ID, ORG_ID, ORG_ID, false))
				.isInstanceOf(AccessDeniedException.class)
				.hasMessageContaining("administrar la organizacion");
	}

	@Test
	@DisplayName("Sin membership en el tenant no se administra")
	void sin_membership_no_administra() {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of());

		assertThatThrownBy(() -> guard.requireOrgAdmin(ACCOUNT_ID, ORG_ID, ORG_ID, false))
				.isInstanceOf(AccessDeniedException.class);
	}

	@ParameterizedTest
	@EnumSource(value = RoleCode.class, mode = EnumSource.Mode.EXCLUDE, names = "ORG_ADMIN")
	@DisplayName("Solo ORG_ADMIN administra la organizacion: cualquier otro rol se rechaza")
	void ningun_otro_rol_administra(RoleCode rol) {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORG_ID, ACCOUNT_ID))
				.willReturn(List.of(Fixtures.membershipVigenteCon(rol)));

		assertThatThrownBy(() -> guard.requireOrgAdmin(ACCOUNT_ID, ORG_ID, ORG_ID, false))
				.isInstanceOf(AccessDeniedException.class);
	}

	// --------------------------------------------------------------------- requireMember

	@Test
	@DisplayName("Un miembro vigente del contexto accede a su organizacion")
	void un_miembro_vigente_accede() {
		given(accountContextService.hasActiveMembership(ACCOUNT_ID, ORG_ID)).willReturn(true);

		assertThatCode(() -> guard.requireMember(ACCOUNT_ID, ORG_ID, ORG_ID, false))
				.doesNotThrowAnyException();
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
}
