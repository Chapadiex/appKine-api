package com.akine.platform.infrastructure.tenant;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.MembershipDirectory;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.tenant.TenantMembership;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolucion del contexto multi-tenant del request. Es la pieza de seguridad de AKINE-01.01.
 *
 * <h2>Que garantiza</h2>
 * <ol>
 *   <li>Ninguna consulta cruza tenants: el {@code organizationId} que usan los repositorios
 *       sale del contexto que publica este filtro, no de un parametro del cliente.</li>
 *   <li><b>Ventana de revocacion cero (T-7):</b> la membership se revalida contra la base en
 *       CADA request, sin cache. Un token criptograficamente valido cuya membership fue
 *       revocada deja de funcionar en el request siguiente.</li>
 * </ol>
 *
 * <h2>Los claims son un hint, nunca autoridad (RN-M01-003)</h2>
 * El token trae {@code organizationId} y {@code consultorioId}, pero firmarlos no los mantiene
 * verdaderos: solo dicen QUE contexto se esta pidiendo. Quien decide si ese contexto es
 * accesible es {@link MembershipDirectory}, leyendo la base ahora.
 *
 * <h2>Codigos de estado: por que 403 y no 401 cuando falta contexto</h2>
 * <p><b>Esto no es una preferencia estetica: cambiarlo a 401 reintroduce un bug conocido
 * (B-2).</b> El interceptor del frontend hace
 * {@code if (error.status === 401) tokenStore.clear()}. Un usuario recien autenticado que
 * todavia no eligio contexto recibiria 401, el interceptor le borraria el token y lo mandaria
 * de vuelta al login, donde volveria a autenticarse, volveria a no tener contexto y volveria a
 * recibir 401: bucle cerrado, la aplicacion es inusable.
 * <p>Ademas es incorrecto semanticamente. 401 significa "no se quien sos". Aca sabemos
 * perfectamente quien es: esta autenticado. Lo que falta es que elija DONDE trabaja, y eso es
 * una condicion de autorizacion, no de autenticacion. Por eso <b>403</b> con
 * {@code type = https://akine.app/problems/missing-tenant-context}: el frontend ramifica por
 * {@code type} y redirige a la seleccion de contexto sin tocar el token.
 * <p>Si alguna vez alguien "corrige" esto de vuelta a 401 porque le parece mas logico, este
 * parrafo existe para que lo lea antes.
 *
 * <h2>Cross-tenant es 404, nunca 403</h2>
 * Pedir un consultorio de otra organizacion, o uno inexistente, o tener la membership vencida,
 * dan todos <b>404 {@code not-found}</b>. Un 403 confirmaria que el recurso existe pero es de
 * otro: eso es filtrar la existencia de datos ajenos, que sobre datos clinicos es una fuga en
 * si misma. Para quien no tiene acceso, el recurso simplemente no existe.
 *
 * <h2>Suscripcion suspendida</h2>
 * {@code SUSPENDIDA} permite lecturas y administracion, y rechaza mutaciones de negocio con
 * <b>409 {@code subscription-suspended}</b> (RN-M01-002: suspender bloquea, jamas destruye).
 * {@code CANCELADA} y {@code BAJA} no permiten usar el contexto: 404.
 *
 * <h2>Orden en la cadena de filtros</h2>
 * Este filtro corre DESPUES de la autenticacion. En 01.01 la cadena todavia es permisiva y no
 * existe el filtro JWT; cuando 01.02 lo agregue, el orden esperado es:
 * <pre>
 *   SecurityContextHolderFilter -> [01.02] JwtAuthenticationFilter -> AuthorizationFilter
 *       -> TenantContextFilter -> DispatcherServlet
 * </pre>
 * Si este filtro corriera antes de la autenticacion nunca encontraria principal y dejaria pasar
 * todo sin contexto.
 */
public class TenantContextFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

	private static final URI MISSING_TENANT_CONTEXT =
			URI.create("https://akine.app/problems/missing-tenant-context");
	private static final URI NOT_FOUND = URI.create("https://akine.app/problems/not-found");
	private static final URI SUBSCRIPTION_SUSPENDED =
			URI.create("https://akine.app/problems/subscription-suspended");

	/**
	 * Rutas que operan con identidad autenticada pero SIN contexto de tenant.
	 *
	 * <p>{@code /api/v1/me/contexts} esta aca por necesidad logica: es el endpoint con el que
	 * el usuario averigua que contextos tiene. Exigirle contexto para poder elegir contexto
	 * seria el mismo bucle que evita el 403 de arriba.
	 */
	private static final List<String> RUTAS_EXACTAS_EXCEPTUADAS = List.of(
			"/api/v1/version",
			"/api/v1/me/contexts");

	/**
	 * Prefijos exceptuados. Actuator y la documentacion del contrato son infraestructura, no
	 * negocio. Login y refresh todavia no existen: los publica 01.02 y quedan declarados aca
	 * para que el dia que aparezcan no haya que acordarse de exceptuarlos —un login que exige
	 * contexto no puede funcionar nunca.
	 */
	private static final List<String> PREFIJOS_EXCEPTUADOS = List.of(
			"/actuator",
			"/v3/api-docs",
			"/swagger-ui",
			"/api/v1/auth/login",
			"/api/v1/auth/refresh");

	/** Metodos sin efecto de escritura: siempre permitidos con la suscripcion suspendida. */
	private static final List<String> METODOS_DE_LECTURA = List.of("GET", "HEAD", "OPTIONS", "TRACE");

	/**
	 * Este filtro es de seguridad y no puede depender de que Jackson este configurado de una
	 * forma u otra en el contexto: se serializa un mapa plano con la forma exacta de RFC 7807.
	 * Un {@code ObjectMapper} inyectado con un mixin distinto cambiaria el cuerpo del error, y
	 * el frontend ramifica por {@code type}.
	 */
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final MembershipDirectory membershipDirectory;
	private final TenantContextHolder tenantContextHolder;
	private final Clock clock;

	public TenantContextFilter(
			MembershipDirectory membershipDirectory,
			TenantContextHolder tenantContextHolder,
			Clock clock) {
		this.membershipDirectory = membershipDirectory;
		this.tenantContextHolder = tenantContextHolder;
		this.clock = clock;
	}

	/**
	 * Las rutas exceptuadas no llegan siquiera a consultar la base.
	 *
	 * <p>No es una optimizacion: es la garantia de que el health check, la documentacion y el
	 * endpoint de seleccion de contexto siguen respondiendo aunque la base este caida o el
	 * usuario no tenga ninguna membership.
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return esRutaExceptuada(rutaDe(request));
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		AuthenticatedPrincipal principal = principalAutenticado();

		// Sin principal no hay nada que resolver. Responder 401 aca seria usurpar el trabajo
		// del punto de entrada de autenticacion, que llega en 01.02: hoy la cadena es
		// permisiva y quien no esta autenticado simplemente no obtiene contexto. El codigo de
		// negocio que necesite tenant fallara ruidosamente en TenantContextHolder.require().
		if (principal == null) {
			filterChain.doFilter(request, response);
			return;
		}

		// Un PLATFORM_ADMIN opera por encima de los tenants y no tiene por que ser miembro de
		// ninguna organizacion: exigirle contexto haria imposible el alta de organizaciones.
		// No se le publica contexto —no lo tiene— y no se consulta la base. Que puede hacer
		// exactamente lo decide la evaluacion de permisos de 01.03.
		if (principal.platformAdmin()) {
			filterChain.doFilter(request, response);
			return;
		}

		Long organizationId = principal.organizationId();
		Long consultorioId = principal.consultorioId();
		if (organizationId == null || consultorioId == null) {
			responderProblema(request, response, HttpStatus.FORBIDDEN, MISSING_TENANT_CONTEXT,
					"Contexto de trabajo no seleccionado",
					"Esta autenticado pero no selecciono un contexto de trabajo. "
							+ "Elija organizacion y consultorio para continuar.");
			return;
		}

		Instant ahora = clock.instant();
		Optional<TenantMembership> resuelta = membershipDirectory.resolveMembership(
				principal.accountId(), organizationId, consultorioId, ahora);

		if (resuelta.isEmpty()) {
			// Membership inexistente, dada de baja o vencida; consultorio inexistente o de otra
			// organizacion. Todos los casos responden igual: para quien no tiene acceso, el
			// recurso no existe.
			log.debug("Contexto rechazado por no accesible: account={} org={} consultorio={}",
					principal.accountId(), organizationId, consultorioId);
			responderNoEncontrado(request, response);
			return;
		}

		TenantMembership membership = resuelta.get();

		if (!membership.operationalStatus().allowsContextUsage()) {
			// CANCELADA o BAJA: el contexto de esa organizacion no puede usarse. Tampoco se
			// distingue de "no existe", por el mismo motivo que arriba.
			log.debug("Contexto rechazado por estado operativo {}: org={}",
					membership.operationalStatus(), organizationId);
			responderNoEncontrado(request, response);
			return;
		}

		if (!membership.operationalStatus().allowsBusinessMutations()
				&& esMutacionDeNegocio(request)) {
			responderProblema(request, response, HttpStatus.CONFLICT, SUBSCRIPTION_SUSPENDED,
					"Suscripcion suspendida",
					"La suscripcion de la organizacion esta suspendida: se permiten lecturas y "
							+ "administracion, no mutaciones de negocio.");
			return;
		}

		RequestTenantContext context = new RequestTenantContext(
				principal.accountId(),
				organizationId,
				consultorioId,
				membership.roleCode(),
				membership.operationalStatus());

		tenantContextHolder.set(context);
		try {
			filterChain.doFilter(request, response);
		} finally {
			// Innegociable: los hilos del contenedor se reutilizan. Un contexto que sobrevive
			// al request se lo lleva puesto el proximo usuario que caiga en ese hilo.
			tenantContextHolder.clear();
		}
	}

	/**
	 * Extrae el principal de AKINE del {@code SecurityContext}, si lo hay.
	 *
	 * <p>Devuelve {@code null} para el token anonimo de Spring Security, cuyo principal es un
	 * {@code String}. Se comprueba por tipo y no por "esta autenticado": lo unico que sirve es
	 * un principal que sepa responder {@code accountId()}.
	 */
	private AuthenticatedPrincipal principalAutenticado() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			return null;
		}
		Object principal = authentication.getPrincipal();
		return principal instanceof AuthenticatedPrincipal akine ? akine : null;
	}

	/**
	 * Ruta del request sin el context path, para que las excepciones no dependan de donde se
	 * despliegue la aplicacion.
	 */
	private String rutaDe(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String contextPath = request.getContextPath();
		if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
			uri = uri.substring(contextPath.length());
		}
		return uri.isEmpty() ? "/" : uri;
	}

	private boolean esRutaExceptuada(String ruta) {
		if (RUTAS_EXACTAS_EXCEPTUADAS.contains(ruta)) {
			return true;
		}
		// Prefijo con frontera: "/actuator" y "/actuator/health" estan exceptuados,
		// "/actuatorfalso" no. Sin la frontera, un endpoint de negocio que empezara igual
		// quedaria sin resolucion de tenant, que es exactamente el agujero a evitar.
		return PREFIJOS_EXCEPTUADOS.stream()
				.anyMatch(prefijo -> ruta.equals(prefijo) || ruta.startsWith(prefijo + "/"));
	}

	/**
	 * Distingue mutacion de negocio de lo que sigue permitido con la suscripcion suspendida.
	 *
	 * <p>El filtro no puede saber si un endpoint es "de negocio" o "de administracion" mirando
	 * la firma, asi que la regla es gruesa y explicita: metodo de lectura -> permitido; y las
	 * rutas de administracion de la suscripcion quedan escritas aca, porque son justamente las
	 * que hacen falta para SALIR de la suspension. Sin esta excepcion, suspender una
	 * organizacion la dejaria imposible de reactivar: la suspension se volveria terminal de
	 * hecho, que es lo contrario de lo que dice RN-M01-002.
	 *
	 * <p>Cuando 01.03 traiga la evaluacion fina de permisos, esta heuristica se reemplaza por
	 * una marca explicita del endpoint.
	 */
	private boolean esMutacionDeNegocio(HttpServletRequest request) {
		if (METODOS_DE_LECTURA.contains(request.getMethod().toUpperCase(java.util.Locale.ROOT))) {
			return false;
		}
		String ruta = rutaDe(request);
		boolean administracionDeSuscripcion =
				ruta.startsWith("/api/v1/organizations/") && ruta.contains("/subscription");
		return !administracionDeSuscripcion;
	}

	private void responderNoEncontrado(HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		responderProblema(request, response, HttpStatus.NOT_FOUND, NOT_FOUND,
				"Recurso no encontrado",
				"El recurso solicitado no existe o no esta disponible.");
	}

	/**
	 * Escribe un ProblemDetail RFC 7807 directamente en la respuesta.
	 *
	 * <p>No lo puede hacer {@code GlobalExceptionHandler}: un filtro corre fuera del
	 * {@code DispatcherServlet} y las excepciones que lance ahi no pasan por el
	 * {@code @RestControllerAdvice}. Por eso se respeta su convencion a mano: mismos
	 * {@code type} bajo {@code https://akine.app/problems/}, mismo content type, y nunca
	 * detalles internos en el cuerpo.
	 */
	private void responderProblema(
			HttpServletRequest request,
			HttpServletResponse response,
			HttpStatus status,
			URI type,
			String title,
			String detail) throws IOException {

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		problem.setType(type);
		problem.setInstance(URI.create(rutaDe(request)));

		Map<String, Object> cuerpo = new LinkedHashMap<>();
		cuerpo.put("type", problem.getType().toString());
		cuerpo.put("title", problem.getTitle());
		cuerpo.put("status", problem.getStatus());
		cuerpo.put("detail", problem.getDetail());
		cuerpo.put("instance", problem.getInstance().toString());

		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(JSON.writeValueAsString(cuerpo));
		response.getWriter().flush();
	}
}
