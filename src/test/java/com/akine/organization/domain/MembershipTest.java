package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el vinculo contextual entre cuenta y organizacion (RF-M01-002, RN-M01-003).
 *
 * <p>El centro del test es {@link Membership#isValidAt(Instant)}: esa pregunta se responde en
 * CADA request para decidir si un contexto sigue habilitado. Vigencia y baja logica son
 * condiciones distintas y las dos tienen que cumplirse; confundirlas deja a una membership
 * revocada operando hasta que venza, o a una vencida operando porque la fila sigue activa.
 */
class MembershipTest {

	private static final Long ORG = 4L;
	private static final Long CUENTA = 88L;
	private static final Instant DESDE = Instant.parse("2026-03-01T00:00:00Z");
	private static final Instant HASTA = Instant.parse("2026-06-01T00:00:00Z");

	private Membership membershipDeOrganizacion() {
		return new Membership(ORG, null, CUENTA, RoleCode.ORG_ADMIN, false, DESDE);
	}

	@Test
	@DisplayName("Una membership nueva nace activa, vigente y sin fin")
	void nace_activa_y_sin_fin() {
		Membership membership = membershipDeOrganizacion();

		assertThat(membership.isActive()).isTrue();
		assertThat(membership.getDeletedAt()).isNull();
		// validUntil null = sin fin. Un default distinto de null caducaria memberships que
		// nadie quiso limitar.
		assertThat(membership.getValidUntil()).isNull();
		assertThat(membership.getValidFrom()).isEqualTo(DESDE);
		assertThat(membership.getOrganizationId()).isEqualTo(ORG);
		assertThat(membership.getAccountId()).isEqualTo(CUENTA);
		assertThat(membership.getRoleCode()).isEqualTo(RoleCode.ORG_ADMIN);
		assertThat(membership.getId()).isNull();
		assertThat(membership.getVersion()).isZero();
	}

	@Test
	@DisplayName("consultorioId nulo significa alcance ORGANIZACION")
	void consultorio_nulo_es_alcance_organizacion() {
		// 01.01 solo escribe null aca. Si alguien lo interpretara como "sede desconocida" y
		// lo rellenara con un id por defecto, la membership dejaria de valer para el resto de
		// las sedes de la organizacion.
		assertThat(membershipDeOrganizacion().getConsultorioId()).isNull();

		Membership deSede = new Membership(ORG, 12L, CUENTA, RoleCode.PROFESIONAL, false, DESDE);
		assertThat(deSede.getConsultorioId()).isEqualTo(12L);
	}

	@Test
	@DisplayName("Ser fundador es un atributo y arranca en false salvo que se declare")
	void el_fundador_es_un_atributo() {
		// La condicion de fundador NO es un rol (RN-M05-005/006): el propietario se crea con
		// ORG_ADMIN + is_founder = true. Si el default fuera true, cualquier miembro invitado
		// quedaria protegido por el invariante "el fundador no puede ser desvinculado".
		assertThat(membershipDeOrganizacion().isFounder()).isFalse();

		Membership fundador = new Membership(ORG, null, CUENTA, RoleCode.ORG_ADMIN, true, DESDE);
		assertThat(fundador.isFounder()).isTrue();
		assertThat(fundador.getRoleCode()).isEqualTo(RoleCode.ORG_ADMIN);
	}

	@Test
	@DisplayName("La vigencia incluye el instante de inicio y excluye el de fin")
	void la_vigencia_incluye_el_inicio_y_excluye_el_fin() {
		Membership membership = membershipDeOrganizacion();
		membership.endValidity(HASTA);

		// Los bordes importan: si validFrom fuera exclusivo, la membership creada en el
		// onboarding no serviria en el mismo request que la creo. Si validUntil fuera
		// inclusivo, un vinculo cerrado seguiria habilitando el contexto un instante mas.
		assertThat(membership.isValidAt(DESDE)).isTrue();
		assertThat(membership.isValidAt(DESDE.minusNanos(1))).isFalse();
		assertThat(membership.isValidAt(HASTA.minusNanos(1))).isTrue();
		assertThat(membership.isValidAt(HASTA)).isFalse();
		assertThat(membership.isValidAt(HASTA.plusSeconds(1))).isFalse();
	}

	@Test
	@DisplayName("Sin fin declarado, la membership vale indefinidamente hacia adelante")
	void sin_fin_vale_indefinidamente() {
		Membership membership = membershipDeOrganizacion();

		assertThat(membership.isValidAt(DESDE.plusSeconds(3600))).isTrue();
		assertThat(membership.isValidAt(Instant.parse("2099-01-01T00:00:00Z"))).isTrue();
	}

	@Test
	@DisplayName("Una membership dada de baja deja de valer aunque su vigencia siga abierta")
	void la_baja_logica_gana_sobre_la_vigencia() {
		Membership membership = membershipDeOrganizacion();
		Instant baja = Instant.parse("2026-04-01T00:00:00Z");

		membership.deactivate(baja);

		// Este es el caso de la revocacion: el admin desvincula a alguien y la vigencia
		// todavia no vencio. Si la baja logica no ganara, el usuario revocado seguiria
		// entrando hasta la fecha de fin. Sin ventana de gracia: el request siguiente ya no
		// pasa.
		assertThat(membership.isValidAt(baja.plusSeconds(1))).isFalse();
		assertThat(membership.isValidAt(DESDE)).isFalse();
		assertThat(membership.isActive()).isFalse();
		assertThat(membership.getDeletedAt()).isEqualTo(baja);
	}

	@Test
	@DisplayName("Cerrar la vigencia conserva la fila")
	void cerrar_la_vigencia_conserva_la_fila() {
		Membership membership = membershipDeOrganizacion();

		membership.endValidity(HASTA);

		// El vinculo historico se conserva: nunca hay DELETE fisico, porque es el que explica
		// quien atendio que en el pasado.
		assertThat(membership.getValidUntil()).isEqualTo(HASTA);
		assertThat(membership.isActive()).isTrue();
		assertThat(membership.getDeletedAt()).isNull();
	}

	@Test
	@DisplayName("Cambiar el rol no toca el resto del vinculo")
	void cambiar_el_rol_no_toca_el_resto() {
		Membership membership = membershipDeOrganizacion();

		membership.changeRole(RoleCode.ADMINISTRATIVO);

		assertThat(membership.getRoleCode()).isEqualTo(RoleCode.ADMINISTRATIVO);
		// Cambiar de rol no puede reabrir ni cerrar la vigencia ni alterar la cuenta: son
		// dimensiones independientes.
		assertThat(membership.getAccountId()).isEqualTo(CUENTA);
		assertThat(membership.getValidFrom()).isEqualTo(DESDE);
		assertThat(membership.getValidUntil()).isNull();
		assertThat(membership.isActive()).isTrue();
	}

	@Test
	@DisplayName("La membership hidratada por JPA arranca activa")
	void la_hidratada_por_jpa_arranca_activa() {
		// El constructor de JPA no pasa por el de negocio; el default del campo es lo unico
		// que evita que una fila nueva nazca inactiva por omision.
		assertThat(new Membership().isActive()).isTrue();
	}
}
