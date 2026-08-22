package com.akine.platform.infrastructure.config;

import java.util.List;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata del contrato OpenAPI canonico de AKINE.
 *
 * <p>El backend es propietario del contrato (plan, decision de contrato API). La estrategia
 * adoptada en AKINE-00.01 es <b>code-first con gate de drift</b>:
 *
 * <ol>
 *   <li>springdoc genera la especificacion a partir de los controllers anotados.</li>
 *   <li>El YAML resultante se commitea en {@code openapi/akine-api.yaml}.</li>
 *   <li>CI regenera y compara: si difiere del commiteado, el build falla.</li>
 * </ol>
 *
 * <p>Consecuencia buscada: todo cambio de contrato aparece como diff revisable en el pull
 * request, sin obligar a escribir a mano el YAML de 29 modulos.
 *
 * <p>La version del contrato es SemVer y se sube a mano en {@code pom.xml}
 * ({@code akine.contract.version}): minor para cambios aditivos, major para incompatibles.
 * No sigue la version de la aplicacion.
 */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI akineOpenAPI(
			@Value("${akine.contract.version}") String contractVersion) {
		return new OpenAPI()
				// Servidor relativo, declarado explicitamente.
				//
				// Sin esto springdoc infiere la URL del servidor en ejecucion, que en los
				// tests es un puerto aleatorio: el contrato cambiaria en cada corrida y el
				// gate de drift daria un falso positivo cada vez.
				//
				// Ademas es lo correcto para el cliente generado: la URL base la decide la
				// app en cada entorno, no queda horneada desde una maquina de desarrollo.
				.servers(List.of(new Server()
						.url("/")
						.description("Servidor que sirve este contrato")))
				.info(new Info()
						.title("AKINE API")
						.version(contractVersion)
						.description("""
								Contrato REST canonico de AKINE: SaaS multi-tenant para centros de \
								kinesiologia, fisioterapia, rehabilitacion y actividades de salud.

								Este artefacto es propiedad del repositorio backend. El frontend \
								genera su cliente TypeScript desde una version fijada de este \
								contrato y no define DTO manuales.""")
						.contact(new Contact().name("AKINE").url("https://github.com/Chapadiex/appKine-api")));
	}
}
