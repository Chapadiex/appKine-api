package com.akine.identity.infrastructure;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * <b>Sin secreto de firma, la aplicacion no arranca.</b> ADR-0017, al pie de la letra.
 *
 * <h2>Que estaba roto</h2>
 *
 * <p>El ADR dice "secreto de ≥256 bits por variable de entorno. Sin default en produccion: sin
 * secreto la aplicacion no arranca". La implementacion hacia lo contrario: {@code application.yml}
 * traia como default el secreto de desarrollo —que esta versionado en el repositorio y por lo
 * tanto es publico— y el unico guardarrail era que el perfil activo se llamara {@code prod},
 * {@code produccion} o {@code production}. Encima {@code spring.profiles.default: local} hacia
 * que arrancar <b>sin</b> {@code SPRING_PROFILES_ACTIVE} tampoco contara como produccion.
 *
 * <p>Resultado: cualquier despliegue con perfil {@code staging}, {@code docker}, {@code prd},
 * {@code k8s} o sin perfil arrancaba firmando con una clave publica. Con esa clave se forja a
 * mano un token con {@code scope:"context"}, el {@code accountId} de un profesional real y su
 * {@code org}/{@code loc}: el filtro de contexto lo valida contra la base, la membership existe,
 * y se lee historia clinica sin credenciales. Con {@code "rol":"PLATFORM_ADMIN"} el filtro hace
 * short-circuit sin consultar la base, y son todos los tenants.
 *
 * <h2>Que fija este test</h2>
 *
 * <p>Que la regla esta al derecho: el modo seguro es el default y el desarrollo es la excepcion
 * declarada por nombre positivo. Los casos negativos usan {@code staging} a proposito —un nombre
 * plausible que nadie previo— porque el agujero nunca estuvo en los nombres previstos.
 */
class ArranqueSinSecretoTest {

	private static final String SECRETO_VALIDO =
			"secreto-sintetico-de-test-con-mas-de-32-bytes-de-largo";

	private JwtEmitter emisorCon(String secreto, String... perfilesActivos) {
		JwtEmitter.JwtProperties propiedades = new JwtEmitter.JwtProperties();
		propiedades.setSecret(secreto);
		propiedades.setAccessTtl(Duration.ofMinutes(10));

		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(perfilesActivos);
		return new JwtEmitter(propiedades, environment);
	}

	// =================================================================================
	// Sin secreto no se arranca
	// =================================================================================

	@Test
	@DisplayName("sin secreto y sin ningun perfil activo: no arranca")
	void sin_secreto_y_sin_perfil_no_arranca() {
		assertThatIllegalStateException()
				.isThrownBy(() -> emisorCon(null))
				.withMessageContaining("AKINE_JWT_SECRET");
	}

	@Test
	@DisplayName("sin secreto en un perfil que nadie previo (staging): no arranca")
	void sin_secreto_en_un_perfil_desconocido_no_arranca() {
		assertThatIllegalStateException()
				.isThrownBy(() -> emisorCon("", "staging"))
				.withMessageContaining("AKINE_JWT_SECRET");
	}

	@Test
	@DisplayName("sin secreto tampoco arranca en un perfil de desarrollo: el default es no arrancar")
	void sin_secreto_tampoco_arranca_en_desarrollo() {
		// El perfil de desarrollo exime de usar el secreto PUBLICO, no de tener uno. Que esta
		// rama tambien falle es lo que hace que la ausencia de secreto nunca sea silenciosa.
		assertThatIllegalStateException()
				.isThrownBy(() -> emisorCon(null, "local"))
				.withMessageContaining("AKINE_JWT_SECRET");
	}

	// =================================================================================
	// El secreto publico solo se tolera en un perfil de desarrollo declarado
	// =================================================================================

	@Test
	@DisplayName("el secreto de desarrollo en un perfil desconocido: no arranca")
	void el_secreto_de_desarrollo_en_un_perfil_desconocido_no_arranca() {
		assertThatIllegalStateException()
				.isThrownBy(() -> emisorCon(JwtEmitter.SECRETO_DE_DESARROLLO, "staging"))
				.withMessageContaining("DESARROLLO");
	}

	@Test
	@DisplayName("el secreto de desarrollo sin ningun perfil activo: no arranca")
	void el_secreto_de_desarrollo_sin_perfil_no_arranca() {
		// Es el caso que el `spring.profiles.default: local` de application.yml tapaba: un
		// despliegue al que se le olvido SPRING_PROFILES_ACTIVE no es un entorno de desarrollo.
		assertThatIllegalStateException()
				.isThrownBy(() -> emisorCon(JwtEmitter.SECRETO_DE_DESARROLLO))
				.withMessageContaining("DESARROLLO");
	}

	@Test
	@DisplayName("el secreto de desarrollo con el perfil local activo: arranca")
	void el_secreto_de_desarrollo_en_local_arranca() {
		assertThatCode(() -> emisorCon(JwtEmitter.SECRETO_DE_DESARROLLO, "local"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("un secreto propio arranca en cualquier perfil, incluido uno desconocido")
	void un_secreto_propio_arranca_en_cualquier_perfil() {
		assertThatCode(() -> emisorCon(SECRETO_VALIDO, "staging"))
				.doesNotThrowAnyException();
		assertThatCode(() -> emisorCon(SECRETO_VALIDO)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("un secreto de menos de 32 bytes no arranca en ningun perfil")
	void un_secreto_corto_no_arranca() {
		assertThatIllegalStateException()
				.isThrownBy(() -> emisorCon("corto", "local"))
				.withMessageContaining("32 bytes");
	}

	// =================================================================================
	// Y no es solo el constructor: el CONTEXTO no levanta
	// =================================================================================

	@Test
	@DisplayName("el contexto de Spring no levanta sin secreto: es un arranque fallido, no un warning")
	void el_contexto_no_levanta_sin_secreto() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of())
				.withUserConfiguration(SoloElEmisor.class)
				.withPropertyValues("spring.profiles.active=staging")
				.run(contexto -> assertThat(contexto)
						.as("un bean que no se puede construir hace fallar el refresh del "
								+ "contexto, o sea el arranque de la aplicacion")
						.hasFailed());
	}

	@Test
	@DisplayName("con el secreto configurado, el mismo contexto levanta")
	void el_contexto_levanta_con_secreto() {
		new ApplicationContextRunner()
				.withUserConfiguration(SoloElEmisor.class)
				.withPropertyValues(
						"spring.profiles.active=staging",
						"akine.security.jwt.secret=" + SECRETO_VALIDO)
				.run(contexto -> assertThat(contexto).hasSingleBean(JwtEmitter.class));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(JwtEmitter.JwtProperties.class)
	static class SoloElEmisor {

		@org.springframework.context.annotation.Bean
		JwtEmitter jwtEmitter(
				JwtEmitter.JwtProperties propiedades,
				org.springframework.core.env.Environment environment) {
			return new JwtEmitter(propiedades, environment);
		}
	}
}
