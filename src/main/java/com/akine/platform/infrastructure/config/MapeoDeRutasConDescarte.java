package com.akine.platform.infrastructure.config;

import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;

/**
 * El {@link RequestMappingHandlerMapping} de Spring, con un <b>descarte previo</b> barato de las
 * rutas que no pueden coincidir con el pedido (docs/carga.md §13).
 *
 * <h2>Por que</h2>
 *
 * <p>Casi todas las rutas de la API llevan variables ({@code /consultorios/{consultorioId}/...}),
 * asi que Spring no las encuentra por ruta directa y, en cada pedido, evalua las condiciones
 * completas de <b>cada una</b> de las ~330 operaciones: metodo, parametros, cabeceras,
 * {@code Content-Type}, {@code Accept} y recien al final el patron. Medido con JFR a 500 usuarios,
 * eso era ~6,5 % de la CPU del backend.
 *
 * <h2>Que hace, y por que no cambia que handler atiende un pedido</h2>
 *
 * <p>Antes de la evaluacion completa, compara la <b>forma</b> del camino del pedido con la de cada
 * patron <b>simple</b> —solo segmentos literales y variables {@code {nombre}} de un segmento—:
 * misma cantidad de elementos y los mismos literales en las mismas posiciones. Para un patron
 * simple esa es una condicion <b>necesaria</b> de {@link PathPattern#matches}: un literal se compara
 * con el valor decodificado del segmento ({@code valueToMatch}, el mismo que usa Spring) y cada
 * variable ocupa exactamente un segmento. Si no se cumple, el patron no puede coincidir y la
 * evaluacion completa devolveria {@code null}: se devuelve {@code null} sin hacerla. Si se cumple,
 * decide la evaluacion completa de Spring, como siempre.
 *
 * <p>Asi, el conjunto de coincidencias es exactamente el mismo y el handler elegido tambien. Lo que
 * no es simple (comodines, expresiones regulares, {@code **}, la raiz), lo que no tiene camino
 * analizado o un analizador que no distingue mayusculas va por la evaluacion completa sin
 * descarte. Los 404, 405, 406 y 415 tampoco cambian: cuando nada coincide, Spring los decide
 * recorriendo todas las rutas por su cuenta ({@code handleNoMatch}), sin pasar por aca.
 */
public class MapeoDeRutasConDescarte extends RequestMappingHandlerMapping {

	/** Segmento que es solo una variable de un segmento, sin expresion regular. */
	private static final Pattern VARIABLE = Pattern.compile("\\{[A-Za-z0-9_]+\\}");

	/** Caracteres que hacen que un segmento no sea un literal puro. */
	private static final Pattern NO_LITERAL = Pattern.compile("[{}*?;%]");

	/** El camino del pedido que se esta resolviendo en este hilo. */
	private static final ThreadLocal<List<PathContainer.Element>> CAMINO = new ThreadLocal<>();

	/**
	 * Formas de los patrones de cada ruta registrada, por <b>identidad</b> de la ruta: se arma una
	 * vez al terminar el registro y despues solo se lee. {@code RequestMappingInfo.hashCode} no
	 * esta cacheado y recorre todas las condiciones: buscarlo en un mapa comun en cada evaluacion
	 * costaba mas que lo que se ahorraba. Una ruta registrada despues (que hoy no hay) no esta en
	 * el mapa y va por la evaluacion completa.
	 */
	private volatile Map<RequestMappingInfo, Forma[]> formas = Map.of();

	private static final Forma[] SIN_DESCARTE = new Forma[0];

	@Override
	protected void handlerMethodsInitialized(Map<RequestMappingInfo, HandlerMethod> handlerMethods) {
		super.handlerMethodsInitialized(handlerMethods);
		Map<RequestMappingInfo, Forma[]> porRuta = new IdentityHashMap<>();
		for (RequestMappingInfo info : handlerMethods.keySet()) {
			porRuta.put(info, formasDe(info));
		}
		this.formas = porRuta;
	}

	@Override
	protected HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request)
			throws Exception {
		List<PathContainer.Element> previo = CAMINO.get();
		CAMINO.set(caminoDe(request));
		try {
			return super.lookupHandlerMethod(lookupPath, request);
		} finally {
			if (previo == null) {
				CAMINO.remove();
			} else {
				CAMINO.set(previo);
			}
		}
	}

	@Override
	protected RequestMappingInfo getMatchingMapping(RequestMappingInfo info, HttpServletRequest request) {
		List<PathContainer.Element> camino = CAMINO.get();
		if (camino != null) {
			Forma[] deLaRuta = formas.get(info);
			if (deLaRuta != null && deLaRuta != SIN_DESCARTE && !algunaPuedeCoincidir(deLaRuta, camino)) {
				return null;
			}
		}
		return super.getMatchingMapping(info, request);
	}

	private List<PathContainer.Element> caminoDe(HttpServletRequest request) {
		if (getPatternParser() == null || !getPatternParser().isCaseSensitive()
				|| !ServletRequestPathUtils.hasParsedRequestPath(request)) {
			return null;
		}
		RequestPath ruta = ServletRequestPathUtils.getParsedRequestPath(request);
		return ruta.pathWithinApplication().elements();
	}

	private static boolean algunaPuedeCoincidir(Forma[] deLaRuta, List<PathContainer.Element> camino) {
		for (Forma forma : deLaRuta) {
			if (forma.puedeCoincidir(camino)) {
				return true;
			}
		}
		return false;
	}

	/** Las formas de los patrones de la ruta, o {@link #SIN_DESCARTE} si alguno no es simple. */
	static Forma[] formasDe(RequestMappingInfo info) {
		PathPatternsRequestCondition condicion = info.getPathPatternsCondition();
		if (condicion == null || condicion.getPatterns().isEmpty()) {
			return SIN_DESCARTE;
		}
		Forma[] resultado = new Forma[condicion.getPatterns().size()];
		int i = 0;
		for (PathPattern patron : condicion.getPatterns()) {
			Forma forma = Forma.de(patron.getPatternString());
			if (forma == null) {
				return SIN_DESCARTE;
			}
			resultado[i++] = forma;
		}
		return resultado;
	}

	/**
	 * La forma de un patron simple: {@code literales[i]} es el texto del segmento {@code i}, o
	 * {@code null} si el segmento es una variable.
	 */
	record Forma(String[] literales) {

		/** {@code null} si el patron no es simple. */
		static Forma de(String patron) {
			if (patron.length() < 2 || patron.charAt(0) != '/' || patron.endsWith("/")) {
				return null;
			}
			String[] partes = patron.substring(1).split("/", -1);
			String[] literales = new String[partes.length];
			for (int i = 0; i < partes.length; i++) {
				String parte = partes[i];
				if (VARIABLE.matcher(parte).matches()) {
					literales[i] = null;
				} else if (!parte.isEmpty() && !NO_LITERAL.matcher(parte).find()) {
					literales[i] = parte;
				} else {
					return null;
				}
			}
			return new Forma(literales);
		}

		/**
		 * Condicion necesaria para que el patron coincida: el camino es exactamente
		 * {@code /segmento} repetido tantas veces como segmentos tiene el patron, y cada literal
		 * es igual al valor decodificado de su segmento.
		 */
		boolean puedeCoincidir(List<PathContainer.Element> camino) {
			if (camino.size() != literales.length * 2) {
				return false;
			}
			for (int i = 0; i < literales.length; i++) {
				if (!(camino.get(2 * i) instanceof PathContainer.Separator)
						|| !(camino.get(2 * i + 1) instanceof PathContainer.PathSegment segmento)) {
					return false;
				}
				String literal = literales[i];
				if (literal != null && !literal.equals(segmento.valueToMatch())) {
					return false;
				}
			}
			return true;
		}
	}
}
