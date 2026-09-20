package com.akine.platform.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * El contrato tiene que decir <b>como se autentica la API</b>.
 *
 * <p>Hasta AKINE-07.07 no declaraba ningun {@code securityScheme}: para cualquier generador de
 * clientes la API entera se leia como publica. Estos tests fijan las tres propiedades que hacen
 * util la declaracion, y la tercera es la que no se ve a simple vista.
 */
@DisplayName("Los esquemas de seguridad del contrato")
class OpenApiSecuritySchemesTest {

	private static final String LOGIN = "/api/v1/auth/login";
	private static final String REFRESH = "/api/v1/auth/refresh";
	private static final String PACIENTES = "/api/v1/personas";

	private final OpenApiCustomizer customizer = new OpenApiConfig().akineSecuritySchemes();

	private OpenAPI contratoDeEjemplo() {
		OpenAPI openApi = new OpenAPI();
		Paths paths = new Paths();
		paths.addPathItem(LOGIN, new PathItem().post(new Operation().operationId("login")));
		paths.addPathItem(REFRESH, new PathItem().post(new Operation().operationId("refrescar")));
		paths.addPathItem(PACIENTES, new PathItem().get(new Operation().operationId("listar")));
		openApi.setPaths(paths);
		return openApi;
	}

	@Test
	@DisplayName("declaran el bearer JWT y la cookie de refresh, y el bearer es el default global")
	void declaranLosDosEsquemasYElDefault() {
		OpenAPI contrato = contratoDeEjemplo();

		customizer.customise(contrato);

		SecurityScheme bearer = contrato.getComponents().getSecuritySchemes().get("bearerAuth");
		assertThat(bearer.getType()).isEqualTo(SecurityScheme.Type.HTTP);
		assertThat(bearer.getScheme()).isEqualTo("bearer");
		assertThat(bearer.getBearerFormat()).isEqualTo("JWT");

		SecurityScheme cookie = contrato.getComponents().getSecuritySchemes().get("refreshCookie");
		assertThat(cookie.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
		assertThat(cookie.getIn()).isEqualTo(SecurityScheme.In.COOKIE);
		assertThat(cookie.getName()).isEqualTo("akine_rt");

		// Autenticado es el default, igual que anyRequest().authenticated() en la cadena.
		assertThat(contrato.getSecurity()).singleElement()
				.satisfies(requisito -> assertThat(requisito).containsKey("bearerAuth"));
	}

	@Test
	@DisplayName("eximen a las publicas y le ponen la cookie —no el bearer— al refresh")
	void distinguenPublicaDeCookieYDeProtegida() {
		OpenAPI contrato = contratoDeEjemplo();

		customizer.customise(contrato);

		// Publica: lista VACIA. Ausencia significaria "hereda el bearer", que es lo contrario.
		assertThat(contrato.getPaths().get(LOGIN).getPost().getSecurity()).isEmpty();

		// Refresh: su unica credencial es la cookie, y por eso no hereda el bearer.
		assertThat(contrato.getPaths().get(REFRESH).getPost().getSecurity())
				.singleElement()
				.satisfies(requisito -> assertThat(requisito).containsOnlyKeys("refreshCookie"));

		// Protegida: no se le toca nada, hereda el requisito global.
		assertThat(contrato.getPaths().get(PACIENTES).getGet().getSecurity()).isNull();
	}

	/**
	 * La propiedad que no se ve razonando el objeto: {@code security: []} tiene que
	 * <b>sobrevivir a la serializacion</b>.
	 *
	 * <p>Si el mapper descartara la lista vacia por venir vacia, el YAML publicado diria que el
	 * login hereda el requisito global y el cliente generado mandaria un token que todavia no
	 * tiene. El sintoma no seria un error de contrato sino un 401 en el primer request de la
	 * aplicacion, y el YAML se veria correcto.
	 */
	@Test
	@DisplayName("y el security vacio de las publicas sobrevive a la serializacion del YAML")
	void laListaVaciaSobreviveAlYaml() {
		OpenAPI contrato = contratoDeEjemplo();
		customizer.customise(contrato);

		String yaml = Yaml.pretty(contrato);

		List<String> lineas = yaml.lines().map(String::strip).toList();
		assertThat(lineas).contains("security: []");
		assertThat(yaml).contains("bearerFormat: JWT");
		assertThat(yaml).contains("name: akine_rt");
	}
}
