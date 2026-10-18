package com.akine.diferidos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.EmbeddedValueResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;

import com.akine.platform.infrastructure.config.MapeoDeRutasConDescarte;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MapeoDeRutasConDescarte} elige <b>el mismo handler</b> que el
 * {@code RequestMappingHandlerMapping} de Spring sin descarte, para cada ruta de la aplicacion
 * (docs/carga.md §13).
 *
 * <p>Arma, por cada operacion registrada, el pedido que le corresponde y variantes que no deben
 * coincidir —otro metodo, un segmento de mas o de menos, un literal cambiado, un literal
 * codificado con {@code %XX}— y compara el resultado de los dos mapeos: el mismo handler o el
 * mismo tipo de excepcion (405, 406, 415).
 */
class MapeoDeRutasEquivalenteIT extends BaseEscenarioDiferido {

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping mapeo;

	@Autowired
	private ApplicationContext contexto;

	@Autowired
	@Qualifier("mvcContentNegotiationManager")
	private ContentNegotiationManager negociacion;

	@Test
	@DisplayName("Mismo handler que Spring sin descarte, para cada ruta y sus variantes")
	void mismo_handler_que_spring() throws Exception {
		assertThat(mapeo).isInstanceOf(MapeoDeRutasConDescarte.class);
		// El descarte tiene la forma de cada ruta registrada: si no, esta comparacion pasaria sin probar nada.
		assertThat((Map<?, ?>) ReflectionTestUtils.getField(mapeo, "formas"))
				.hasSameSizeAs(mapeo.getHandlerMethods());

		RequestMappingHandlerMapping spring = new RequestMappingHandlerMapping();
		spring.setPatternParser(mapeo.getPatternParser());
		spring.setContentNegotiationManager(negociacion);
		spring.setPathPrefixes(mapeo.getPathPrefixes());
		spring.setEmbeddedValueResolver(new EmbeddedValueResolver(
				((ConfigurableApplicationContext) contexto).getBeanFactory()));
		spring.setApplicationContext(contexto);
		spring.afterPropertiesSet();
		assertThat(spring.getHandlerMethods()).hasSameSizeAs(mapeo.getHandlerMethods());

		List<MockHttpServletRequest> pedidos = new ArrayList<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> ruta : mapeo.getHandlerMethods().entrySet()) {
			RequestMappingInfo info = ruta.getKey();
			Set<org.springframework.web.bind.annotation.RequestMethod> metodos =
					info.getMethodsCondition().getMethods();
			String metodo = metodos.isEmpty() ? "GET" : metodos.iterator().next().name();
			for (PathPattern patron : info.getPathPatternsCondition().getPatterns()) {
				String camino = patron.getPatternString().replaceAll("\\{[^}/]+\\}", "1");
				pedidos.add(pedido(metodo, camino, info));
				pedidos.add(pedido(metodo.equals("GET") ? "POST" : "GET", camino, info));
				pedidos.add(pedido("DELETE", camino, info));
				pedidos.add(pedido(metodo, camino + "/extra", info));
				pedidos.add(pedido(metodo, camino + "/", info));
				int corte = camino.lastIndexOf('/');
				if (corte > 0) {
					pedidos.add(pedido(metodo, camino.substring(0, corte), info));
				}
				pedidos.add(pedido(metodo, camino.replaceFirst("/api/", "/apx/"), info));
				// Un literal codificado se decodifica antes de comparar: tiene que seguir coincidiendo.
				pedidos.add(pedido(metodo, codificarPrimeraLetraDelTercerSegmento(camino), info));
			}
		}

		int comparados = 0;
		for (MockHttpServletRequest pedido : pedidos) {
			assertThat(resultado(mapeo, pedido))
					.as("%s %s", pedido.getMethod(), pedido.getRequestURI())
					.isEqualTo(resultado(spring, copia(pedido)));
			comparados++;
		}
		assertThat(comparados).isGreaterThan(1_000);
	}

	private static String codificarPrimeraLetraDelTercerSegmento(String camino) {
		String[] partes = camino.split("/", -1);
		if (partes.length < 4 || partes[3].isEmpty()) {
			return camino;
		}
		partes[3] = String.format("%%%02X", (int) partes[3].charAt(0)) + partes[3].substring(1);
		return String.join("/", partes);
	}

	private static MockHttpServletRequest pedido(String metodo, String camino, RequestMappingInfo info) {
		MockHttpServletRequest pedido = new MockHttpServletRequest(metodo, camino);
		Set<MediaType> produce = info.getProducesCondition().getProducibleMediaTypes();
		pedido.addHeader("Accept", produce.isEmpty() ? "application/json" : produce.iterator().next().toString());
		Set<MediaType> consume = info.getConsumesCondition().getConsumableMediaTypes();
		if (!consume.isEmpty()) {
			pedido.setContentType(consume.iterator().next().toString());
			pedido.setContent("{}".getBytes());
		}
		return pedido;
	}

	private static MockHttpServletRequest copia(MockHttpServletRequest original) {
		MockHttpServletRequest pedido = new MockHttpServletRequest(original.getMethod(), original.getRequestURI());
		pedido.addHeader("Accept", Objects.requireNonNull(original.getHeader("Accept")));
		if (original.getContentType() != null) {
			pedido.setContentType(original.getContentType());
			pedido.setContent("{}".getBytes());
		}
		return pedido;
	}

	private static String resultado(RequestMappingHandlerMapping mapeo, MockHttpServletRequest pedido) {
		ServletRequestPathUtils.parseAndCache(pedido);
		try {
			HandlerExecutionChain cadena = mapeo.getHandler(pedido);
			return cadena == null ? "sin handler" : cadena.getHandler().toString();
		} catch (Exception e) {
			return e.getClass().getName();
		}
	}
}
