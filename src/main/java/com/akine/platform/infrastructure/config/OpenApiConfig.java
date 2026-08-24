package com.akine.platform.infrastructure.config;

import java.util.List;

import com.akine.platform.spi.problem.ProblemType;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
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

	/**
	 * Publica el catalogo de {@code type} de Problem Details como el schema {@code ProblemType}.
	 *
	 * <h2>Que problema resuelve</h2>
	 *
	 * <p>El {@code type} es el unico campo de un Problem Detail que una maquina puede ramificar:
	 * el {@code status} agrupa demasiado —los cuatro conflictos de colaborador y los seis de sede
	 * son todos 409— y el {@code detail} es prosa en castellano que cambia sin aviso. Hasta ahora
	 * los valores solo aparecian dentro de las <b>descripciones</b> de las operaciones, o sea en
	 * texto libre, y el cliente mantenia su propia lista escrita a mano. Dos listas hechas por
	 * separado divergen.
	 *
	 * <p>Publicado como enum de strings, el generador del frontend produce un tipo cerrado: usar
	 * un valor que no esta en el catalogo deja de compilar del lado del cliente, que es la
	 * verificacion que antes no existia en ningun lado.
	 *
	 * <h2>Por que un schema aparte y no un campo tipado en {@code ProblemDetail}</h2>
	 *
	 * <p>{@code ProblemDetail} es el schema estandar que aporta Spring: retipar ahi su
	 * {@code type} de {@code string/uri} a un enum cerrado es un cambio <b>incompatible</b> sobre
	 * un schema que ya consumen todas las respuestas de error, y ademas mentiria — un 500 de un
	 * componente que todavia no adopto el catalogo saldria con un {@code type} fuera del enum y
	 * el cliente lo rechazaria al deserializar. El catalogo va al lado, y el campo sigue siendo
	 * una URI libre.
	 *
	 * <h2>Por que un {@code OpenApiCustomizer} y no {@code .components()} en el bean de arriba</h2>
	 *
	 * <p>El customizer corre <b>despues</b> de que springdoc calculo los componentes a partir de
	 * los controllers, asi que agrega sin riesgo de que la generacion pise lo declarado a mano.
	 *
	 * <p>Los valores salen de {@link ProblemType}, que es de donde los sacan tambien los tres
	 * advices: el contrato no puede quedar desincronizado del codigo porque es el mismo dato.
	 */
	@Bean
	public OpenApiCustomizer akineProblemTypeCatalog() {
		return openApi -> {
			if (openApi.getComponents() == null) {
				openApi.setComponents(new Components());
			}
			openApi.getComponents().addSchemas("ProblemType", new StringSchema()
					._enum(ProblemType.valores())
					.description("""
							Catalogo cerrado de los valores que puede tomar el campo type de un \
							Problem Detail (RFC 7807). Es el unico campo del error pensado para \
							que lo lea una maquina: el status agrupa demasiado y el detail es \
							texto para personas. Ramificar por este valor y nunca por el detail.

							Agregar un valor es un cambio aditivo; quitarlo o resignificarlo es \
							incompatible y exige version mayor del contrato."""));
		};
	}
}
