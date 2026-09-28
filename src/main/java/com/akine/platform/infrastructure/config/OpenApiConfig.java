package com.akine.platform.infrastructure.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.akine.platform.spi.problem.ProblemType;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
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

	/** Nombre del esquema de token de acceso, tal como lo referencian las operaciones. */
	static final String ESQUEMA_BEARER = "bearerAuth";

	/** Nombre del esquema de la cookie de refresh. */
	static final String ESQUEMA_COOKIE_DE_REFRESH = "refreshCookie";

	/**
	 * Nombre de la cookie de refresh, duplicado a proposito.
	 *
	 * <p>La constante original es {@code identity.api.RefreshCookies.NOMBRE}, que es
	 * package-private y vive en otro modulo: {@code platform} no puede importar
	 * {@code identity.api} —lo prohibe la regla de modulos y lo verifica ArchUnit—. Se repite el
	 * literal en vez de abrir un paquete interno o inventar un puerto en {@code spi} para un
	 * string de diez caracteres. Si alguna vez cambia, cambia en dos lugares.
	 */
	private static final String NOMBRE_DE_LA_COOKIE_DE_REFRESH = "akine_rt";

	/**
	 * Operaciones publicas que NO son de identidad y por lo tanto no salen de la lista de
	 * {@link SecurityConfig}. Hoy es una sola: el contrato tecnico de versionado, publico desde
	 * AKINE-00.01. {@code /actuator/**} no figura porque springdoc no lo documenta.
	 */
	private static final Set<String> RUTAS_PUBLICAS_FUERA_DE_IDENTIDAD = Set.of("/api/v1/version");

	/**
	 * Declara <b>como se autentica</b> la API. Hueco preexistente desde AKINE-00.01 y cerrado en
	 * AKINE-07.07.
	 *
	 * <h2>Que estaba roto</h2>
	 *
	 * <p>El contrato no declaraba ningun {@code securityScheme}, en ningun modulo. Para
	 * cualquier lector —humano o generador de codigo— la API entera se leia como publica. El
	 * frontend de AKINE funciona igual porque agrega el {@code Authorization} en un interceptor
	 * escrito a mano, o sea <b>fuera</b> del cliente generado; pero eso es una compensacion de un
	 * consumidor particular, no una propiedad del contrato. Un consumidor nuevo generado desde el
	 * YAML no tiene de donde saber que la API pide token, y lo descubre con un 401 en runtime.
	 *
	 * <h2>Que declara</h2>
	 *
	 * <ul>
	 *   <li>{@code bearerAuth} — {@code http}/{@code bearer}, formato JWT. Es el access token
	 *       acotado al contexto (ADR-0017), y es el default <b>de toda la API</b>: el requisito
	 *       se declara en la raiz del documento, igual que la cadena declara
	 *       {@code anyRequest().authenticated()}. Autenticado es el default y publico es la
	 *       excepcion, tambien en el contrato.</li>
	 *   <li>{@code refreshCookie} — {@code apiKey} en cookie {@code akine_rt}. Es la unica
	 *       credencial de {@code /auth/refresh} y {@code /auth/logout}. Va declarada aunque el
	 *       cliente no pueda leerla —es {@code httpOnly}—: lo que el contrato tiene que decir es
	 *       que esas dos operaciones <b>necesitan credenciales y no son el bearer</b>, porque de
	 *       ahi sale que el cliente generado tiene que mandarlas {@code withCredentials}.</li>
	 * </ul>
	 *
	 * <h2>Por que las publicas llevan {@code security: []} y no se omiten</h2>
	 *
	 * <p>Omitir {@code security} en una operacion significa "hereda el default", que ahora es
	 * "exige bearer". La lista vacia es la unica forma que tiene OpenAPI 3 de decir "esta
	 * operacion NO requiere autenticacion" cuando hay un requisito global. Sin ella, el cliente
	 * generado mandaria token al login, que es exactamente el request que todavia no lo tiene.
	 *
	 * <h2>De donde sale la lista</h2>
	 *
	 * <p>De {@link SecurityConfig#RUTAS_PUBLICAS_DE_IDENTIDAD} y
	 * {@link SecurityConfig#RUTAS_CON_COOKIE_DE_REFRESH}, las mismas constantes que arma la
	 * cadena de filtros. <b>No hay una segunda lista.</b> Una copia escrita aparte divergiria en
	 * la primera ruta que alguien abra o cierre de un solo lado, y la divergencia no la
	 * detectaria ningun test: el contrato seguiria siendo valido, solo que mentiroso.
	 *
	 * <h2>Compatibilidad</h2>
	 *
	 * <p>Es un cambio <b>aditivo</b>: no toca ningun {@code path}, {@code schema} ni respuesta.
	 * Un cliente ya generado sigue compilando; uno nuevo aprende a autenticarse.
	 */
	@Bean
	public OpenApiCustomizer akineSecuritySchemes() {
		return openApi -> {
			if (openApi.getComponents() == null) {
				openApi.setComponents(new Components());
			}
			openApi.getComponents()
					.addSecuritySchemes(ESQUEMA_BEARER, new SecurityScheme()
							.type(SecurityScheme.Type.HTTP)
							.scheme("bearer")
							.bearerFormat("JWT")
							.description("""
									Access token de AKINE, acotado al contexto (organizacion + \
									consultorio) elegido despues del login. Se obtiene en \
									POST /api/v1/auth/login y se renueva en \
									POST /api/v1/auth/refresh.

									Vive solo en memoria del cliente: no se guarda en \
									localStorage ni en sessionStorage. Su reemplazo se pide con \
									la cookie de refresh, que el navegador manda sola."""))
					.addSecuritySchemes(ESQUEMA_COOKIE_DE_REFRESH, new SecurityScheme()
							.type(SecurityScheme.Type.APIKEY)
							.in(SecurityScheme.In.COOKIE)
							.name(NOMBRE_DE_LA_COOKIE_DE_REFRESH)
							.description("""
									Refresh token en cookie httpOnly, Secure y SameSite=Strict. \
									El cliente no puede leerla ni escribirla: la emite el \
									servidor y la manda el navegador. Un cliente generado tiene \
									que hacer estas dos llamadas con las credenciales incluidas \
									(withCredentials / credentials: 'include').

									Cada canje la rota: el valor presentado queda invalidado \
									aunque el canje falle."""));

			// Requisito global: por default toda operacion exige el bearer, igual que la cadena
			// exige autenticacion para anyRequest().
			openApi.setSecurity(new ArrayList<>(
					List.of(new SecurityRequirement().addList(ESQUEMA_BEARER))));

			Set<String> publicasDeIdentidad =
					new LinkedHashSet<>(List.of(SecurityConfig.RUTAS_PUBLICAS_DE_IDENTIDAD));
			Set<String> conCookie = new LinkedHashSet<>(SecurityConfig.RUTAS_CON_COOKIE_DE_REFRESH);

			if (openApi.getPaths() == null) {
				return;
			}
			openApi.getPaths().forEach((ruta, item) -> {
				if (conCookie.contains(ruta)) {
					// No heredan el bearer: su unica credencial es la cookie.
					aplicarATodasLasOperaciones(item, operacion -> operacion.setSecurity(
							new ArrayList<>(List.of(new SecurityRequirement()
									.addList(ESQUEMA_COOKIE_DE_REFRESH)))));
				} else if (publicasDeIdentidad.contains(ruta)
						|| RUTAS_PUBLICAS_FUERA_DE_IDENTIDAD.contains(ruta)) {
					// Lista vacia, no ausencia: la ausencia hereda el requisito global.
					aplicarATodasLasOperaciones(item,
							operacion -> operacion.setSecurity(new ArrayList<>()));
				}
			});
		};
	}

	private static void aplicarATodasLasOperaciones(
			PathItem item, java.util.function.Consumer<Operation> ajuste) {
		item.readOperations().forEach(ajuste);
	}
}
