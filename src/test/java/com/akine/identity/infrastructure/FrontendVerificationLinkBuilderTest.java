package com.akine.identity.infrastructure;

import com.akine.identity.domain.TipoTokenVerificacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Armado del enlace que recibe la persona por correo. */
class FrontendVerificationLinkBuilderTest {

	private static FrontendVerificationLinkBuilder builder(String baseUrl) {
		IdentityProperties properties = new IdentityProperties();
		properties.getLinks().setBaseUrl(baseUrl);
		return new FrontendVerificationLinkBuilder(properties);
	}

	@Test
	@DisplayName("activacion y reset apuntan a pantallas distintas")
	void cada_tipo_tiene_su_pantalla() {
		FrontendVerificationLinkBuilder builder = builder("https://app.akine.test");

		assertThat(builder.enlaceDe(TipoTokenVerificacion.ACTIVACION, "abc123"))
				.isEqualTo("https://app.akine.test/activar?token=abc123");
		assertThat(builder.enlaceDe(TipoTokenVerificacion.RESET, "abc123"))
				.isEqualTo("https://app.akine.test/restablecer?token=abc123");
	}

	@Test
	@DisplayName("la barra final de la base no duplica la de la ruta")
	void la_barra_final_no_se_duplica() {
		assertThat(builder("https://app.akine.test/")
				.enlaceDe(TipoTokenVerificacion.ACTIVACION, "abc123"))
				.isEqualTo("https://app.akine.test/activar?token=abc123");
	}

	@Test
	@DisplayName("el token se codifica para URL")
	void el_token_se_codifica() {
		// El generador propio no produce estos caracteres, pero el builder no puede asumir
		// quien lo llama: un token con un & sin escapar llega roto y el soporte no lo entiende.
		assertThat(builder("https://app.akine.test")
				.enlaceDe(TipoTokenVerificacion.RESET, "a+b/c=d&e"))
				.isEqualTo("https://app.akine.test/restablecer?token=a%2Bb%2Fc%3Dd%26e");
	}

	@Test
	@DisplayName("sin token no hay enlace que armar")
	void sin_token_falla() {
		FrontendVerificationLinkBuilder builder = builder("https://app.akine.test");

		assertThatThrownBy(() -> builder.enlaceDe(TipoTokenVerificacion.RESET, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> builder.enlaceDe(TipoTokenVerificacion.RESET, "  "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> builder.enlaceDe(null, "abc123"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("las rutas y el parametro son configurables por entorno")
	void las_rutas_son_configurables() {
		IdentityProperties properties = new IdentityProperties();
		properties.getLinks().setBaseUrl("https://otro.test");
		// Sin barra inicial: el builder la agrega, para que una ruta mal escrita en el yml no
		// produzca "https://otro.testalta/confirmar".
		properties.getLinks().setActivationPath("alta/confirmar");
		properties.getLinks().setResetPath("/reset");
		properties.getLinks().setTokenParam("t");

		assertThat(new FrontendVerificationLinkBuilder(properties)
				.enlaceDe(TipoTokenVerificacion.ACTIVACION, "abc123"))
				.isEqualTo("https://otro.test/alta/confirmar?t=abc123");
	}
}
