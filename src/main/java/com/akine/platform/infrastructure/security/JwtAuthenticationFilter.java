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
 * <b>No consulta la base.</b> Ni el estado de la cuenta ni la membership: lo primero seria un
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

	public JwtAuthenticationFilter(AccessTokenVerifier accessTokenVerifier) {
		this.accessTokenVerifier = accessTokenVerifier;
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

		SecurityContext contexto = SecurityContextHolder.createEmptyContext();
		contexto.setAuthentication(new UsernamePasswordAuthenticationToken(
				new AuthenticatedJwtPrincipal(claims.get()),
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
