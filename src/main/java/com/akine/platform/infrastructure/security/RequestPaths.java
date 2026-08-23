package com.akine.platform.infrastructure.security;

import org.springframework.web.util.UrlPathHelper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * La ruta de un request, <b>en la misma forma en que la ve el enrutamiento</b>.
 *
 * <h2>El agujero que esta clase cierra</h2>
 *
 * <p>{@code HttpServletRequest.getRequestURI()} devuelve la URI <b>tal cual llego</b>: sin
 * decodificar y con el contenido de los parametros de path adentro. Spring MVC y Spring Security
 * no deciden asi: resuelven el handler y evaluan los {@code requestMatchers} sobre el path
 * <b>decodificado</b>. Un filtro que compare la cadena cruda contra una lista de rutas esta
 * comparando algo distinto de lo que despues decide a donde va el request, y esa diferencia es
 * explotable con un solo caracter:
 *
 * <pre>
 *   POST /api/v1/auth/%6cogin
 * </pre>
 *
 * <p>{@code %6c} es la letra {@code l}. El enrutamiento lo decodifica, encuentra
 * {@code /api/v1/auth/login} y lo trata como la ruta publica que es. El filtro que miraba la
 * cadena cruda ve {@code /api/v1/auth/%6cogin}, no la encuentra en su lista y <b>se saltea</b>:
 * el login queda sin limite de intentos —fuerza bruta de contrasenas sin costo— y los dos
 * endpoints que se autentican por cookie quedan sin validacion de {@code Origin}. Medido contra
 * el backend real antes de este arreglo: cuarenta intentos sobre la ruta codificada, cuarenta
 * {@code 401 invalid-credentials}, ningun {@code 429}; los mismos cuarenta sobre la ruta normal
 * daban {@code 429} desde el intento 31.
 *
 * <p>El {@code StrictHttpFirewall} no lo frena y no tiene por que: {@code %6c} es un alfanumerico
 * codificado, no un caracter peligroso. Lo que el firewall bloquea —barras codificadas,
 * {@code ..}, caracteres de control— es otra cosa.
 *
 * <h2>Por que existe como pieza compartida</h2>
 *
 * <p>Porque el mismo calculo lo necesitan tres lugares —{@link ProblemResponses},
 * {@link RateLimitFilter} y el filtro de {@code Origin} de la cadena— y tres copias del mismo
 * calculo divergen en la primera correccion que alguien haga en una sola de ellas. Esa deuda ya
 * estaba anotada en el filtro de {@code Origin}; el bug de arriba es la factura.
 *
 * <p><b>Falla del lado seguro.</b> {@link UrlPathHelper#getPathWithinApplication} decodifica y
 * quita el contenido de los parametros de path, igual que el enrutamiento. Si alguna vez las dos
 * normalizaciones difirieran, esta produce una ruta <i>mas</i> normalizada, o sea que un filtro
 * de proteccion se aplicaria de mas y nunca de menos.
 */
public final class RequestPaths {

	/**
	 * Instancia compartida e inmutable: {@code urlDecode = true},
	 * {@code removeSemicolonContent = true}. Son exactamente los defaults del enrutamiento.
	 */
	private static final UrlPathHelper HELPER = UrlPathHelper.defaultInstance;

	private RequestPaths() {
		// Utilidad.
	}

	/**
	 * Ruta decodificada y sin el context path, para no atar ninguna lista de rutas a donde se
	 * despliegue la aplicacion.
	 *
	 * @return la ruta, nunca vacia: {@code "/"} para la raiz
	 */
	public static String de(HttpServletRequest request) {
		String ruta = HELPER.getPathWithinApplication(request);
		return ruta == null || ruta.isEmpty() ? "/" : ruta;
	}
}
