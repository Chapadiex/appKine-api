package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica los invariantes del tenant.
 *
 * <p>La organizacion no tiene maquina de estados propia a proposito: solo baja logica. Lo que
 * se fija aca es eso —que el ciclo de vida sea unicamente {@code active}/{@code deleted_at}—
 * y que el slug sea inmutable desde el dominio.
 */
class OrganizationTest {

	private static final String NOMBRE = "Centro Kine Norte";
	private static final String SLUG = "centro-kine-norte";
	private static final String ZONA = "America/Argentina/Buenos_Aires";

	private Organization nuevaOrganizacion() {
		return new Organization(NOMBRE, SLUG, ZONA);
	}

	@Test
	@DisplayName("Una organizacion nueva nace activa y con su zona horaria explicita")
	void nace_activa() {
		Organization organizacion = nuevaOrganizacion();

		assertThat(organizacion.isActive()).isTrue();
		assertThat(organizacion.getDeletedAt()).isNull();
		assertThat(organizacion.getName()).isEqualTo(NOMBRE);
		assertThat(organizacion.getSlug()).isEqualTo(SLUG);
		// La zona IANA es explicita y obligatoria: calcular cierres de dia y vencimientos
		// sobre UTC o sobre la zona del servidor da resultados distintos segun donde corra.
		assertThat(organizacion.getTimezone()).isEqualTo(ZONA);
		assertThat(organizacion.getId()).isNull();
		assertThat(organizacion.getVersion()).isZero();
	}

	@Test
	@DisplayName("Editar la organizacion nunca cambia el slug")
	void editar_no_cambia_el_slug() {
		Organization organizacion = nuevaOrganizacion();

		organizacion.update("Centro Kine Sur", "America/Santiago");

		assertThat(organizacion.getName()).isEqualTo("Centro Kine Sur");
		assertThat(organizacion.getTimezone()).isEqualTo("America/Santiago");
		// El slug se usa en URLs y en soporte: si update lo tocara, renombrar la
		// organizacion romperia todos los enlaces guardados por los usuarios.
		assertThat(organizacion.getSlug()).isEqualTo(SLUG);
	}

	@Test
	@DisplayName("La baja es logica y deja rastro del momento")
	void la_baja_es_logica() {
		Organization organizacion = nuevaOrganizacion();
		Instant momento = Instant.parse("2026-05-20T12:00:00Z");

		organizacion.deactivate(momento);

		// Nunca hay DELETE fisico: la organizacion es el ancla de historia clinica,
		// obligaciones y auditoria. Sin deleted_at no se puede reconstruir desde cuando el
		// tenant dejo de operar.
		assertThat(organizacion.isActive()).isFalse();
		assertThat(organizacion.getDeletedAt()).isEqualTo(momento);
		assertThat(organizacion.getName()).isEqualTo(NOMBRE);
		assertThat(organizacion.getSlug()).isEqualTo(SLUG);
	}

	@Test
	@DisplayName("La organizacion hidratada por JPA arranca activa")
	void la_hidratada_por_jpa_arranca_activa() {
		assertThat(new Organization().isActive()).isTrue();
	}
}
