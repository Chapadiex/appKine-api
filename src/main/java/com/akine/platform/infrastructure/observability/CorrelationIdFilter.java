package com.akine.platform.infrastructure.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;

import io.micrometer.common.KeyValue;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Id de correlacion por request (G-4).
 *
 * <h2>Que hace</h2>
 * <ol>
 *   <li>Toma el {@code X-Request-Id} entrante si es valido; si no viene, o no es valido, genera
 *       uno (UUID).</li>
 *   <li>Lo publica en el MDC como {@code requestId}, asi que aparece en cada linea de log del
 *       request, incluidas las de los filtros de seguridad.</li>
 *   <li>Lo devuelve en el header {@code X-Request-Id} de la respuesta, <b>tambien en los
 *       rechazos</b> (401, 403, 429): corre antes que toda la cadena de Spring Security, y el
 *       header se escribe antes de seguir la cadena.</li>
 *   <li>Lo agrega al span del request como atributo de <b>alta cardinalidad</b>
 *       ({@code akine.request_id}): aparece en la traza y nunca como tag de una metrica.</li>
 * </ol>
 *
 * <h2>Por que se valida el header entrante</h2>
 * El valor lo controla el cliente y termina en cada linea de log. Sin validar, un
 * {@code X-Request-Id} con saltos de linea fabrica lineas de log falsas, y uno de un mega
 * llena el disco. Se acepta solo {@code [A-Za-z0-9._:-]}, hasta 64 caracteres —entra un UUID,
 * un ULID y el id de cualquier proxy conocido—; lo que no cumple se descarta en silencio y se
 * genera uno propio. No se responde 400: un id de correlacion mal formado no es motivo para
 * rechazar el pedido del usuario.
 *
 * <h2>Request id y trace id no son lo mismo</h2>
 * El {@code traceId} lo genera Micrometer Tracing y es la llave de la traza distribuida (y de
 * {@code audit_event.correlation_id}). El {@code requestId} es la llave que ve el usuario o el
 * frontend: es el que se pide en un reporte de error, y con el se encuentra el {@code traceId}
 * en el log.
 *
 * <h2>Orden</h2>
 * Se registra justo despues de {@link ServerHttpObservationFilter} (ver
 * {@link ObservabilityConfig}): necesita el span abierto para agregarle el atributo, y tiene que
 * correr antes de la cadena de seguridad para que los rechazos tambien lleven el id.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

	/** Header de entrada y de salida. */
	public static final String HEADER = "X-Request-Id";

	/** Atributo del request: el error dispatch reusa el mismo id en vez de generar otro. */
	static final String ATRIBUTO = CorrelationIdFilter.class.getName() + ".requestId";

	/** Atributo del span. Alta cardinalidad: va a la traza, nunca a una metrica. */
	static final String ATRIBUTO_DE_SPAN = "akine.request_id";

	private static final Pattern VALIDO = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String requestId = resolver(request);
		request.setAttribute(ATRIBUTO, requestId);
		response.setHeader(HEADER, requestId);
		ServerHttpObservationFilter.findObservationContext(request).ifPresent(contexto ->
				contexto.addHighCardinalityKeyValue(KeyValue.of(ATRIBUTO_DE_SPAN, requestId)));
		conMdc(requestId, request, response, filterChain);
	}

	/**
	 * El error dispatch (la pagina de error del contenedor tras una excepcion no manejada) vuelve
	 * a pasar por la cadena con el mismo request. {@code OncePerRequestFilter} no lo filtra de
	 * nuevo, pero el MDC del dispatch original ya se limpio: sin esto, el log del error —que es
	 * justamente el que mas se busca— saldria sin {@code requestId}.
	 */
	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected void doFilterNestedErrorDispatch(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Object id = request.getAttribute(ATRIBUTO);
		if (id instanceof String requestId) {
			conMdc(requestId, request, response, filterChain);
		} else {
			filterChain.doFilter(request, response);
		}
	}

	private static void conMdc(
			String requestId,
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String anterior = MDC.get(ClavesDeMdc.REQUEST_ID);
		MDC.put(ClavesDeMdc.REQUEST_ID, requestId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			// Los hilos del contenedor se reutilizan: un requestId que sobrevive al request
			// aparece en el log del proximo pedido que caiga en este hilo.
			if (anterior == null) {
				MDC.remove(ClavesDeMdc.REQUEST_ID);
			} else {
				MDC.put(ClavesDeMdc.REQUEST_ID, anterior);
			}
		}
	}

	static String resolver(HttpServletRequest request) {
		Object yaAsignado = request.getAttribute(ATRIBUTO);
		if (yaAsignado instanceof String id) {
			return id;
		}
		String entrante = request.getHeader(HEADER);
		if (entrante != null && VALIDO.matcher(entrante).matches()) {
			return entrante;
		}
		return UUID.randomUUID().toString();
	}
}
