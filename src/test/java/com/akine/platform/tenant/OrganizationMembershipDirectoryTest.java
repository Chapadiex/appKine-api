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

import com.akine.organization.domain.LecturaDeContexto;
import com.akine.organization.domain.Membership;
import com.akine.organization.domain.RoleCode;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.infrastructure.MembershipRepository;
import com.akine.organization.infrastructure.tenant.OrganizationMembershipDirectory;
import com.akine.platform.spi.tenant.TenantMembership;
import com.akine.platform.spi.tenant.TenantOperationalStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * El adaptador que implementa {@code platform.spi.tenant.MembershipDirectory}.
 *
 * <p>Vive en el paquete de tests del tenancy de {@code platform} porque lo que verifica es el
 * cumplimiento de ESE contrato: que un contexto no accesible devuelva vacio sin distinguir el
 * motivo, y que el estado operativo llegue traducido. Los tests de las entities de
 * {@code organization} son otro asunto y viven en su propio paquete.
 *
 * <p>Unitario con el repositorio simulado: la regla que se prueba es de composicion y de orden
 * de comprobaciones. Que la sentencia unica traiga sede, organizacion y suscripcion con las
 * mismas condiciones que las cuatro lecturas de antes lo prueba {@code ContextoDeAccesoIT} contra
 * MySQL.
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

	@InjectMocks
	private OrganizationMembershipDirectory directorio;

	@Test
	@DisplayName("Contexto valido: devuelve el rol de la membership y el estado operativo del tenant")
	void contexto_valido() {
		dadas(fila(membershipVigente(11L, null, RoleCode.PROFESIONAL), SubscriptionStatus.ACTIVA));

		TenantMembership resuelta = resolver().orElseThrow();

		assertThat(resuelta.membershipId()).isEqualTo(11L);
		assertThat(resuelta.roleCode()).isEqualTo("PROFESIONAL");
		assertThat(resuelta.operationalStatus()).isEqualTo(TenantOperationalStatus.ACTIVA);
	}

	@Test
	@DisplayName("Una sola lectura por resolucion: membership, sede, organizacion y suscripcion juntas")
	void una_sola_lectura() {
		dadas(fila(membershipVigente(11L, null, RoleCode.PROFESIONAL), SubscriptionStatus.ACTIVA));

		resolver();

		verify(membershipRepository).leerContextoDeAcceso(ORGANIZACION, CUENTA, CONSULTORIO);
		verifyNoMoreInteractions(membershipRepository);
	}

	@Test
	@DisplayName("Sin membership en esa organizacion: vacio")
	void sin_membership() {
		dadas();

		assertThat(resolver()).isEmpty();
	}

	@Test
	@DisplayName("Membership vencida (valid_until en el pasado): rechazo, aunque la fila siga activa")
	void membership_vencida() {
		Membership vencida = membershipVigente(11L, null, RoleCode.PROFESIONAL);
		vencida.endValidity(AYER);
		dadas(fila(vencida, SubscriptionStatus.ACTIVA));

		// Vigencia y baja logica son cosas distintas: la fila esta activa y aun asi no habilita
		// ningun contexto.
		assertThat(vencida.isActive()).isTrue();
		assertThat(resolver()).isEmpty();
	}

	@Test
	@DisplayName("Membership que todavia no empezo: rechazo")
	void membership_futura() {
		Membership futura = new Membership(
				ORGANIZACION, null, CUENTA, RoleCode.PROFESIONAL, false, AHORA.plusSeconds(3_600));
		ReflectionTestUtils.setField(futura, "id", 11L);
		dadas(fila(futura, SubscriptionStatus.ACTIVA));

		assertThat(resolver()).isEmpty();
	}

	@Test
	@DisplayName("Membership con alcance de OTRO consultorio: rechazo")
	void membership_de_otro_consultorio() {
		dadas(fila(membershipVigente(11L, 999L, RoleCode.CONSULTORIO_ADMIN), SubscriptionStatus.ACTIVA));

		assertThat(resolver()).isEmpty();
	}

	@Test
	@DisplayName("Membership con consultorio_id NULL alcanza a toda la organizacion")
	void membership_de_alcance_organizacion() {
		dadas(fila(membershipVigente(11L, null, RoleCode.ORG_ADMIN), SubscriptionStatus.ACTIVA));

		assertThat(resolver()).isPresent();
	}

	@Test
	@DisplayName("Con dos memberships gana la de la sede sobre la de alcance organizacion")
	void gana_la_de_la_sede() {
		dadas(fila(membershipVigente(11L, null, RoleCode.ORG_ADMIN), SubscriptionStatus.ACTIVA),
				fila(membershipVigente(12L, CONSULTORIO, RoleCode.PROFESIONAL), SubscriptionStatus.ACTIVA));

		TenantMembership resuelta = resolver().orElseThrow();

		assertThat(resuelta.membershipId()).isEqualTo(12L);
		assertThat(resuelta.roleCode()).isEqualTo("PROFESIONAL");
	}

	@Test
	@DisplayName("Consultorio inexistente, dado de baja o de otro tenant: vacio, sin distinguir cual")
	void consultorio_no_accesible() {
		// La consulta une la sede por (id, organizationId): un id ajeno inyectado en el token no
		// resuelve y la columna llega vacia.
		dadas(new LecturaDeContexto(membershipVigente(11L, null, RoleCode.ORG_ADMIN),
				null, ORGANIZACION, SubscriptionStatus.ACTIVA));

		assertThat(resolver()).isEmpty();
	}

	@Test
	@DisplayName("Organizacion dada de baja logica: su contexto no se resuelve")
	void organizacion_dada_de_baja() {
		// La consulta une la organizacion VIGENTE: una dada de baja no aparece, y para quien
		// pregunta es indistinguible de una inexistente.
		dadas(new LecturaDeContexto(membershipVigente(11L, null, RoleCode.ORG_ADMIN),
				CONSULTORIO, null, SubscriptionStatus.ACTIVA));

		assertThat(resolver()).isEmpty();
	}

	@Test
	@DisplayName("Suscripcion SUSPENDIDA y CANCELADA se traducen al enum del spi")
	void traduce_los_estados_de_la_suscripcion() {
		dadas(fila(membershipVigente(11L, null, RoleCode.ORG_ADMIN), SubscriptionStatus.SUSPENDIDA));
		assertThat(resolver().orElseThrow().operationalStatus())
				.isEqualTo(TenantOperationalStatus.SUSPENDIDA);

		dadas(fila(membershipVigente(11L, null, RoleCode.ORG_ADMIN), SubscriptionStatus.CANCELADA));
		assertThat(resolver().orElseThrow().operationalStatus())
				.isEqualTo(TenantOperationalStatus.CANCELADA);
	}

	@Test
	@DisplayName("Organizacion sin suscripcion: se niega el acceso en vez de asumir un estado")
	void sin_suscripcion() {
		dadas(new LecturaDeContexto(membershipVigente(11L, null, RoleCode.ORG_ADMIN),
				CONSULTORIO, ORGANIZACION, null));

		assertThat(resolver()).isEmpty();
	}

	// =================================================================================
	// Ayudas
	// =================================================================================

	private Optional<TenantMembership> resolver() {
		return directorio.resolveMembership(CUENTA, ORGANIZACION, CONSULTORIO, AHORA);
	}

	private Membership membershipVigente(long id, Long consultorioId, RoleCode rol) {
		Membership membership = new Membership(ORGANIZACION, consultorioId, CUENTA, rol, false, AYER);
		// La entity nace sin id porque no paso por JPA; el contrato del spi lo exige.
		ReflectionTestUtils.setField(membership, "id", id);
		return membership;
	}

	private static LecturaDeContexto fila(Membership membership, SubscriptionStatus estado) {
		return new LecturaDeContexto(membership, CONSULTORIO, ORGANIZACION, estado);
	}

	private void dadas(LecturaDeContexto... filas) {
		given(membershipRepository.leerContextoDeAcceso(ORGANIZACION, CUENTA, CONSULTORIO))
				.willReturn(List.of(filas));
	}
}
