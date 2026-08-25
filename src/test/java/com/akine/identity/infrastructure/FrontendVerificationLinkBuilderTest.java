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

	/**
	 * Los tres tipos apuntan a pantallas distintas, y las tres <b>bajo {@code /auth}</b>.
	 *
	 * <p>El prefijo es lo que este test existe para fijar. El frontend monta las pantallas
	 * publicas bajo esa ruta, asi que un enlace a {@code /activar} pelado cae en el comodin
	 * {@code **} del router y el usuario ve "pagina no encontrada" con un token perfectamente
	 * valido en la URL.
	 *
	 * <p><b>Estuvo roto desde 01.02 y ningun test lo vio</b>, porque los tres casos de abajo
	 * fijaban el valor por defecto que estaba mal y los demas tests del builder configuraban su
	 * propia ruta. Se corrige en 02.03, que es la etapa que agrega el tercer enlace de correo.
	 */
	@Test
	@DisplayName("los tres tipos apuntan a su pantalla, bajo /auth como las sirve el frontend")
	void cada_tipo_tiene_su_pantalla() {
		FrontendVerificationLinkBuilder builder = builder("https://app.akine.test");

		assertThat(builder.enlaceDe(TipoTokenVerificacion.ACTIVACION, "abc123"))
				.isEqualTo("https://app.akine.test/auth/activar?token=abc123");
		assertThat(builder.enlaceDe(TipoTokenVerificacion.RESET, "abc123"))
				.isEqualTo("https://app.akine.test/auth/restablecer?token=abc123");
		assertThat(builder.enlaceDe(TipoTokenVerificacion.INVITACION, "abc123"))
				.isEqualTo("https://app.akine.test/auth/invitacion?token=abc123");
	}

	@Test
	@DisplayName("la barra final de la base no duplica la de la ruta")
	void la_barra_final_no_se_duplica() {
		assertThat(builder("https://app.akine.test/")
				.enlaceDe(TipoTokenVerificacion.ACTIVACION, "abc123"))
				.isEqualTo("https://app.akine.test/auth/activar?token=abc123");
	}

	@Test
	@DisplayName("el token se codifica para URL")
	void el_token_se_codifica() {
		// El generador propio no produce estos caracteres, pero el builder no puede asumir
		// quien lo llama: un token con un & sin escapar llega roto y el soporte no lo entiende.
		assertThat(builder("https://app.akine.test")
				.enlaceDe(TipoTokenVerificacion.RESET, "a+b/c=d&e"))
				.isEqualTo("https://app.akine.test/auth/restablecer?token=a%2Bb%2Fc%3Dd%26e");
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
