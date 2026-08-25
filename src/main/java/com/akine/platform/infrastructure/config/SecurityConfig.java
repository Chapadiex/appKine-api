package com.akine.platform.infrastructure.config;

import com.akine.platform.spi.problem.ProblemType;
import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.akine.platform.infrastructure.security.JwtAuthenticationFilter;
import com.akine.platform.infrastructure.security.ProblemAccessDeniedHandler;
import com.akine.platform.infrastructure.security.ProblemAuthenticationEntryPoint;
import com.akine.platform.infrastructure.security.RateLimitFilter;
import com.akine.platform.infrastructure.security.RequestPaths;
import com.akine.platform.infrastructure.tenant.TenantContextFilter;
import com.akine.platform.spi.config.PerfilesDeEjecucion;

/**
 * La cadena de seguridad del backend.
 *
 * <h2>Que cambio en AKINE-01.02</h2>
 *
 * <p>Se termino el {@code anyRequest().permitAll()} de las etapas anteriores. Ahora
 * <b>autenticado es el default y publico es la excepcion</b>, escrita ruta por ruta. La
 * diferencia importa mas de lo que parece: con la regla al reves, un endpoint nuevo nace
 * protegido y se abre a proposito; con la regla original, nacia abierto y habia que acordarse
 * de cerrarlo.
 *
 * <h2>Orden de la cadena</h2>
 *
 * <pre>
 *   CorsFilter
 *     -&gt; RateLimitFilter            (antes de autenticar: no gastar Argon2id en lo que se rechaza)
 *     -&gt; SecurityContextHolderFilter
 *     -&gt; JwtAuthenticationFilter    (publica el AuthenticatedPrincipal a partir del bearer)
 *     -&gt; AuthorizationFilter        (aplica authorizeHttpRequests)
 *     -&gt; TenantContextFilter        (revalida el contexto contra la base y lo publica)
 *     -&gt; DispatcherServlet
 * </pre>
 *
 * <p><b>Es exactamente el orden que 01.01 dejo previsto</b> en el javadoc de
 * {@code TenantContextFilter}, y no se movio nada de lo que ya estaba: el filtro de contexto
 * sigue despues de {@code AuthorizationFilter}. Lo unico nuevo antes de lo previsto es el rate
 * limit, que va al principio porque no necesita identidad y porque limitar despues de verificar
 * credenciales significaria pagar el hasheo de cada intento que igual se iba a rechazar.
 *
 * <p>El contexto se resuelve DESPUES de autenticar —antes no hay principal que revalidar— y
 * DESPUES de autorizar la ruta, para no pegarle a la base por un request que la cadena va a
 * rechazar de todos modos.
 *
 * <h2>401 y 403 no son intercambiables</h2>
 *
 * <p>Sin credencial valida: <b>401</b> ({@link ProblemAuthenticationEntryPoint}). Autenticado
 * pero sin permiso: <b>403</b> ({@link ProblemAccessDeniedHandler}). Autenticado pero sin
 * contexto de trabajo elegido: <b>403</b>, y lo responde {@code TenantContextFilter}. El motivo
 * es concreto y esta documentado en ese filtro: el interceptor del frontend borra el token ante
 * cualquier 401, asi que un 403 devuelto como 401 encierra al usuario en un bucle de login.
 *
 * <h2>CSRF</h2>
 *
 * <p>El CSRF nativo de Spring sigue deshabilitado: la API es stateless por Bearer y no usa
 * cookie de sesion. Pero desde 01.02 hay <b>dos</b> endpoints que se autentican con una cookie
 * —{@code /auth/refresh} y {@code /auth/logout}, que reciben {@code akine_rt}— y el navegador
 * manda las cookies solo, sin que la pagina intervenga. Para esos dos corre
 * {@link OriginCsrfFilter}, que valida {@code Origin} contra los origenes permitidos por
 * configuracion, tal como exige ADR-0017. Es la defensa que se suma al {@code SameSite=Strict}
 * de la cookie: {@code SameSite} depende del navegador, la validacion de {@code Origin} no.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	/**
	 * Rutas publicas de identidad. <b>Las ocho existen y estan publicadas</b> por los
	 * controllers de {@code identity.api} desde AKINE-01.02.
	 *
	 * <p>Son publicas porque quien las usa <b>todavia no tiene sesion</b>, no porque sean
	 * inofensivas: son justamente las mas atacadas, y por eso son las que limita
	 * {@code RateLimitFilter} y las que ADR-0018 obliga a responder de forma uniforme.
	 *
	 * <p>Se declaran con metodo ademas de ruta: las ocho son {@code POST}, y abrir la ruta
	 * entera dejaria pasar sin autenticar cualquier verbo que alguien agregue mas adelante
	 * sobre el mismo path.
	 *
	 * <p><b>Lo que NO esta aca y esta bajo el mismo prefijo:</b>
	 * {@code POST /api/v1/auth/context} y {@code DELETE /api/v1/auth/sessions} exigen
	 * autenticacion. Operan sobre una identidad ya establecida, asi que caen en el
	 * {@code anyRequest().authenticated()} de abajo sin necesidad de una regla propia.
	 */
	private static final String[] RUTAS_PUBLICAS_DE_IDENTIDAD = {
			// Sesion.
			"/api/v1/auth/login",
			"/api/v1/auth/refresh",
			// El logout es publico porque solo presenta la cookie de refresh: exigir un access
			// vivo impediria cerrar sesion justo cuando mas hace falta, con el access vencido.
			"/api/v1/auth/logout",
			// Registro y activacion.
			"/api/v1/auth/register",
			"/api/v1/auth/activate",
			"/api/v1/auth/activation/resend",
			// Recuperacion de contrasena.
			"/api/v1/auth/password-reset",
			"/api/v1/auth/password-reset/confirm",
			// Invitacion a colaborar (M05). Las tres son publicas porque quien acepta o rechaza
			// no tiene sesion —y muchas veces ni cuenta—: lo que lo autoriza es el token del
			// enlace, que prueba que llega al buzon al que se emitio la invitacion.
			"/api/v1/auth/invitations/preview",
			"/api/v1/auth/invitations/accept",
			"/api/v1/auth/invitations/decline"
	};

	/**
	 * Rutas que reciben la cookie {@code akine_rt} y ninguna otra credencial.
	 *
	 * <p>Son las unicas del sistema vulnerables a CSRF, y por eso son las unicas que valida
	 * {@link OriginCsrfFilter}. El resto de la API se autentica con {@code Authorization:
	 * Bearer}, un header que un sitio hostil no puede hacer que el navegador envie.
	 */
	private static final List<String> RUTAS_CON_COOKIE_DE_REFRESH = List.of(
			"/api/v1/auth/refresh",
			"/api/v1/auth/logout");

	/**
	 * Documentacion del contrato. <b>Cerrada por default; publica solo en desarrollo.</b>
	 *
	 * <p>La regla estaba al reves: se cerraba si el perfil activo se llamaba {@code prod},
	 * {@code produccion} o {@code production}, y se publicaba en cualquier otro caso. Con eso,
	 * un despliegue con perfil {@code staging}, {@code docker}, {@code prd}, {@code k8s} o sin
	 * perfil servia a cualquiera el mapa completo de la API: cada endpoint, cada campo, cada
	 * codigo de error. Ver {@link PerfilesDeEjecucion}.
	 */
	private static final String[] RUTAS_DE_DOCUMENTACION = {
			"/v3/api-docs",
			"/v3/api-docs/**",
			"/v3/api-docs.yaml",
			"/swagger-ui.html",
			"/swagger-ui/**"
	};

	private final List<String> allowedOrigins;
	private final ObjectProvider<TenantContextFilter> tenantContextFilter;
	private final ObjectProvider<JwtAuthenticationFilter> jwtAuthenticationFilter;
	private final ObjectProvider<RateLimitFilter> rateLimitFilter;
	private final Environment environment;

	/**
	 * Los tres filtros se reciben como {@link ObjectProvider} y no como dependencias
	 * obligatorias.
	 *
	 * <p>El motivo es el mismo para los tres y viene de 01.01: los slices {@code @WebMvcTest}
	 * importan esta configuracion sin el modulo de tenancy, sin sus repositorios y sin el emisor
	 * de tokens; exigirlos los volveria imposibles de levantar. En la aplicacion completa
	 * siempre estan, y si falta alguno se avisa con un WARN, porque una cadena a la que le falta
	 * una pieza de seguridad no puede pasar inadvertida.
	 *
	 * <p>Un slice sin {@code JwtAuthenticationFilter} sigue pudiendo probar autorizacion: el
	 * principal se inyecta por request con el post-processor {@code authentication(..)} de
	 * spring-security-test, y las reglas de {@code authorizeHttpRequests} se evaluan igual.
	 */
	public SecurityConfig(
			@Value("${akine.security.cors.allowed-origins}") List<String> allowedOrigins,
			ObjectProvider<TenantContextFilter> tenantContextFilter,
			ObjectProvider<JwtAuthenticationFilter> jwtAuthenticationFilter,
			ObjectProvider<RateLimitFilter> rateLimitFilter,
			Environment environment) {
		this.allowedOrigins = allowedOrigins;
		this.tenantContextFilter = tenantContextFilter;
		this.jwtAuthenticationFilter = jwtAuthenticationFilter;
		this.rateLimitFilter = rateLimitFilter;
		this.environment = environment;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		boolean desarrollo = PerfilesDeEjecucion.esDesarrollo(environment);
		if (!desarrollo) {
			log.info("La documentacion del contrato NO se publica: no hay ningun perfil de "
					+ "desarrollo activo {}. Es el comportamiento por default.",
					PerfilesDeEjecucion.perfilesDeDesarrollo());
		}

		http
				.cors(Customizer.withDefaults())
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.headers(headers -> headers
						.frameOptions(frame -> frame.deny())
						.contentTypeOptions(Customizer.withDefaults())
						.httpStrictTransportSecurity(hsts -> hsts
								.includeSubDomains(true)
								.maxAgeInSeconds(31_536_000))
						.referrerPolicy(referrer -> referrer.policy(
								org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
										.ReferrerPolicy.NO_REFERRER)))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(new ProblemAuthenticationEntryPoint())
						.accessDeniedHandler(new ProblemAccessDeniedHandler()))
				.authorizeHttpRequests(auth -> {
					// El preflight de CORS no lleva credenciales por definicion: exigirlas
					// rompe toda llamada del navegador desde otro origen.
					auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();

					// Contrato tecnico de versionado: publico desde 00.01.
					auth.requestMatchers(HttpMethod.GET, "/api/v1/version").permitAll();

					// Health para los probes del orquestador. El resto de Actuator queda
					// autenticado: expone informacion operativa del sistema.
					auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();

					auth.requestMatchers(HttpMethod.POST, RUTAS_PUBLICAS_DE_IDENTIDAD).permitAll();

					if (desarrollo) {
						auth.requestMatchers(RUTAS_DE_DOCUMENTACION).permitAll();
					} else {
						// El contrato describe cada endpoint y cada campo del sistema. En
						// desarrollo es una herramienta; en cualquier otro lado es un mapa para
						// quien quiera atacarlo. El default es cerrar.
						auth.requestMatchers(RUTAS_DE_DOCUMENTACION).denyAll();
					}

					// Todo lo demas: autenticado. Sin excepciones implicitas.
					auth.anyRequest().authenticated();
				});

		// Antes que todo lo demas: un request cross-site sobre el endpoint de refresh se corta
		// sin tocar la base ni el contador del rate limit.
		http.addFilterBefore(new OriginCsrfFilter(allowedOrigins), SecurityContextHolderFilter.class);

		RateLimitFilter limite = rateLimitFilter.getIfAvailable();
		if (limite == null) {
			log.warn("La cadena de seguridad se arma SIN limite de intentos. Esperable solo en "
					+ "un slice de test; en la aplicacion completa deja el login expuesto a "
					+ "fuerza bruta.");
		} else {
			http.addFilterBefore(limite, SecurityContextHolderFilter.class);
		}

		JwtAuthenticationFilter autenticacion = jwtAuthenticationFilter.getIfAvailable();
		if (autenticacion == null) {
			log.warn("La cadena de seguridad se arma SIN autenticacion por token. Esperable solo "
					+ "en un slice de test, donde el principal se inyecta por request; en la "
					+ "aplicacion completa es un error de configuracion.");
		} else {
			http.addFilterBefore(autenticacion, AuthorizationFilter.class);
		}

		TenantContextFilter filtroDeContexto = tenantContextFilter.getIfAvailable();
		if (filtroDeContexto == null) {
			log.warn("La cadena de seguridad se arma SIN resolucion de contexto multi-tenant. "
					+ "Esperable solo en un slice de test; en la aplicacion completa es un error "
					+ "de configuracion.");
		} else {
			http.addFilterAfter(filtroDeContexto, AuthorizationFilter.class);
		}

		return http.build();
	}

	/**
	 * Un origen en la forma exacta en que lo emite un navegador: esquema y host en minusculas,
	 * y el puerto <b>solo si no es el del esquema</b>.
	 *
	 * <p>Es la forma canonica de RFC 6454, y la unica contra la que tiene sentido comparar un
	 * header {@code Origin}. Devuelve {@code null} si el valor configurado no es un origen
	 * —tiene path, query, userinfo, o le falta esquema o host—: preferimos que un origen mal
	 * escrito desaparezca de la lista y falle cerrado, antes que adivinar que quiso decir.
	 *
	 * @return la forma canonica, o {@code null} si el valor no es un origen valido
	 */
	static String formaCanonicaDeOrigen(String origen) {
		if (origen == null || origen.isBlank()) {
			return null;
		}
		URI uri;
		try {
			uri = URI.create(origen.trim());
		} catch (IllegalArgumentException malFormado) {
			return null;
		}
		String esquema = uri.getScheme();
		String host = uri.getHost();
		if (esquema == null || host == null) {
			return null;
		}
		// Un Origin es esquema+host+puerto y nada mas. `https://akine.app@atacante.test` es
		// justamente el caso que parece decir una cosa y apunta a otra.
		if ((uri.getPath() != null && !uri.getPath().isEmpty())
				|| uri.getQuery() != null
				|| uri.getFragment() != null
				|| uri.getUserInfo() != null) {
			return null;
		}
		esquema = esquema.toLowerCase(java.util.Locale.ROOT);
		String base = esquema + "://" + host.toLowerCase(java.util.Locale.ROOT);
		int puerto = uri.getPort();
		if (puerto < 0 || puerto == puertoPorDefectoDe(esquema)) {
			return base;
		}
		return base + ":" + puerto;
	}

	private static int puertoPorDefectoDe(String esquema) {
		return switch (esquema) {
			case "https" -> 443;
			case "http" -> 80;
			default -> -1;
		};
	}

	/**
	 * Origenes permitidos por configuracion, nunca comodin.
	 *
	 * <p>{@code allowCredentials(true)} es necesario para el refresh token en cookie
	 * {@code httpOnly} y es incompatible con {@code allowedOrigins("*")}: por eso los origenes
	 * se declaran explicitamente por entorno.
	 */
	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		// La lista se canonicaliza antes de entregarsela a Spring: su comparacion tambien es
		// textual, y es la que decide en ultima instancia. Sin esto, configurar
		// "https://AKINE.APP" o "https://akine.app:443" —el mismo origen que el navegador
		// manda como "https://akine.app"— produce un 403 que no se explica leyendo la config.
		configuration.setAllowedOrigins(allowedOrigins.stream()
				.map(SecurityConfig::formaCanonicaDeOrigen)
				.filter(java.util.Objects::nonNull)
				.distinct()
				.toList());
		configuration.setAllowedMethods(List.of(
				HttpMethod.GET.name(),
				HttpMethod.POST.name(),
				HttpMethod.PUT.name(),
				HttpMethod.PATCH.name(),
				HttpMethod.DELETE.name(),
				HttpMethod.OPTIONS.name()));
		// Idempotency-Key va en esta lista porque el alta de organizaciones y el registro de
		// cuenta lo exigen como header obligatorio. Sin declararlo, el preflight lo rechaza y
		// esos dos endpoints quedan inalcanzables desde el navegador en cuanto el frontend se
		// sirve desde otro origen. En desarrollo no se nota: el proxy de Angular vuelve todo
		// mismo-origen y no hay preflight que falle.
		configuration.setAllowedHeaders(List.of(
				"Authorization", "Content-Type", "Accept", "Idempotency-Key"));

		// Un header de respuesta que no se expone es ilegible desde JavaScript entre origenes:
		// el navegador lo recibe y el codigo ve null. Retry-After es el unico que la interfaz
		// necesita leer —para decir "esperá N segundos" en vez de un "probá mas tarde" a
		// ciegas— y por eso es el unico que se expone.
		configuration.setExposedHeaders(List.of("Retry-After"));
		configuration.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	/**
	 * Validacion de {@code Origin} sobre los dos endpoints que se autentican con una cookie
	 * (ADR-0017).
	 *
	 * <h2>Que ataque cierra</h2>
	 *
	 * <p>La cookie {@code akine_rt} la manda el navegador sola, sin que la pagina haga nada. Un
	 * sitio hostil que consiga que el navegador de la victima haga {@code POST} a
	 * {@code /api/v1/auth/refresh} provoca una rotacion: el refresh legitimo queda consumido y
	 * la victima pierde la sesion en el siguiente intento. Sobre {@code /logout} el efecto es
	 * mas directo todavia. El resto de la API no corre riesgo porque se autentica con
	 * {@code Authorization: Bearer}, un header que un sitio ajeno no puede hacer que el
	 * navegador envie.
	 *
	 * <h2>Por que hace falta si la cookie ya es SameSite=Strict</h2>
	 *
	 * <p>Porque {@code SameSite} lo aplica el navegador y no todos lo aplican igual: hay
	 * clientes viejos que lo ignoran, y hay navegadores que relajan la regla en navegaciones de
	 * primer nivel. La validacion de {@code Origin} la aplica el servidor, que es el unico lado
	 * que controlamos. Son dos capas para el mismo vector, y es deliberado.
	 *
	 * <h2>Ausencia de Origin</h2>
	 *
	 * <p>Un request sin {@code Origin} ni {@code Referer} <b>se rechaza</b>. Es la decision
	 * incomoda: {@code curl} y las herramientas de linea de comandos no mandan ninguno de los
	 * dos, asi que un script que renueve sesion tiene que declarar su origen. Aceptar la
	 * ausencia dejaria la puerta abierta a cualquier cliente que simplemente no mande el header,
	 * que es lo primero que prueba quien ataca. Fallar cerrado.
	 *
	 * <p>{@code Referer} es el respaldo y no la fuente primaria: lo mandan menos clientes y
	 * puede venir recortado por politica de privacidad. Se compara solo su parte de origen.
	 *
	 * <p>Vive como clase anidada de la configuracion —y no como pieza suelta de
	 * {@code platform.infrastructure.security}— porque su unica razon de existir es esta
	 * cadena y su unica configuracion es la lista de origenes que esta clase ya recibe.
	 */
	static final class OriginCsrfFilter extends org.springframework.web.filter.OncePerRequestFilter {

		private static final URI TYPE_CSRF_RECHAZADO =
				ProblemType.CSRF_REJECTED.uri();

		private final List<String> origenesPermitidos;

		OriginCsrfFilter(List<String> origenesPermitidos) {
			this.origenesPermitidos = List.copyOf(origenesPermitidos);
		}

		/**
		 * Se filtra solo lo que puede ser abusado: una de las dos rutas, y con la cookie puesta.
		 *
		 * <p><b>Sin cookie no hay nada que proteger.</b> Un {@code POST /auth/refresh} sin
		 * {@code akine_rt} no puede rotar ninguna sesion —el servicio responde 401— y un
		 * {@code /auth/logout} sin cookie es un no-op que responde 204. Exigirles {@code Origin}
		 * seria rechazar por CSRF requests que no pueden producir ningun efecto, y romperia a
		 * cualquier cliente que no sea un navegador —un test de humo, un health check— sin ganar
		 * nada. El vector existe unicamente cuando el navegador adjunta la credencial solo.
		 */
		@Override
		protected boolean shouldNotFilter(jakarta.servlet.http.HttpServletRequest request) {
			// El preflight no lleva cookies y lo resuelve CORS; filtrarlo romperia toda llamada
			// del navegador desde el frontend.
			if (HttpMethod.OPTIONS.matches(request.getMethod())) {
				return true;
			}
			if (!RUTAS_CON_COOKIE_DE_REFRESH.contains(rutaDe(request))) {
				return true;
			}
			return !traeCookieDeRefresh(request);
		}

		/** Nombre de la cookie que custodia el refresh. Lo fija {@code identity.api}. */
		private static boolean traeCookieDeRefresh(
				jakarta.servlet.http.HttpServletRequest request) {

			jakarta.servlet.http.Cookie[] cookies = request.getCookies();
			if (cookies == null) {
				return false;
			}
			for (jakarta.servlet.http.Cookie cookie : cookies) {
				if ("akine_rt".equals(cookie.getName())) {
					return true;
				}
			}
			return false;
		}

		@Override
		protected void doFilterInternal(
				jakarta.servlet.http.HttpServletRequest request,
				jakarta.servlet.http.HttpServletResponse response,
				jakarta.servlet.FilterChain filterChain)
				throws jakarta.servlet.ServletException, java.io.IOException {

			String origen = origenDeclarado(request);
			if (origen != null && esPermitido(origen)) {
				filterChain.doFilter(request, response);
				return;
			}

			// El origen recibido se loguea porque es un dato de la conexion, no una credencial,
			// y es lo unico que sirve para diagnosticar un despliegue con la lista mal cargada.
			// El cuerpo del request no se toca: ahi viaja la cookie de sesion (RN-M02-003).
			log.warn("Request rechazado por Origin no permitido: ruta={} origin={}",
					rutaDe(request), origen == null ? "(ausente)" : origen);

			escribirProblema(request, response);
		}

		/**
		 * Compara el origen recibido contra la lista permitida, normalizando los dos lados.
		 *
		 * <p><b>Por que no alcanza {@code List.contains}.</b> Un origen se compara por esquema,
		 * host y puerto <i>normalizados</i> (RFC 6454): el host es case-insensitive y el puerto
		 * por defecto equivale a omitirlo. Con igualdad textual, {@code https://AKINE.APP} y
		 * {@code https://akine.app:443} —los dos, el mismo origen que el configurado— se
		 * rechazaban. No era un agujero, porque el error caia del lado seguro, pero un
		 * despliegue que cargue la lista con el puerto explicito producia 403 inexplicables en
		 * refresh y logout.
		 *
		 * <p><b>Por que no {@code equalsIgnoreCase} ni {@code startsWith}.</b> Los dos serian
		 * peores que el problema: el primero deja pasar diferencias de esquema, y el segundo
		 * convierte {@code https://akine.app.atacante.test} en un origen valido. Se normaliza
		 * a esquema+host en minusculas con el puerto siempre explicito, y recien ahi se compara
		 * por igualdad.
		 */
		private boolean esPermitido(String origen) {
			String canonico = formaCanonicaDeOrigen(origen);
			if (canonico == null) {
				return false;
			}
			for (String permitido : origenesPermitidos) {
				if (canonico.equals(formaCanonicaDeOrigen(permitido))) {
					return true;
				}
			}
			return false;
		}

		/** Origen del request: {@code Origin}, y {@code Referer} recortado como respaldo. */
		private static String origenDeclarado(jakarta.servlet.http.HttpServletRequest request) {
			String origin = request.getHeader("Origin");
			if (origin != null && !origin.isBlank()) {
				return origin;
			}
			String referer = request.getHeader("Referer");
			if (referer == null || referer.isBlank()) {
				return null;
			}
			try {
				URI uri = URI.create(referer);
				if (uri.getScheme() == null || uri.getHost() == null) {
					return null;
				}
				return uri.getPort() < 0
						? uri.getScheme() + "://" + uri.getHost()
						: uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
			} catch (IllegalArgumentException malFormado) {
				return null;
			}
		}

		/**
		 * Escribe el Problem Details a mano.
		 *
		 * <p>Un filtro corre fuera del {@code DispatcherServlet}: lo que lance ahi no pasa por
		 * ningun {@code @RestControllerAdvice}, y sin esto el contenedor devolveria su pagina de
		 * error HTML, que el frontend no puede ramificar por {@code type}. El helper que hace
		 * esto mismo para el resto de la cadena vive en {@code platform.infrastructure.security}
		 * y es package-private; exponerlo solo para esta clase seria ampliar la superficie de
		 * una pieza de seguridad. <b>Deuda anotada:</b> unificar las dos cuando alguna de las
		 * dos cambie de forma.
		 */
		private static void escribirProblema(
				jakarta.servlet.http.HttpServletRequest request,
				jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {

			if (response.isCommitted()) {
				return;
			}
			response.setStatus(org.springframework.http.HttpStatus.FORBIDDEN.value());
			response.setContentType(
					org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
			response.getWriter().write("""
					{"type":"%s",\
					"title":"Origen no permitido",\
					"status":403,\
					"detail":"El origen del pedido no esta autorizado para operar sobre la sesion.",\
					"instance":"%s"}"""
					.formatted(TYPE_CSRF_RECHAZADO, rutaDe(request)));
			response.getWriter().flush();
		}

		/**
		 * Ruta decodificada y sin el context path, para no atar la lista de rutas a donde se
		 * despliegue.
		 *
		 * <p><b>Ya no se calcula aca.</b> Esta era la segunda de las copias que el javadoc de
		 * {@link #escribirProblema} anotaba como deuda, y la deuda se cobro: mientras leia
		 * {@code getRequestURI()} sin decodificar, {@code POST /api/v1/auth/%72efresh} con la
		 * cookie puesta y sin {@code Origin} pasaba este filtro —medido: 401 del servicio contra
		 * el 403 {@code csrf-rejected} de la ruta normal—. Ahora las tres copias son una:
		 * {@link RequestPaths}.
		 */
		private static String rutaDe(jakarta.servlet.http.HttpServletRequest request) {
			return RequestPaths.de(request);
		}
	}
}
