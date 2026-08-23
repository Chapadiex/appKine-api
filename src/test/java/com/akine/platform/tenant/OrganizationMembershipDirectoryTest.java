package com.akine.platform.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.akine.organization.domain.Consultorio;
import com.akine.organization.domain.Membership;
import com.akine.organization.domain.Organization;
import com.akine.organization.domain.RoleCode;
import com.akine.organization.domain.Subscription;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.infrastructure.ConsultorioRepository;
import com.akine.organization.infrastructure.MembershipRepository;
import com.akine.organization.infrastructure.OrganizationRepository;
import com.akine.organization.infrastructure.SubscriptionRepository;
import com.akine.organization.infrastructure.tenant.OrganizationMembershipDirectory;
import com.akine.platform.spi.tenant.TenantMembership;
import com.akine.platform.spi.tenant.TenantOperationalStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * El adaptador que implementa {@code platform.spi.tenant.MembershipDirectory}.
 *
 * <p>Vive en el paquete de tests del tenancy de {@code platform} porque lo que verifica es el
 * cumplimiento de ESE contrato: que un contexto no accesible devuelva vacio sin distinguir el
 * motivo, y que el estado operativo llegue traducido. Los tests de las entities de
 * {@code organization} son otro asunto y viven en su propio paquete.
 *
 * <p>Unitario con mocks de repositorio: la regla que se prueba es de composicion y de orden de
 * comprobaciones, no de SQL.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationMembershipDirectoryTest {

	private static final Instant AHORA = Instant.parse("2026-08-22T12:00:00Z");
	private static final Instant AYER = AHORA.minusSeconds(86_400);

	private static final long CUENTA = 7L;
	private static final long ORGANIZACION = 100L;
	private static final long CONSULTORIO = 200L;

	@Mock
	private MembershipRepository membershipRepository;
	@Mock
	private ConsultorioRepository consultorioRepository;
	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private SubscriptionRepository subscriptionRepository;

	@InjectMocks
	private OrganizationMembershipDirectory directorio;

	@Test
	@DisplayName("Contexto valido: devuelve el rol de la membership y el estado operativo del tenant")
	void contexto_valido() {
		dadaLaMembership(membershipVigente(null, RoleCode.PROFESIONAL));
		dadoElConsultorioDeLaOrganizacion();
		dadaLaOrganizacionVigente();
		dadaLaSuscripcion(SubscriptionStatus.ACTIVA);

		TenantMembership resuelta = resolver().orElseThrow();

		assertThat(resuelta.membershipId()).isEqualTo(11L);
		assertThat(resuelta.roleCode()).isEqualTo("PROFESIONAL");
		assertThat(resuelta.operationalStatus()).isEqualTo(TenantOperationalStatus.ACTIVA);
	}

	@Test
	@DisplayName("Sin membership en esa organizacion: vacio, y no se consulta nada mas")
	void sin_membership() {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORGANIZACION, CUENTA))
				.willReturn(List.of());

		assertThat(resolver()).isEmpty();
		verifyNoInteractions(consultorioRepository, organizationRepository, subscriptionRepository);
	}

	@Test
	@DisplayName("Membership vencida (valid_until en el pasado): rechazo, aunque la fila siga activa")
	void membership_vencida() {
		Membership vencida = membershipVigente(null, RoleCode.PROFESIONAL);
		vencida.endValidity(AYER);
		dadaLaMembership(vencida);

		// Vigencia y baja logica son cosas distintas: la fila esta activa y aun asi no habilita
		// ningun contexto.
		assertThat(vencida.isActive()).isTrue();
		assertThat(resolver()).isEmpty();
		verifyNoInteractions(consultorioRepository, organizationRepository, subscriptionRepository);
	}

	@Test
	@DisplayName("Membership que todavia no empezo: rechazo")
	void membership_futura() {
		Membership futura = new Membership(
				ORGANIZACION, null, CUENTA, RoleCode.PROFESIONAL, false, AHORA.plusSeconds(3_600));
		ReflectionTestUtils.setField(futura, "id", 11L);
		dadaLaMembership(futura);

		assertThat(resolver()).isEmpty();
	}

	@Test
	@DisplayName("Membership con alcance de OTRO consultorio: rechazo")
	void membership_de_otro_consultorio() {
		dadaLaMembership(membershipVigente(999L, RoleCode.CONSULTORIO_ADMIN));

		assertThat(resolver()).isEmpty();
		verifyNoInteractions(consultorioRepository);
	}

	@Test
	@DisplayName("Membership con consultorio_id NULL alcanza a toda la organizacion")
	void membership_de_alcance_organizacion() {
		dadaLaMembership(membershipVigente(null, RoleCode.ORG_ADMIN));
		dadoElConsultorioDeLaOrganizacion();
		dadaLaOrganizacionVigente();
		dadaLaSuscripcion(SubscriptionStatus.ACTIVA);

		assertThat(resolver()).isPresent();
	}

	@Test
	@DisplayName("Consultorio inexistente, dado de baja o de otro tenant: vacio, sin distinguir cual")
	void consultorio_no_accesible() {
		dadaLaMembership(membershipVigente(null, RoleCode.ORG_ADMIN));
		// La consulta es por (id, organizationId): un id ajeno inyectado en el token no resuelve.
		given(consultorioRepository.findByIdAndOrganizationIdAndActiveTrue(CONSULTORIO, ORGANIZACION))
				.willReturn(Optional.empty());

		assertThat(resolver()).isEmpty();
		verifyNoInteractions(organizationRepository, subscriptionRepository);
	}

	@Test
	@DisplayName("Organizacion dada de baja logica: su contexto no se resuelve")
	void organizacion_dada_de_baja() {
		dadaLaMembership(membershipVigente(null, RoleCode.ORG_ADMIN));
		dadoElConsultorioDeLaOrganizacion();
		// La consulta pide la organizacion VIGENTE: una dada de baja no aparece, y para quien
		// pregunta es indistinguible de una inexistente.
		given(organizationRepository.findByIdAndActiveTrue(ORGANIZACION)).willReturn(Optional.empty());

		assertThat(resolver()).isEmpty();
		verifyNoInteractions(subscriptionRepository);
	}

	@Test
	@DisplayName("Suscripcion SUSPENDIDA y CANCELADA se traducen al enum del spi")
	void traduce_los_estados_de_la_suscripcion() {
		dadaLaMembership(membershipVigente(null, RoleCode.ORG_ADMIN));
		dadoElConsultorioDeLaOrganizacion();
		dadaLaOrganizacionVigente();
		dadaLaSuscripcion(SubscriptionStatus.SUSPENDIDA);

		assertThat(resolver().orElseThrow().operationalStatus())
				.isEqualTo(TenantOperationalStatus.SUSPENDIDA);

		dadaLaSuscripcion(SubscriptionStatus.CANCELADA);
		assertThat(resolver().orElseThrow().operationalStatus())
				.isEqualTo(TenantOperationalStatus.CANCELADA);
	}

	@Test
	@DisplayName("Organizacion sin suscripcion: se niega el acceso en vez de asumir un estado")
	void sin_suscripcion() {
		dadaLaMembership(membershipVigente(null, RoleCode.ORG_ADMIN));
		dadoElConsultorioDeLaOrganizacion();
		dadaLaOrganizacionVigente();
		given(subscriptionRepository.findByOrganizationId(ORGANIZACION)).willReturn(Optional.empty());

		assertThat(resolver()).isEmpty();
	}

	// =================================================================================
	// Ayudas
	// =================================================================================

	private Optional<TenantMembership> resolver() {
		return directorio.resolveMembership(CUENTA, ORGANIZACION, CONSULTORIO, AHORA);
	}

	private Membership membershipVigente(Long consultorioId, RoleCode rol) {
		Membership membership = new Membership(ORGANIZACION, consultorioId, CUENTA, rol, false, AYER);
		// La entity nace sin id porque no paso por JPA; el contrato del spi lo exige.
		ReflectionTestUtils.setField(membership, "id", 11L);
		return membership;
	}

	private void dadaLaMembership(Membership membership) {
		given(membershipRepository.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(ORGANIZACION, CUENTA))
				.willReturn(List.of(membership));
	}

	private void dadoElConsultorioDeLaOrganizacion() {
		given(consultorioRepository.findByIdAndOrganizationIdAndActiveTrue(CONSULTORIO, ORGANIZACION))
				.willReturn(Optional.of(new Consultorio(ORGANIZACION, "Sede Centro")));
	}

	private void dadaLaOrganizacionVigente() {
		Organization organization =
				new Organization("Centro Kine", "centro-kine", "America/Argentina/Cordoba");
		given(organizationRepository.findByIdAndActiveTrue(ORGANIZACION))
				.willReturn(Optional.of(organization));
	}

	private void dadaLaSuscripcion(SubscriptionStatus estado) {
		Subscription subscription = new Subscription(ORGANIZACION, 1L, AYER);
		if (estado != SubscriptionStatus.ACTIVA) {
			subscription.transitionTo(estado);
		}
		given(subscriptionRepository.findByOrganizationId(ORGANIZACION))
				.willReturn(Optional.of(subscription));
	}
}
