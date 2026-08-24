package com.akine.platform.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El limite de intentos sobre los endpoints sensibles de identidad.
 *
 * <p>Dos tests cargan el peso. {@link #el_limite_no_distingue_de_que_cuenta_se_trata()} es la
 * garantia anti-enumeracion de ADR-0018 llevada al rate limit: el filtro <b>no lee el cuerpo del
 * request</b>, asi que dos intentos sobre emails distintos consumen el mismo cupo y nadie puede
 * deducir cual de los dos existe mirando cuando aparece el 429. Y
 * {@link #las_rutas_de_negocio_no_se_limitan()} evita el error opuesto: aplicar el limite a toda
 * la API convierte una jornada normal de consultorio en un corte de servicio.
 */
class RateLimitFilterTest {

	private static final Instant T0 = Instant.parse("2026-08-23T12:00:00Z");
	private static final String IP = "203.0.113.7";

	private RateLimitFilter filtro(int maximo, boolean habilitado) {
		return filtro(maximo, maximo, maximo, habilitado);
	}

	private RateLimitFilter filtro(int maximo, int maximoDeRegistro, boolean habilitado) {
		return filtro(maximo, maximoDeRegistro, maximo, habilitado);
	}

	private RateLimitFilter filtro(
			int maximo, int maximoDeRegistro, int maximoDeAltaDeColaborador, boolean habilitado) {

		return new RateLimitFilter(
				new FixedWindowRateLimiter(Duration.ofMinutes(1), maximo),
				new FixedWindowRateLimiter(Duration.ofMinutes(1), maximoDeRegistro),
				new FixedWindowRateLimiter(Duration.ofMinutes(1), maximoDeAltaDeColaborador),
				Clock.fixed(T0, ZoneOffset.UTC),
				habilitado);
	}

	private MockHttpServletRequest requestDe(String ruta, String ip, String cuerpo) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ruta);
		request.setRemoteAddr(ip);
		if (cuerpo != null) {
			request.setContent(cuerpo.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			request.setContentType("application/json");
		}
		return request;
	}

	private MockHttpServletResponse ejecutar(RateLimitFilter filtro, MockHttpServletRequest req)
			throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filtro.doFilter(req, response, new MockFilterChain());
		return response;
	}

	@Test
	@DisplayName("corta con 429 en Problem Details al superar el cupo")
	void corta_con_429() throws Exception {
		RateLimitFilter filtro = filtro(2, true);

		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", IP, null)).getStatus())
				.isEqualTo(200);
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", IP, null)).getStatus())
				.isEqualTo(200);

		MockHttpServletResponse cortado =
				ejecutar(filtro, requestDe("/api/v1/auth/login", IP, null));

		assertThat(cortado.getStatus()).isEqualTo(429);
		assertThat(cortado.getContentType()).startsWith("application/problem+json");
		assertThat(cortado.getContentAsString())
				.contains("https://akine.app/problems/rate-limited")
				.contains("\"status\":429");
		assertThat(cortado.getHeader("Retry-After")).isEqualTo("60");
	}

	@Test
	@DisplayName("el limite no distingue de que cuenta se trata: no es un oraculo de existencia")
	void el_limite_no_distingue_de_que_cuenta_se_trata() throws Exception {
		RateLimitFilter filtro = filtro(2, true);

		// Dos emails distintos, uno "existente" y otro no: para el filtro son el mismo cupo,
		// porque el cuerpo del request no se lee nunca.
		ejecutar(filtro, requestDe("/api/v1/auth/login", IP, "{\"email\":\"ana@ejemplo.test\"}"));
		ejecutar(filtro, requestDe("/api/v1/auth/login", IP, "{\"email\":\"nadie@ejemplo.test\"}"));

		MockHttpServletResponse tercero = ejecutar(filtro,
				requestDe("/api/v1/auth/login", IP, "{\"email\":\"ana@ejemplo.test\"}"));
		assertThat(tercero.getStatus()).isEqualTo(429);

		MockHttpServletResponse cuarto = ejecutar(filtro,
				requestDe("/api/v1/auth/login", IP, "{\"email\":\"nadie@ejemplo.test\"}"));
		assertThat(cuarto.getStatus()).isEqualTo(429);
		// Misma respuesta, byte a byte, para el email que existiria y para el que no.
		assertThat(cuarto.getContentAsString()).isEqualTo(tercero.getContentAsString());
	}

	@Test
	@DisplayName("cada ruta lleva su propio cupo")
	void cada_ruta_lleva_su_propio_cupo() throws Exception {
		RateLimitFilter filtro = filtro(1, true);

		ejecutar(filtro, requestDe("/api/v1/auth/login", IP, null));
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", IP, null)).getStatus())
				.isEqualTo(429);
		// Agotar el login no puede dejar sin refresh a quien ya tiene sesion abierta.
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/refresh", IP, null)).getStatus())
				.isEqualTo(200);
	}

	@Test
	@DisplayName("el alta directa de colaboradores tiene su propio cupo, y agotarlo no toca el login")
	void el_alta_de_colaborador_tiene_su_propio_cupo() throws Exception {
		// Este limite es una de las dos mitigaciones que hacen aceptable el 404 del email
		// desconocido (decision del 24/08/2026). Si la ruta dejara de estar limitada, el
		// endpoint volveria a ser un oraculo de enumeracion sin techo, y nada mas lo notaria.
		RateLimitFilter filtro = filtro(5, 5, 2, true);

		assertThat(ejecutar(filtro, requestDe("/api/v1/memberships", IP, null)).getStatus())
				.isEqualTo(200);
		assertThat(ejecutar(filtro, requestDe("/api/v1/memberships", IP, null)).getStatus())
				.isEqualTo(200);
		assertThat(ejecutar(filtro, requestDe("/api/v1/memberships", IP, null)).getStatus())
				.isEqualTo(429);

		// Cupo propio: barrer emails no puede dejar sin entrar al resto del consultorio.
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", IP, null)).getStatus())
				.isEqualTo(200);
		// Y la gestion de colaboradores de organization, que es otra ruta, no se limita.
		assertThat(ejecutar(filtro, requestDe("/api/v1/organizations/7/memberships", IP, null))
				.getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("cada IP lleva su propio cupo")
	void cada_ip_lleva_su_propio_cupo() throws Exception {
		RateLimitFilter filtro = filtro(1, true);

		ejecutar(filtro, requestDe("/api/v1/auth/login", IP, null));
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", IP, null)).getStatus())
				.isEqualTo(429);
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", "198.51.100.4", null))
				.getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("una sub-ruta de una ruta limitada tambien se limita")
	void una_subruta_tambien_se_limita() throws Exception {
		RateLimitFilter filtro = filtro(1, true);

		ejecutar(filtro, requestDe("/api/v1/auth/password-reset", IP, null));
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/password-reset/confirm", IP, null))
				.getStatus()).isEqualTo(200);
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/password-reset/confirm", IP, null))
				.getStatus()).isEqualTo(429);
	}

	@Test
	@DisplayName("las rutas de negocio no se limitan")
	void las_rutas_de_negocio_no_se_limitan() throws Exception {
		RateLimitFilter filtro = filtro(1, true);

		for (int intento = 0; intento < 10; intento++) {
			assertThat(ejecutar(filtro, requestDe("/api/v1/organizations", IP, null)).getStatus())
					.isEqualTo(200);
		}
		// Y una ruta que apenas empieza igual que una limitada tampoco se cuela.
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/loginfalso", IP, null)).getStatus())
				.isEqualTo(200);
	}

	@Test
	@DisplayName("una ruta con la letra codificada NO evade el limite: %6cogin cuenta como login")
	void una_ruta_codificada_no_evade_el_limite() throws Exception {
		// El agujero que fija este test, medido contra el backend real antes del arreglo:
		// cuarenta POST a /api/v1/auth/%6cogin devolvieron cuarenta 401 invalid-credentials y
		// ningun 429, mientras que los mismos cuarenta sobre /api/v1/auth/login daban 429 desde
		// el intento 31. El enrutamiento decodifica y llega a login(); el filtro comparaba la
		// URI cruda, no la encontraba en su lista y se salteaba. Fuerza bruta sin ningun limite.
		RateLimitFilter filtro = filtro(1, true);

		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/%6cogin", IP, null)).getStatus())
				.isEqualTo(200);
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/%6cogin", IP, null)).getStatus())
				.as("la ruta codificada tiene que consumir el mismo cupo que la normal")
				.isEqualTo(429);

		// Y comparte contador con la forma sin codificar: si fueran dos claves distintas, el
		// atacante duplicaria su cupo alternando entre las dos escrituras de la misma ruta.
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", IP, null)).getStatus())
				.isEqualTo(429);
	}

	@Test
	@DisplayName("el alta self-service esta limitada, y con su propio cupo mas chico")
	void el_registro_esta_limitado_con_su_propio_cupo() throws Exception {
		// No estaba en la lista. Sin limite, el registro era enumeracion a velocidad de red
		// (ADR-0018 apoya su 202 uniforme justamente en que "el rate limiting encarece la
		// enumeracion masiva"), bomba de correo dirigida contra el buzon de la victima, e
		// inundacion de tenants: cada email libre crea cuenta, organizacion, consultorio,
		// suscripcion y membership.
		RateLimitFilter filtro = filtro(10, 2, true);

		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/register", IP, null)).getStatus())
				.isEqualTo(200);
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/register", IP, null)).getStatus())
				.isEqualTo(200);
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/register", IP, null)).getStatus())
				.as("el cupo del alta es mas chico que el general y se agota antes")
				.isEqualTo(429);

		// El cupo del alta es propio: agotarlo no puede dejar sin login al consultorio entero
		// que comparte la IP.
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", IP, null)).getStatus())
				.isEqualTo(200);
	}

	@Test
	@DisplayName("deshabilitado deja pasar todo")
	void deshabilitado_deja_pasar_todo() throws Exception {
		RateLimitFilter filtro = filtro(1, false);

		for (int intento = 0; intento < 5; intento++) {
			assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", IP, null)).getStatus())
					.isEqualTo(200);
		}
	}

	@Test
	@DisplayName("un request sin IP resoluble no rompe el contador")
	void un_request_sin_ip_no_rompe_nada() throws Exception {
		RateLimitFilter filtro = filtro(1, true);
		MockHttpServletRequest sinIp = new MockHttpServletRequest("POST", "/api/v1/auth/login");
		sinIp.setRemoteAddr(null);

		assertThat(ejecutar(filtro, sinIp).getStatus()).isEqualTo(200);
		MockHttpServletRequest otro = new MockHttpServletRequest("POST", "/api/v1/auth/login");
		otro.setRemoteAddr(null);
		assertThat(ejecutar(filtro, otro).getStatus()).isEqualTo(429);
	}

	@Test
	@DisplayName("el loopback IPv6 y el IPv4 comparten cupo: el limite no se duplica por dual-stack")
	void ipv6_e_ipv4_comparten_cupo() throws Exception {
		// Sin normalizar, "0:0:0:0:0:0:0:1" y "127.0.0.1" son dos claves distintas y la misma
		// maquina obtiene el doble del cupo configurado alternando la familia de direcciones.
		// Un limite que se duplica cambiando de socket no es el limite que dice el numero.
		RateLimitFilter filtro = filtro(1, true);

		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", "127.0.0.1", null)).getStatus())
				.isEqualTo(200);
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", "0:0:0:0:0:0:0:1", null))
				.getStatus())
				.isEqualTo(429);
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", "::1", null)).getStatus())
				.isEqualTo(429);
	}

	@Test
	@DisplayName("una IPv4 mapeada en IPv6 cuenta como la misma direccion")
	void la_ipv4_mapeada_cuenta_igual() throws Exception {
		RateLimitFilter filtro = filtro(1, true);

		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", "192.0.2.10", null)).getStatus())
				.isEqualTo(200);
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", "::ffff:192.0.2.10", null))
				.getStatus())
				.isEqualTo(429);
	}

	@Test
	@DisplayName("dos direcciones realmente distintas siguen contando por separado")
	void dos_direcciones_distintas_no_se_mezclan() throws Exception {
		// La normalizacion junta formas equivalentes de la MISMA direccion, no agrupa por red:
		// castigar a un rango entero por un solo abusador seria peor que el problema.
		RateLimitFilter filtro = filtro(1, true);

		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", "192.0.2.10", null)).getStatus())
				.isEqualTo(200);
		assertThat(ejecutar(filtro, requestDe("/api/v1/auth/login", "192.0.2.11", null)).getStatus())
				.isEqualTo(200);
	}
}
