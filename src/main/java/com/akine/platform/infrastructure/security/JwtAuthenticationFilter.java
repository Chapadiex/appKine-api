package com.akine.platform.infrastructure.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.akine.platform.spi.security.AccessTokenClaims;
import com.akine.platform.spi.security.AccessTokenVerifier;
import com.akine.platform.spi.tenant.PlatformRoleDirectory;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * El "quien sos" de la cadena. Lee el bearer, lo verifica y publica el principal.
 *
 * <h2>Que hace y que deliberadamente no hace</h2>
 *
 * <p>Verifica la firma y la vigencia del access token, arma un
 * {@link AuthenticatedJwtPrincipal} y lo deja en el {@code SecurityContext} para que
 * {@code TenantContextFilter} —que corre despues de {@code AuthorizationFilter}— lo encuentre.
 * <b>Consulta la base una sola vez, y solo para el rol de plataforma.</b> Desde AKINE-01.03
 * pregunta {@code platform_role} por el puerto {@link PlatformRoleDirectory} para saber si la
 * cuenta administra la plataforma: ese dato no puede salir del claim {@code rol} sin convertir
 * la ventana de revocacion del permiso mas alto del sistema en el TTL del token (ADR-0020).
 * <b>Lo demas sigue sin consultarse.</b> Ni el estado de la cuenta ni la membership: lo primero seria un
 * {@code SELECT} extra en todo request autenticado para cubrir un evento raro, y se resolvio
 * aceptando la ventana de 10 minutos del TTL (ADR-0017 D-3); lo segundo es trabajo del filtro
 * de contexto, que si lo hace y sin cache.
 *
 * <h2>Tres caminos, tres respuestas</h2>
 * <ol>
 *   <li><b>Sin cabecera {@code Authorization}:</b> el request sigue anonimo. No se responde 401
 *       aca, porque hay rutas publicas —version, health, login— que tienen que funcionar sin
 *       token. Quien decide si esa ruta exige autenticacion es {@code AuthorizationFilter}, y
 *       si la exige, el {@link ProblemAuthenticationEntryPoint} responde el 401.</li>
 *   <li><b>Cabecera presente y token invalido</b> —firma rota, vencido, alcance incoherente,
 *       header de algoritmo distinto—: <b>401 inmediato</b>. Un token roto siempre es un error,
 *       tambien sobre una ruta publica: dejarlo pasar como anonimo le haria creer al cliente
 *       que su sesion sigue viva.</li>
 *   <li><b>Token valido:</b> principal publicado, el request continua.</li>
 * </ol>
 *
 * <h2>401 y 403 no son intercambiables</h2>
 *
 * <p>Este filtro solo produce <b>401</b>: "no se quien sos". El <b>403</b> lo producen el filtro
 * de contexto —autenticado pero sin contexto elegido— y el {@link ProblemAccessDeniedHandler}.
 * Confundirlos rompe el frontend: su interceptor borra el token ante cualquier 401, asi que un
 * 403 devuelto como 401 manda al usuario a un bucle de login del que no sale (regla heredada de
 * AKINE-01.01).
 *
 * <h2>El token no se loguea, ni entero ni en pedazos</h2>
 *
 * <p>Ni en {@code debug}. Un access token en un archivo de log es una credencial en un archivo
 * de log (RN-M02-003), y los logs se comparten para diagnosticar mucho mas alegremente que una
 * base de datos.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

	/** Prefijo del esquema Bearer, con el espacio. Se compara ignorando mayusculas (RFC 7235). */
	private static final String PREFIJO_BEARER = "Bearer ";

	private final AccessTokenVerifier accessTokenVerifier;

	/**
	 * Origen de dato del rol de plataforma (ADR-0020).
	 *
	 * <p>Puerto invertido: lo implementa {@code organization.infrastructure.tenant} leyendo
	 * {@code platform_role}. {@code platform} nunca compila contra {@code organization}.
	 */
	private final PlatformRoleDirectory platformRoleDirectory;

	private final java.time.Clock clock;

	public JwtAuthenticationFilter(
			AccessTokenVerifier accessTokenVerifier,
			PlatformRoleDirectory platformRoleDirectory,
			java.time.Clock clock) {
		this.accessTokenVerifier = accessTokenVerifier;
		this.platformRoleDirectory = platformRoleDirectory;
		this.clock = clock;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		String presentado = bearerDe(request);

		if (presentado == null) {
			// Sin credencial: sigue anonimo y decide la autorizacion de la ruta.
			filterChain.doFilter(request, response);
			return;
		}

		Optional<AccessTokenClaims> claims = accessTokenVerifier.verify(presentado);
		if (claims.isEmpty()) {
			log.debug("Access token rechazado en {}", ProblemResponses.rutaDe(request));
			responderNoAutenticado(request, response);
			return;
		}

		// El rol de plataforma se revalida contra la base en CADA request y sin cache
		// (ADR-0020). No sale del claim `rol`: autorizar por ese claim convertiria la ventana de
		// revocacion del permiso mas alto del sistema en el TTL del token, que es exactamente lo
		// que AccessTokenClaims documenta que no hay que hacer. Es un seek indexado sobre
		// platform_role, la misma decision —y el mismo precio— que la revalidacion de membership
		// del filtro de contexto (T-7).
		boolean platformAdmin = platformRoleDirectory.isPlatformAdmin(
				claims.get().accountId(), clock.instant());

		SecurityContext contexto = SecurityContextHolder.createEmptyContext();
		contexto.setAuthentication(new UsernamePasswordAuthenticationToken(
				new AuthenticatedJwtPrincipal(claims.get(), platformAdmin),
				// Sin credenciales en el contexto: el token ya se verifico y guardarlo lo
				// dejaria disponible para cualquier cosa que serialice el principal.
				null,
				// Sin authorities: los permisos se resuelven contra la base, no contra el token
				// (RN-M01-003). La matriz de 01.03 decide que puede hacer cada rol.
				List.of()));
		SecurityContextHolder.setContext(contexto);

		try {
			filterChain.doFilter(request, response);
		} finally {
			// Los hilos del contenedor se reutilizan: un principal que sobrevive al request se
			// lo lleva puesto el proximo usuario que caiga en ese hilo.
			SecurityContextHolder.clearContext();
		}
	}

	/** Devuelve el token del header, o {@code null} si no hay uno con forma de Bearer. */
	private static String bearerDe(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || header.length() <= PREFIJO_BEARER.length()) {
			return null;
		}
		if (!header.regionMatches(true, 0, PREFIJO_BEARER, 0, PREFIJO_BEARER.length())) {
			return null;
		}
		String valor = header.substring(PREFIJO_BEARER.length()).trim();
		return valor.isEmpty() ? null : valor;
	}

	private static void responderNoAutenticado(
			HttpServletRequest request, HttpServletResponse response) throws IOException {
		ProblemResponses.escribir(request, response, HttpStatus.UNAUTHORIZED,
				ProblemResponses.UNAUTHORIZED,
				"No autenticado",
				"La credencial presentada no es valida o expiro. Inicie sesion nuevamente.");
	}
}
