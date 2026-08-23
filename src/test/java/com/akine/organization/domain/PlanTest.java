package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica los invariantes del plan comercial.
 *
 * <p>El plan es catalogo de la plataforma, no dato de un tenant. Lo que se fija aca es que
 * retirarlo de la oferta sea una baja logica: hay suscripciones historicas que lo referencian
 * y RN-M01-002 prohibe perder esa historia.
 */
class PlanTest {

	@Test
	@DisplayName("Un plan nuevo nace activo con su codigo estable")
	void nace_activo() {
		Plan plan = new Plan("BASICO", "Plan Basico");

		assertThat(plan.isActive()).isTrue();
		assertThat(plan.getDeletedAt()).isNull();
		// El codigo lo consumen el seed y el spi de onboarding: cambiarlo rompe los dos, por
		// eso la columna es updatable = false y no hay setter.
		assertThat(plan.getCode()).isEqualTo("BASICO");
		assertThat(plan.getName()).isEqualTo("Plan Basico");
		assertThat(plan.getId()).isNull();
		assertThat(plan.getVersion()).isZero();
	}

	@Test
	@DisplayName("Renombrar el plan no toca su codigo")
	void renombrar_no_toca_el_codigo() {
		Plan plan = new Plan("BASICO", "Plan Basico");

		plan.rename("Plan Inicial");

		assertThat(plan.getName()).isEqualTo("Plan Inicial");
		// El nombre es comercial y cambia; el codigo es la clave que usan el seed y el
		// onboarding para encontrar el plan.
		assertThat(plan.getCode()).isEqualTo("BASICO");
	}

	@Test
	@DisplayName("Retirar el plan es baja logica: las suscripciones vigentes lo siguen usando")
	void retirar_es_baja_logica() {
		Plan plan = new Plan("BASICO", "Plan Basico");
		Instant momento = Instant.parse("2026-02-10T00:00:00Z");

		plan.deactivate(momento);

		// Borrarlo dejaria huerfanas las suscripciones que lo referencian y el historico no
		// podria responder que incluia el plan cuando alguien se suscribio.
		assertThat(plan.isActive()).isFalse();
		assertThat(plan.getDeletedAt()).isEqualTo(momento);
		assertThat(plan.getCode()).isEqualTo("BASICO");
	}

	@Test
	@DisplayName("El plan hidratado por JPA arranca activo")
	void el_hidratado_por_jpa_arranca_activo() {
		assertThat(new Plan().isActive()).isTrue();
	}
}
