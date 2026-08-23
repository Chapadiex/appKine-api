package com.akine.platform.infrastructure.security;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Escritura de errores RFC 7807 desde dentro de la cadena de filtros.
 *
 * <p><b>Por que no lo hace {@code GlobalExceptionHandler}.</b> Un filtro corre fuera del
 * {@code DispatcherServlet}: las excepciones que lance ahi no pasan por el
 * {@code @RestControllerAdvice}, asi que si el filtro no escribe la respuesta, el contenedor
 * devuelve su pagina de error por defecto —HTML, con el detalle que se le ocurra— y el frontend,
 * que ramifica por el campo {@code type}, no encuentra nada donde ramificar.
 *
 * <p>Se serializa un mapa plano con su propio {@link ObjectMapper} y no el del contexto, por la
 * misma razon que {@code TenantContextFilter}: esto es una pieza de seguridad y el cuerpo del
 * error no puede cambiar porque alguien configure un mixin de Jackson en otro lado.
 *
 * <p><b>Regla:</b> el {@code detail} es texto fijo. Nunca lleva el token presentado, ni un
 * fragmento, ni el motivo interno del rechazo. Un mensaje que distinga "firma invalida" de
 * "vencido" le entrega al atacante el resultado de cada intento.
 */
final class ProblemResponses {

	/** Prefijo unico de los {@code type} del proyecto (ADR-0005). */
	static final String BASE = "https://akine.app/problems/";

	static final URI UNAUTHORIZED = URI.create(BASE + "unauthorized");
	static final URI FORBIDDEN = URI.create(BASE + "forbidden");
	static final URI RATE_LIMITED = URI.create(BASE + "rate-limited");

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private ProblemResponses() {
		// Utilidad.
	}

	/**
	 * Ruta del request sin el context path, para no atar el cuerpo al despliegue.
	 *
	 * <p>Delega en {@link RequestPaths}: la ruta que se compara y la que se publica tienen que
	 * ser la <b>decodificada</b>, que es la que usa el enrutamiento. Cuando esto leia
	 * {@code getRequestURI()} directamente, {@code /api/v1/auth/%6cogin} se ruteaba al login y
	 * se saltaba el limite de intentos. Ver el javadoc de {@link RequestPaths}.
	 */
	static String rutaDe(HttpServletRequest request) {
		return RequestPaths.de(request);
	}

	static void escribir(
			HttpServletRequest request,
			HttpServletResponse response,
			HttpStatus status,
			URI type,
			String title,
			String detail) throws IOException {

		if (response.isCommitted()) {
			// Nada que hacer: alguien ya empezo a escribir. Insistir tiraria una excepcion que
			// taparia el error original.
			return;
		}

		Map<String, Object> cuerpo = new LinkedHashMap<>();
		cuerpo.put("type", type.toString());
		cuerpo.put("title", title);
		cuerpo.put("status", status.value());
		cuerpo.put("detail", detail);
		cuerpo.put("instance", rutaDe(request));

		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(JSON.writeValueAsString(cuerpo));
		response.getWriter().flush();
	}
}
