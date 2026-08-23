package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el registro de idempotencia del alta compuesta (ADR-0008).
 *
 * <p>{@link OrganizationOnboarding#matchesRequestHash(String)} decide si un reintento devuelve
 * el resultado original o se rechaza por conflicto. Equivocarse hacia un lado crea un segundo
 * tenant duplicado; hacia el otro, rompe el onboarding self-service con un 409 inventado.
 */
class OrganizationOnboardingTest {

	private static final Instant CREADO = Instant.parse("2026-01-02T03:04:05Z");
	private static final String HASH = "a".repeat(64);

	private OrganizationOnboarding conHash(String hash) {
		return new OrganizationOnboarding("clave-idem-1", hash, 1L, 2L, 3L, 4L, CREADO);
	}

	@Test
	@DisplayName("El registro guarda el resultado completo del alta")
	void guarda_el_resultado_completo() {
		OrganizationOnboarding registro = conHash(HASH);

		// El reintento devuelve exactamente lo que se creo la primera vez, asi que los cuatro
		// ids tienen que estar: si faltara uno, el replay respondera un recurso incompleto.
		assertThat(registro.getIdempotencyKey()).isEqualTo("clave-idem-1");
		assertThat(registro.getRequestHash()).isEqualTo(HASH);
		assertThat(registro.getAccountId()).isEqualTo(1L);
		assertThat(registro.getOrganizationId()).isEqualTo(2L);
		assertThat(registro.getConsultorioId()).isEqualTo(3L);
		assertThat(registro.getMembershipId()).isEqualTo(4L);
		assertThat(registro.getCreatedAt()).isEqualTo(CREADO);
		assertThat(registro.getId()).isNull();
	}

	@Test
	@DisplayName("El mismo payload permite el replay")
	void el_mismo_payload_permite_el_replay() {
		assertThat(conHash(HASH).matchesRequestHash(HASH)).isTrue();
	}

	@Test
	@DisplayName("La misma clave con otro payload no coincide")
	void otra_payload_con_la_misma_clave_no_coincide() {
		// Es un error del cliente que no puede resolverse devolviendo el resultado viejo:
		// pidio crear otra cosa con la clave de la anterior.
		assertThat(conHash(HASH).matchesRequestHash("b".repeat(64))).isFalse();
	}

	@Test
	@DisplayName("Un registro sin hash acepta cualquier reintento")
	void sin_hash_acepta_el_reintento() {
		// El alta por spi no tiene payload HTTP que comparar: inventar un conflicto ahi
		// romperia el onboarding self-service en el primer reintento de red.
		OrganizationOnboarding porSpi = conHash(null);

		assertThat(porSpi.getRequestHash()).isNull();
		assertThat(porSpi.matchesRequestHash(HASH)).isTrue();
		assertThat(porSpi.matchesRequestHash(null)).isTrue();
	}

	@Test
	@DisplayName("Un registro con hash y un reintento sin hash no coinciden")
	void con_hash_contra_reintento_sin_hash_no_coincide() {
		// No puede compararse contra nada, y tratarlo como coincidencia dejaria pasar un
		// payload distinto por el simple hecho de no calcular el hash.
		assertThat(conHash(HASH).matchesRequestHash(null)).isFalse();
	}
}
