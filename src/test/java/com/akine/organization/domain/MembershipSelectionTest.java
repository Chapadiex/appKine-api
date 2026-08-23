package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Criterios de eleccion de membership cuando hay varias en la misma organizacion.
 *
 * <p>Esto no es una utilidad cualquiera: dos de sus tres llamadores son decisiones de
 * autorizacion —la resolucion de contexto de cada request y el guard de administracion—, asi
 * que elegir mal abre o cierra accesos. Cada test de aca fija uno de esos criterios.
 */
@DisplayName("Eleccion de membership con varias en la misma organizacion")
class MembershipSelectionTest {

	private static final long ORG = 7L;
	private static final long CUENTA = 42L;
	private static final long SEDE = 100L;
	private static final long OTRA_SEDE = 200L;

	private static final Instant AHORA = Instant.parse("2026-08-23T12:00:00Z");

	@Nested
	@DisplayName("Decidir sobre UNA sede: gana la mas especifica")
	class Applicable {

		@Test
		@DisplayName("La membership de la sede gana sobre la de alcance organizacion")
		void la_de_la_sede_gana() {
			Membership organizacion = membership(1L, null, RoleCode.ORG_ADMIN);
			Membership deSede = membership(2L, SEDE, RoleCode.PROFESIONAL);

			Optional<Membership> elegida = MembershipSelection.applicableAt(
					List.of(organizacion, deSede), SEDE, AHORA);

			assertThat(elegida).contains(deSede);
		}

		@Test
		@DisplayName("El orden en que vengan de la base no cambia el resultado")
		void el_orden_no_importa() {
			Membership organizacion = membership(1L, null, RoleCode.ORG_ADMIN);
			Membership deSede = membership(2L, SEDE, RoleCode.PROFESIONAL);

			assertThat(MembershipSelection.applicableAt(
					List.of(deSede, organizacion), SEDE, AHORA)).contains(deSede);
			assertThat(MembershipSelection.applicableAt(
					List.of(organizacion, deSede), SEDE, AHORA)).contains(deSede);
		}

		@Test
		@DisplayName("En otra sede, la de alcance organizacion sigue habilitando")
		void en_otra_sede_gana_la_de_organizacion() {
			Membership organizacion = membership(1L, null, RoleCode.ORG_ADMIN);
			Membership deSede = membership(2L, SEDE, RoleCode.PROFESIONAL);

			Optional<Membership> elegida = MembershipSelection.applicableAt(
					List.of(organizacion, deSede), OTRA_SEDE, AHORA);

			assertThat(elegida).contains(organizacion);
		}

		@Test
		@DisplayName("Si la de la sede vencio, la de organizacion vigente conserva el acceso")
		void la_vencida_no_tapa_a_la_vigente() {
			Membership organizacion = membership(1L, null, RoleCode.ORG_ADMIN);
			Membership deSedeVencida = vencida(membership(2L, SEDE, RoleCode.PROFESIONAL));

			Optional<Membership> elegida = MembershipSelection.applicableAt(
					List.of(organizacion, deSedeVencida), SEDE, AHORA);

			// Si se eligiera primero por especificidad y despues se mirara la vigencia, esta
			// persona perderia un acceso que sigue teniendo.
			assertThat(elegida).contains(organizacion);
		}

		@Test
		@DisplayName("Memberships de otras sedes no habilitan la sede pedida")
		void otras_sedes_no_habilitan() {
			Membership otraSede = membership(1L, OTRA_SEDE, RoleCode.PROFESIONAL);

			assertThat(MembershipSelection.applicableAt(List.of(otraSede), SEDE, AHORA)).isEmpty();
		}

		@Test
		@DisplayName("Sin memberships no hay nada que elegir")
		void sin_memberships() {
			assertThat(MembershipSelection.applicableAt(List.of(), SEDE, AHORA)).isEmpty();
		}
	}

	@Nested
	@DisplayName("Decidir sobre la organizacion entera: solo la de alcance organizacion")
	class OrganizationScoped {

		@Test
		@DisplayName("Se devuelve la de alcance organizacion y nunca una acotada a una sede")
		void solo_la_de_alcance_organizacion() {
			Membership organizacion = membership(1L, null, RoleCode.ADMINISTRATIVO);
			Membership deSede = membership(2L, SEDE, RoleCode.ORG_ADMIN);

			assertThat(MembershipSelection.organizationScoped(List.of(deSede, organizacion)))
					.contains(organizacion);
		}

		@Test
		@DisplayName("Un ORG_ADMIN acotado a una sede NO habla por la organizacion")
		void un_org_admin_de_sede_no_administra_el_tenant() {
			Membership deSede = membership(2L, SEDE, RoleCode.ORG_ADMIN);

			// Es la escalada de privilegio que aparece sola cuando 01.03 empiece a escribir
			// memberships por sede: fail-closed a proposito.
			assertThat(MembershipSelection.organizationScoped(List.of(deSede))).isEmpty();
		}

		@Test
		@DisplayName("Devuelve tambien la vencida: quien decide encadena isValidAt")
		void devuelve_la_vencida_para_poder_explicarla() {
			Membership vencida = vencida(membership(1L, null, RoleCode.ORG_ADMIN));

			Optional<Membership> elegida = MembershipSelection.organizationScoped(List.of(vencida));

			assertThat(elegida).isPresent();
			assertThat(elegida.get().isValidAt(AHORA)).isFalse();
		}
	}

	@Test
	@DisplayName("El alcance de la membership se lee del propio dominio")
	void el_alcance_lo_responde_la_entity() {
		Membership organizacion = membership(1L, null, RoleCode.ORG_ADMIN);
		Membership deSede = membership(2L, SEDE, RoleCode.PROFESIONAL);

		assertThat(organizacion.isOrganizationScoped()).isTrue();
		assertThat(organizacion.covers(SEDE)).isTrue();
		assertThat(organizacion.covers(OTRA_SEDE)).isTrue();

		assertThat(deSede.isOrganizationScoped()).isFalse();
		assertThat(deSede.covers(SEDE)).isTrue();
		assertThat(deSede.covers(OTRA_SEDE)).isFalse();
	}

	private static Membership membership(long id, Long consultorioId, RoleCode rol) {
		Membership membership = new Membership(
				ORG, consultorioId, CUENTA, rol, false, AHORA.minus(30, ChronoUnit.DAYS));
		ReflectionTestUtils.setField(membership, "id", id);
		return membership;
	}

	private static Membership vencida(Membership membership) {
		membership.endValidity(AHORA.minus(1, ChronoUnit.DAYS));
		return membership;
	}
}
