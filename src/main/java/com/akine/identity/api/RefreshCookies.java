package com.akine.identity.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.time.Instant;

/**
 * Custodia del refresh token en el borde HTTP (ADR-0017).
 *
 * <h2>Los cuatro atributos, y que se rompe si falta cada uno</h2>
 *
 * <ul>
 *   <li>{@code httpOnly} — el JavaScript de la pagina no puede leerla. Sin esto, un XSS se
 *       lleva una sesion de doce horas y la proteccion entera se cae.</li>
 *   <li>{@code Secure} — no viaja por HTTP plano. Sin esto, cualquier red hostil la lee.</li>
 *   <li>{@code SameSite=Strict} — el navegador no la manda en requests originados por otro
 *       sitio. Es la mitad del CSRF de este endpoint; la otra mitad es la validacion de
 *       {@code Origin} en la cadena de filtros.</li>
 *   <li>{@code Path=/api/v1/auth} — <b>no</b> viaja a ningun endpoint de negocio. Reduce la
 *       superficie: una credencial de doce horas no tiene por que acompanar cada consulta de
 *       turnos.</li>
 * </ul>
 *
 * <p>Sin prefijo {@code __Host-} a proposito: ese prefijo exige {@code Path=/}, que es
 * justamente lo contrario del acotamiento de arriba. El ADR eligio el acotamiento.
 *
 * <p><b>El valor plano no se loguea nunca</b>, ni siquiera truncado, ni a nivel DEBUG
 * (RN-M02-003). Esta clase no tiene logger a proposito.
 */
final class RefreshCookies {

	/** Nombre de la cookie. Lo conoce el frontend solo para saber que no puede leerla. */
	static final String NOMBRE = "akine_rt";

	/**
	 * Alcance de la cookie.
	 *
	 * <p>Tiene que cubrir {@code /api/v1/auth/refresh} y {@code /api/v1/auth/logout}, que son
	 * los dos unicos endpoints que la presentan. Ampliarlo a {@code /api/v1} la mandaria a
	 * cada request de negocio, y ahi vuelve a ser interesante robarla.
	 */
	static final String PATH = "/api/v1/auth";

	private RefreshCookies() {
		// Utilidad.
	}

	/**
	 * Cookie que instala el refresh recien emitido.
	 *
	 * <p>{@code maxAge} se calcula contra el vencimiento ABSOLUTO de la sesion y no contra el
	 * TTL de configuracion: la rotacion no extiende la sesion (ADR-0017), asi que la cookie que
	 * entrega un refresh rotado tiene que morir cuando muere la familia, no doce horas despues
	 * de cada renovacion. Sin esta cuenta, refrescar cada diez minutos daria una sesion eterna
	 * del lado del navegador.
	 */
	static String instalar(String refreshPlano, Instant expiraEn, Instant ahora) {
		Duration restante = Duration.between(ahora, expiraEn);
		if (restante.isNegative()) {
			restante = Duration.ZERO;
		}
		return base(refreshPlano).maxAge(restante).build().toString();
	}

	/**
	 * Cookie que borra la del navegador.
	 *
	 * <p>Mismo nombre, mismo {@code Path} y valor vacio con {@code maxAge} cero. Los tres tienen
	 * que coincidir: una cookie de borrado con otro {@code Path} crea una segunda cookie en vez
	 * de pisar la primera, y el navegador se queda con la vieja.
	 */
	static String borrar() {
		return base("").maxAge(Duration.ZERO).build().toString();
	}

	/** Lee el refresh presentado, o {@code null} si el request no trae la cookie. */
	static String leer(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (NOMBRE.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

	/** Cabecera bajo la cual se escriben las dos. */
	static String header() {
		return HttpHeaders.SET_COOKIE;
	}

	private static ResponseCookie.ResponseCookieBuilder base(String valor) {
		return ResponseCookie.from(NOMBRE, valor)
				.httpOnly(true)
				.secure(true)
				.sameSite("Strict")
				.path(PATH);
	}
}
