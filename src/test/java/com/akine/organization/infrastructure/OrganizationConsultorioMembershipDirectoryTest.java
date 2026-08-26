package com.akine.organization.infrastructure;

import com.akine.organization.domain.Membership;
import com.akine.organization.domain.RoleCode;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Tests de {@link OrganizationConsultorioMembershipDirectory}, adaptador de
 * {@code com.akine.organization.spi.ConsultorioMembershipDirectory}.
 *
 * <p>Igual que el repositorio que envuelve, el filtro de tenant es la garantia central: se
 * verifica que la consulta se delega tal cual en {@code findByIdAndOrganizationId} y que el
 * adaptador nunca "arregla" un resultado vacio inventando datos.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationConsultorioMembershipDirectoryTest {

	private static final long ORG_ID = 10L;
	private static final long OTRA_ORG_ID = 11L;
	private static final long MEMBERSHIP_ID = 60L;
	private static final long ACCOUNT_ID = 30L;
	private static final long CONSULTORIO_ID = 20L;
	private static final long OTRO_CONSULTORIO_ID = 21L;

	@Mock
	private MembershipRepository membershipRepository;

	@InjectMocks
	private OrganizationConsultorioMembershipDirectory directory;

	@Test
	@DisplayName("no resuelve una membership de otro tenant")
	void no_resuelve_una_membership_de_otro_tenant() {
		// El repositorio ya filtra por (id, organizationId): pedir la membership con el
		// organizationId equivocado es, para la consulta real, lo mismo que no existir.
		given(membershipRepository.findByIdAndOrganizationId(MEMBERSHIP_ID, OTRA_ORG_ID))
				.willReturn(Optional.empty());

		assertThat(directory.find(OTRA_ORG_ID, MEMBERSHIP_ID)).isEmpty();
	}

	@Test
	@DisplayName("alcance organizacion cubre cualquier sede del tenant")
	void alcance_organizacion_cubre_cualquier_sede_del_tenant() {
		Membership membership = membershipDeAlcanceOrganizacion();
		given(membershipRepository.findByIdAndOrganizationId(MEMBERSHIP_ID, ORG_ID))
				.willReturn(Optional.of(membership));

		ConsultorioMembershipSnapshot foto = directory.find(ORG_ID, MEMBERSHIP_ID).orElseThrow();

		assertThat(foto.consultorioId()).isNull();
		assertThat(foto.cubreConsultorio(CONSULTORIO_ID)).isTrue();
		assertThat(foto.cubreConsultorio(OTRO_CONSULTORIO_ID)).isTrue();
	}

	@Test
	@DisplayName("alcance consultorio no cubre otra sede")
	void alcance_consultorio_no_cubre_otra_sede() {
		Membership membership = membershipDeSede(CONSULTORIO_ID);
		given(membershipRepository.findByIdAndOrganizationId(MEMBERSHIP_ID, ORG_ID))
				.willReturn(Optional.of(membership));

		ConsultorioMembershipSnapshot foto = directory.find(ORG_ID, MEMBERSHIP_ID).orElseThrow();

		assertThat(foto.cubreConsultorio(CONSULTORIO_ID)).isTrue();
		assertThat(foto.cubreConsultorio(OTRO_CONSULTORIO_ID)).isFalse();
	}

	@Test
	@DisplayName("validAt es false despues de validUntil")
	void valid_at_es_false_despues_de_valid_until() {
		Membership membership = membershipDeAlcanceOrganizacion();
		membership.endValidity(Instant.now().minus(1, ChronoUnit.DAYS));
		given(membershipRepository.findByIdAndOrganizationId(MEMBERSHIP_ID, ORG_ID))
				.willReturn(Optional.of(membership));

		ConsultorioMembershipSnapshot foto = directory.find(ORG_ID, MEMBERSHIP_ID).orElseThrow();

		assertThat(foto.validAt(Instant.now())).isFalse();
	}

	@Test
	@DisplayName("validAt es false si la membership esta dada de baja")
	void valid_at_es_false_si_la_membership_esta_dada_de_baja() {
		Membership membership = membershipDeAlcanceOrganizacion();
		membership.deactivate(Instant.now());
		given(membershipRepository.findByIdAndOrganizationId(MEMBERSHIP_ID, ORG_ID))
				.willReturn(Optional.of(membership));

		ConsultorioMembershipSnapshot foto = directory.find(ORG_ID, MEMBERSHIP_ID).orElseThrow();

		assertThat(foto.active()).isFalse();
		assertThat(foto.validAt(Instant.now())).isFalse();
	}

	@Test
	@DisplayName("validAt es false si la membership esta suspendida")
	void valid_at_es_false_si_la_membership_esta_suspendida() {
		// Suspendida no toca `active` (baja logica) ni la ventana de vigencia: es la TERCERA
		// condicion. Si el snapshot solo mirara active + vigencia, esta membership leeria como
		// valida, y un profesional suspendido seguiria ofreciendo disponibilidad.
		Membership membership = membershipDeAlcanceOrganizacion();
		membership.suspender();
		given(membershipRepository.findByIdAndOrganizationId(MEMBERSHIP_ID, ORG_ID))
				.willReturn(Optional.of(membership));

		ConsultorioMembershipSnapshot foto = directory.find(ORG_ID, MEMBERSHIP_ID).orElseThrow();

		assertThat(foto.active()).isTrue();
		assertThat(foto.estado()).isEqualTo("SUSPENDIDA");
		assertThat(foto.habilitada()).isFalse();
		assertThat(foto.validAt(Instant.now())).isFalse();
	}

	private static Membership membershipDeAlcanceOrganizacion() {
		return conId(new Membership(ORG_ID, null, ACCOUNT_ID, RoleCode.ORG_ADMIN, true,
				Instant.now().minus(30, ChronoUnit.DAYS)), MEMBERSHIP_ID);
	}

	private static Membership membershipDeSede(long consultorioId) {
		return conId(new Membership(ORG_ID, consultorioId, ACCOUNT_ID, RoleCode.PROFESIONAL, false,
				Instant.now().minus(30, ChronoUnit.DAYS)), MEMBERSHIP_ID);
	}

	/** Las entities no exponen setter de {@code id} —lo asigna la base— asi que se pone por reflexion. */
	private static Membership conId(Membership membership, long id) {
		ReflectionTestUtils.setField(membership, "id", id);
		return membership;
	}
}
