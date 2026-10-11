package com.akine.platform.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La IP del cliente detras de un proxy de confianza (simulacro de G-13).
 *
 * <p>Las dos mitades pesan igual. Sin proxy declarado, {@code X-Forwarded-For} <b>no vale
 * nada</b>: si valiera, evadir el limite seria cambiar un header. Con el nginx declarado, la IP es
 * la del cliente real y no la del nginx: si no, el limite es global para todo el sistema.
 */
class ResolutorDeIpDeClienteTest {

	private static final String NGINX = "172.18.0.5";
	private static final String CLIENTE = "203.0.113.7";
	private static final String ATACANTE = "198.51.100.9";

	private final ResolutorDeIpDeCliente detrasDelNginx =
			ResolutorDeIpDeCliente.desde(List.of("172.18.0.0/16"));

	@Test
	@DisplayName("sin proxies de confianza se ignora X-Forwarded-For: manda el socket")
	void sin_proxies_ignora_la_cabecera() {
		ResolutorDeIpDeCliente sinProxies = ResolutorDeIpDeCliente.desde(List.of());

		assertThat(sinProxies.resolver(ATACANTE, List.of("1.2.3.4"))).isEqualTo(ATACANTE);
		assertThat(sinProxies.resolver(requestDe(ATACANTE, "1.2.3.4"))).isEqualTo(ATACANTE);
	}

	@Test
	@DisplayName("una lista con entradas vacias equivale a no declarar proxies")
	void vacios_no_declaran_nada() {
		ResolutorDeIpDeCliente vacio = ResolutorDeIpDeCliente.desde(List.of("", "  "));

		assertThat(vacio.resolver(NGINX, List.of(CLIENTE))).isEqualTo(NGINX);
	}

	@Test
	@DisplayName("desde el proxy de confianza se toma el cliente real de X-Forwarded-For")
	void desde_el_proxy_toma_el_cliente() {
		assertThat(detrasDelNginx.resolver(NGINX, List.of(CLIENTE))).isEqualTo(CLIENTE);
		assertThat(detrasDelNginx.resolver(requestDe(NGINX, CLIENTE))).isEqualTo(CLIENTE);
	}

	@Test
	@DisplayName("un X-Forwarded-For falsificado desde una IP no confiable no evade el limite")
	void falsificado_desde_afuera_no_cuenta() {
		assertThat(detrasDelNginx.resolver(ATACANTE, List.of(CLIENTE))).isEqualTo(ATACANTE);
	}

	@Test
	@DisplayName("lo que el cliente antepone a traves del proxy no cuenta: vale el primer salto no confiable desde la derecha")
	void lo_antepuesto_por_el_cliente_no_cuenta() {
		// El atacante manda "X-Forwarded-For: 1.2.3.4" y el nginx le agrega su IP real al final.
		assertThat(detrasDelNginx.resolver(NGINX, List.of("1.2.3.4, " + ATACANTE)))
				.isEqualTo(ATACANTE);
		// Igual si llega como dos cabeceras.
		assertThat(detrasDelNginx.resolver(NGINX, List.of("1.2.3.4", ATACANTE)))
				.isEqualTo(ATACANTE);
	}

	@Test
	@DisplayName("los saltos intermedios de confianza se saltean")
	void saltea_proxies_encadenados() {
		assertThat(detrasDelNginx.resolver(NGINX, List.of(CLIENTE + ", 172.18.0.9")))
				.isEqualTo(CLIENTE);
	}

	@Test
	@DisplayName("si todos los saltos son de confianza, el cliente es el de mas a la izquierda")
	void todos_confiables() {
		assertThat(detrasDelNginx.resolver(NGINX, List.of("172.18.0.2, 172.18.0.9")))
				.isEqualTo("172.18.0.2");
	}

	@Test
	@DisplayName("desde el proxy pero sin cabecera, la IP es la del proxy")
	void proxy_sin_cabecera() {
		assertThat(detrasDelNginx.resolver(NGINX, List.of())).isEqualTo(NGINX);
	}

	@Test
	@DisplayName("una entrada que no es IP corta el recorrido en el ultimo salto confiable, sin DNS")
	void entrada_basura_falla_del_lado_seguro() {
		assertThat(detrasDelNginx.resolver(NGINX, List.of("localhost"))).isEqualTo(NGINX);
		assertThat(detrasDelNginx.resolver(NGINX, List.of("999.1.1.1"))).isEqualTo(NGINX);
		assertThat(detrasDelNginx.resolver(NGINX, List.of(CLIENTE + ", basura, 172.18.0.9")))
				.isEqualTo("172.18.0.9");
	}

	@Test
	@DisplayName("la IP resuelta se normaliza igual que la del socket (IPv4 mapeada)")
	void normaliza() {
		assertThat(detrasDelNginx.resolver("::ffff:" + NGINX, List.of("::ffff:" + CLIENTE)))
				.isEqualTo(CLIENTE);
	}

	@Test
	@DisplayName("acepta IPs sueltas e IPv6 en la lista")
	void ip_suelta_e_ipv6() {
		ResolutorDeIpDeCliente mixto =
				ResolutorDeIpDeCliente.desde(List.of(NGINX, "fd00::/8"));

		assertThat(mixto.resolver(NGINX, List.of(CLIENTE))).isEqualTo(CLIENTE);
		assertThat(mixto.resolver("172.18.0.6", List.of(CLIENTE))).isEqualTo("172.18.0.6");
		assertThat(mixto.resolver("fd00::1", List.of(CLIENTE))).isEqualTo(CLIENTE);
	}

	@Test
	@DisplayName("una entrada mal escrita en la configuracion falla al arrancar")
	void configuracion_invalida_falla() {
		assertThatThrownBy(() -> ResolutorDeIpDeCliente.desde(List.of("nginx")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("AKINE_TRUSTED_PROXIES");
		assertThatThrownBy(() -> ResolutorDeIpDeCliente.desde(List.of("172.18.0.0/40")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Nested
	@DisplayName("en el filtro")
	class EnElFiltro {

		private final Instant t0 = Instant.parse("2026-10-09T12:00:00Z");

		private RateLimitFilter filtro(ResolutorDeIpDeCliente resolutor) {
			return new RateLimitFilter(
					new FixedWindowRateLimiter(Duration.ofMinutes(1), 1),
					new FixedWindowRateLimiter(Duration.ofMinutes(1), 1),
					new FixedWindowRateLimiter(Duration.ofMinutes(1), 1),
					Clock.fixed(t0, ZoneOffset.UTC),
					true,
					resolutor);
		}

		private int login(RateLimitFilter filtro, String socket, String reenviada)
				throws Exception {
			MockHttpServletRequest request = requestDe(socket, reenviada);
			MockHttpServletResponse response = new MockHttpServletResponse();
			filtro.doFilter(request, response, new MockFilterChain());
			return response.getStatus();
		}

		@Test
		@DisplayName("detras del nginx declarado, dos clientes distintos tienen cupos distintos")
		void clientes_distintos_cupos_distintos() throws Exception {
			RateLimitFilter filtro = filtro(detrasDelNginx);

			assertThat(login(filtro, NGINX, CLIENTE)).isEqualTo(200);
			assertThat(login(filtro, NGINX, ATACANTE)).isEqualTo(200);
			assertThat(login(filtro, NGINX, CLIENTE)).isEqualTo(429);
		}

		@Test
		@DisplayName("sin proxy declarado, cambiar X-Forwarded-For no da cupo nuevo")
		void sin_proxy_la_cabecera_no_da_cupo() throws Exception {
			RateLimitFilter filtro = filtro(ResolutorDeIpDeCliente.sinProxies());

			assertThat(login(filtro, ATACANTE, "10.0.0.1")).isEqualTo(200);
			assertThat(login(filtro, ATACANTE, "10.0.0.2")).isEqualTo(429);
		}

		@Test
		@DisplayName("con proxy declarado, una cabecera falsificada desde afuera tampoco da cupo nuevo")
		void falsificada_desde_afuera_no_da_cupo() throws Exception {
			RateLimitFilter filtro = filtro(detrasDelNginx);

			assertThat(login(filtro, ATACANTE, "10.0.0.1")).isEqualTo(200);
			assertThat(login(filtro, ATACANTE, "10.0.0.2")).isEqualTo(429);
		}
	}

	private static MockHttpServletRequest requestDe(String socket, String reenviada) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
		request.setRemoteAddr(socket);
		if (reenviada != null) {
			request.addHeader("X-Forwarded-For", reenviada);
		}
		return request;
	}
}
