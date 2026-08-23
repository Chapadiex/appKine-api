package com.akine.platform.infrastructure.security;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akine.platform.infrastructure.config.SecurityConfig;

import static com.akine.PerfilesDeTest.SOLO_SLICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El filtro de {@code Origin} sobre los dos endpoints que se autentican con cookie (ADR-0017).
 *
 * <h2>Por que se prueba a traves de la cadena y no aislado</h2>
 *
 * <p>{@code OriginCsrfFilter} es una clase anidada package-private de
 * {@code platform.infrastructure.config.SecurityConfig}: desde este paquete no se la puede
 * instanciar. No es un accidente —el javadoc de la clase explica que existe solo para esa
 * cadena— asi que se la ejercita como la ejercita un request real: montando la cadena entera
 * con {@code @WebMvcTest} + {@code @Import(SecurityConfig.class)}, igual que
 * {@link SecurityChainTest}.
 *
 * <p>El slice monta la cadena <b>sin</b> {@code SecurityFiltersConfig}: los tres filtros que
 * arma esa clase se reciben por {@code ObjectProvider} y son opcionales. Dejarlos afuera saca
 * del medio al rate limit (que podria devolver 429 y enmascarar el 403 que se quiere observar)
 * y al filtro JWT, que aca no aporta nada porque las dos rutas bajo prueba son publicas.
 *
 * <h2>Lo que cada bloque garantiza</h2>
 *
 * <ul>
 *   <li><b>Alcance</b> — el filtro toca dos rutas y solo con la cookie puesta. Ni una mas
 *       (romperia a todo cliente que no sea navegador) ni una menos.</li>
 *   <li><b>Decision</b> — permitido pasa, ajeno no, y el ausente tampoco: <b>falla cerrado</b>.</li>
 *   <li><b>Comparacion</b> — las cuatro formas clasicas de burlar una lista de origenes:
 *       mayusculas, puerto explicito, esquema y sufijo de dominio.</li>
 *   <li><b>Respuesta</b> — Problem Details bien formado y que no devuelve el {@code Origin}
 *       recibido, que es el vector de XSS reflejado.</li>
 * </ul>
 */
@WebMvcTest(controllers = OriginCsrfFilterTest.ControllerDeSonda.class,
		properties = "akine.security.cors.allowed-origins="
				+ OriginCsrfFilterTest.PERMITIDO + "," + OriginCsrfFilterTest.PERMITIDO_LOCAL)
@Import({OriginCsrfFilterTest.ControllerDeSonda.class, SecurityConfig.class})
@ActiveProfiles(SOLO_SLICE)
class OriginCsrfFilterTest {

	static final String PERMITIDO = "https://akine.app";
	static final String PERMITIDO_LOCAL = "http://localhost:4200";
	private static final String HOSTIL = "https://atacante.test";

	private static final String REFRESH = "/api/v1/auth/refresh";
	private static final String LOGOUT = "/api/v1/auth/logout";
	private static final String LOGIN = "/api/v1/auth/login";
	private static final String NEGOCIO = "/api/v1/probe";

	private static final String TYPE_CSRF = "https://akine.app/problems/csrf-rejected";

	@Autowired
	private MockMvc mockMvc;

	@BeforeEach
	void reiniciarElContador() {
		ControllerDeSonda.LLEGADAS.set(0);
	}

	/** La cookie que dispara el filtro. Valor sintetico: el filtro no la lee, solo la detecta. */
	private static Cookie cookieDeRefresh() {
		return new Cookie("akine_rt", "valor-sintetico-sin-significado");
	}

	private MockHttpServletRequestBuilder conCookie(String ruta) {
		return post(ruta).cookie(cookieDeRefresh());
	}

	private void afirmarQueLlegoAlController(MvcResult resultado) {
		assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
		assertThat(ControllerDeSonda.LLEGADAS.get())
				.as("el request tenia que llegar al controller")
				.isEqualTo(1);
	}

	/**
	 * El request no llego al controller, sin exigir <b>quien</b> lo corto.
	 *
	 * <p>Sirve para los casos en que el rechazo lo produce otra pieza de la cadena y no este
	 * filtro: afirmar {@code csrf-rejected} ahi ataria el test a una responsabilidad que no es
	 * la que esta bajo prueba.
	 */
	private void afirmarQueNoLlego(MvcResult resultado) {
		assertThat(resultado.getResponse().getStatus()).isNotEqualTo(200);
		assertThat(ControllerDeSonda.LLEGADAS.get())
				.as("el request NO tenia que llegar al controller")
				.isZero();
	}

	private void afirmarRechazadoPorCsrf(MvcResult resultado) throws Exception {
		assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
		assertThat(resultado.getResponse().getContentAsString()).contains(TYPE_CSRF);
		assertThat(ControllerDeSonda.LLEGADAS.get())
				.as("el request NO tenia que llegar al controller")
				.isZero();
	}

	// =================================================================================
	// Alcance: que rutas y en que condiciones interviene el filtro
	// =================================================================================

	@Nested
	@DisplayName("Alcance")
	class Alcance {

		@Test
		@DisplayName("sin cookie no hay nada que robar: pasa aunque no declare ningun origen")
		void sin_cookie_pasa_sin_origen_ni_referer() throws Exception {
			// Es la decision explicita del filtro: un refresh sin akine_rt no puede rotar
			// ninguna sesion, asi que exigirle Origin solo romperia a curl y a los health
			// checks sin cerrar ningun vector.
			afirmarQueLlegoAlController(mockMvc.perform(post(REFRESH)).andReturn());
		}

		@Test
		@DisplayName("sin cookie, un Referer hostil tampoco lo frena")
		void sin_cookie_un_referer_hostil_no_lo_frena() throws Exception {
			// Se usa Referer y no Origin a proposito: un Origin ajeno lo cortaria el CorsFilter,
			// y entonces el test no diria nada sobre el filtro que se quiere probar.
			afirmarQueLlegoAlController(
					mockMvc.perform(post(REFRESH).header("Referer", HOSTIL + "/pagina")).andReturn());
		}

		@Test
		@DisplayName("una cookie que no es akine_rt no activa el filtro")
		void otra_cookie_no_activa_el_filtro() throws Exception {
			afirmarQueLlegoAlController(mockMvc.perform(post(REFRESH)
					.cookie(new Cookie("otra_cosa", "x"))
					.header("Referer", HOSTIL + "/pagina")).andReturn());
		}

		@Test
		@DisplayName("/auth/login queda fuera de alcance: no recibe la cookie de refresh")
		void login_queda_fuera_de_alcance() throws Exception {
			afirmarQueLlegoAlController(mockMvc.perform(conCookie(LOGIN)
					.header("Referer", HOSTIL + "/pagina")).andReturn());
		}

		@Test
		@DisplayName("un endpoint de negocio no lo toca el filtro: lo rechaza la autenticacion")
		void un_endpoint_de_negocio_no_lo_toca_el_filtro() throws Exception {
			// 401 y no 403: quien decide es authorizeHttpRequests, no el filtro de CSRF. Que el
			// cuerpo no mencione csrf-rejected es la mitad importante de la afirmacion.
			MvcResult resultado = mockMvc.perform(get(NEGOCIO)
							.cookie(cookieDeRefresh())
							.header("Referer", HOSTIL + "/pagina"))
					.andExpect(status().isUnauthorized())
					.andReturn();

			assertThat(resultado.getResponse().getContentAsString()).doesNotContain("csrf-rejected");
		}

		@Test
		@DisplayName("con context path el alcance no se pierde: la ruta se compara sin el prefijo")
		void con_context_path_el_alcance_no_se_pierde() throws Exception {
			// Si el filtro comparara el requestURI crudo, un despliegue bajo /akine dejaria de
			// proteger el refresh sin que nada avise. La instancia del Problem Details tampoco
			// puede llevar el prefijo: ataria el cuerpo al lugar del despliegue.
			mockMvc.perform(post("/akine" + REFRESH)
							.contextPath("/akine")
							.cookie(cookieDeRefresh())
							.header("Origin", HOSTIL))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.type").value(TYPE_CSRF))
					.andExpect(jsonPath("$.instance").value(REFRESH));

			assertThat(ControllerDeSonda.LLEGADAS.get()).isZero();
		}

		@Test
		@DisplayName("el preflight nunca se filtra: no lleva cookies y lo resuelve CORS")
		void el_preflight_nunca_se_filtra() throws Exception {
			mockMvc.perform(options(REFRESH)
							.cookie(cookieDeRefresh())
							.header("Origin", PERMITIDO)
							.header("Access-Control-Request-Method", "POST"))
					.andExpect(status().isOk());
		}
	}

	// =================================================================================
	// Decision: permitido pasa, ajeno no, ausente tampoco
	// =================================================================================

	@Nested
	@DisplayName("Decision")
	class Decision {

		@Test
		@DisplayName("Origin permitido con cookie: pasa")
		void origin_permitido_pasa() throws Exception {
			afirmarQueLlegoAlController(
					mockMvc.perform(conCookie(REFRESH).header("Origin", PERMITIDO)).andReturn());
		}

		@Test
		@DisplayName("cualquiera de los origenes configurados sirve, no solo el primero")
		void el_segundo_origen_configurado_tambien_sirve() throws Exception {
			afirmarQueLlegoAlController(
					mockMvc.perform(conCookie(REFRESH).header("Origin", PERMITIDO_LOCAL))
							.andReturn());
		}

		@Test
		@DisplayName("Origin de otro sitio con cookie: 403 csrf-rejected y no llega al controller")
		void origin_hostil_es_403() throws Exception {
			afirmarRechazadoPorCsrf(
					mockMvc.perform(conCookie(REFRESH).header("Origin", HOSTIL)).andReturn());
		}

		@Test
		@DisplayName("/auth/logout esta protegido igual que /auth/refresh")
		void logout_esta_protegido_igual() throws Exception {
			afirmarRechazadoPorCsrf(
					mockMvc.perform(conCookie(LOGOUT).header("Origin", HOSTIL)).andReturn());
			ControllerDeSonda.LLEGADAS.set(0);
			afirmarQueLlegoAlController(
					mockMvc.perform(conCookie(LOGOUT).header("Origin", PERMITIDO)).andReturn());
		}

		@Test
		@DisplayName("sin Origin ni Referer, con cookie: 403. Falla cerrado")
		void sin_origen_declarado_falla_cerrado() throws Exception {
			// Es LA decision incomoda del filtro y la que mas facil se revierte sin querer en un
			// refactor: no mandar el header es lo primero que prueba quien ataca, asi que la
			// ausencia tiene que rechazarse, no tolerarse.
			MvcResult resultado = mockMvc.perform(conCookie(REFRESH)).andReturn();

			afirmarRechazadoPorCsrf(resultado);
			assertThat(resultado.getResponse().getContentAsString())
					.as("el cuerpo no puede delatar que el origen venia vacio")
					.doesNotContain("ausente");
		}

		@Test
		@DisplayName("un Origin vacio equivale a no declarar origen: 403")
		void un_origin_vacio_es_ausencia() throws Exception {
			afirmarRechazadoPorCsrf(
					mockMvc.perform(conCookie(REFRESH).header("Origin", "   ")).andReturn());
		}

		@Test
		@DisplayName("Origin literal 'null' —iframe sandboxeado, redireccion— se rechaza")
		void el_origin_literal_null_se_rechaza() throws Exception {
			afirmarRechazadoPorCsrf(
					mockMvc.perform(conCookie(REFRESH).header("Origin", "null")).andReturn());
		}
	}

	// =================================================================================
	// Comparacion de origenes: las formas clasicas de burlar una lista
	// =================================================================================

	@Nested
	@DisplayName("Comparacion de origenes")
	class Comparacion {

		@Test
		@DisplayName("un dominio que solo empieza igual no pasa: akine.app.atacante.test")
		void un_sufijo_de_dominio_no_pasa() throws Exception {
			// El error clasico es comparar con startsWith. Aca la lista se compara por igualdad
			// exacta, y este test es el que se pondria rojo si alguien la aflojara.
			afirmarRechazadoPorCsrf(mockMvc.perform(conCookie(REFRESH)
					.header("Origin", "https://akine.app.atacante.test")).andReturn());
		}

		@Test
		@DisplayName("tampoco pasa el que termina igual: atacante-akine.app")
		void un_prefijo_de_dominio_no_pasa() throws Exception {
			afirmarRechazadoPorCsrf(mockMvc.perform(conCookie(REFRESH)
					.header("Origin", "https://atacante-akine.app")).andReturn());
		}

		@Test
		@DisplayName("cambiar el esquema no alcanza: http://akine.app no es https://akine.app")
		void el_esquema_importa() throws Exception {
			afirmarRechazadoPorCsrf(mockMvc.perform(conCookie(REFRESH)
					.header("Origin", "http://akine.app")).andReturn());
		}

		@Test
		@DisplayName("un Origin entrante con el puerto por defecto explicito no llega: lo corta CORS")
		void el_puerto_explicito_entrante_no_llega() throws Exception {
			// El filtro CSRF si lo acepta: para el, https://akine.app:443 y https://akine.app
			// son el mismo origen (RFC 6454). Quien lo rechaza es el CorsFilter de Spring, que
			// corre despues y compara el Origin entrante contra la lista sin normalizarle el
			// puerto por defecto.
			//
			// No se fuerza que pase, y a proposito: un navegador NUNCA serializa el puerto por
			// defecto en Origin, asi que este request no existe en la vida real. El caso que si
			// existia —la lista CONFIGURADA con el puerto escrito— se resuelve canonicalizando
			// esa lista en SecurityConfig#corsConfigurationSource, no aflojando esta puerta.
			//
			// Por eso el rechazo NO es un csrf-rejected: es de otra pieza de la cadena.
			afirmarQueNoLlego(mockMvc.perform(conCookie(REFRESH)
					.header("Origin", "https://akine.app:443")).andReturn());
		}

		@Test
		@DisplayName("el host en mayusculas es el mismo origen: https://AKINE.APP pasa")
		void el_host_en_mayusculas_pasa() throws Exception {
			// El host es case-insensitive (RFC 3986). Lo que NO se normaliza es el esquema
			// contra otro esquema ni un host que solo se parezca: eso lo fijan los tests de
			// arriba, que son los que se pondrian rojos si alguien aflojara la comparacion.
			afirmarQueLlegoAlController(mockMvc.perform(conCookie(REFRESH)
					.header("Origin", "https://AKINE.APP")).andReturn());
		}

		@Test
		@DisplayName("un Origin con path pegado no pasa: Origin es solo esquema, host y puerto")
		void un_origin_con_path_no_pasa() throws Exception {
			afirmarRechazadoPorCsrf(mockMvc.perform(conCookie(REFRESH)
					.header("Origin", PERMITIDO + "/")).andReturn());
		}
	}

	// =================================================================================
	// Referer como respaldo
	// =================================================================================

	@Nested
	@DisplayName("Referer como respaldo")
	class RefererDeRespaldo {

		@Test
		@DisplayName("sin Origin, un Referer permitido con path y query: pasa")
		void referer_permitido_con_path_y_query_pasa() throws Exception {
			afirmarQueLlegoAlController(mockMvc.perform(conCookie(REFRESH)
					.header("Referer", PERMITIDO + "/app/sesion?volver=/turnos#hoy")).andReturn());
		}

		@Test
		@DisplayName("sin Origin, un Referer de otro sitio: 403")
		void referer_hostil_es_403() throws Exception {
			afirmarRechazadoPorCsrf(mockMvc.perform(conCookie(REFRESH)
					.header("Referer", HOSTIL + "/trampa.html")).andReturn());
		}

		@Test
		@DisplayName("el Origin manda sobre el Referer: Origin hostil con Referer permitido es 403")
		void el_origin_manda_sobre_el_referer() throws Exception {
			afirmarRechazadoPorCsrf(mockMvc.perform(conCookie(REFRESH)
					.header("Origin", HOSTIL)
					.header("Referer", PERMITIDO + "/app")).andReturn());
		}

		@Test
		@DisplayName("un Referer con puerto explicito conserva el puerto al recortarse")
		void el_referer_conserva_el_puerto() throws Exception {
			// El origen configurado incluye el puerto, asi que reconstruirlo bien es la
			// diferencia entre pasar y no pasar.
			afirmarQueLlegoAlController(mockMvc.perform(conCookie(REFRESH)
					.header("Referer", "http://localhost:4200/app/sesion")).andReturn());
		}

		@Test
		@DisplayName("un Referer con puerto que no es el configurado: 403")
		void el_referer_con_otro_puerto_es_403() throws Exception {
			afirmarRechazadoPorCsrf(mockMvc.perform(conCookie(REFRESH)
					.header("Referer", "http://localhost:9999/app")).andReturn());
		}

		@Test
		@DisplayName("un Referer sin esquema no se puede recortar: 403")
		void un_referer_sin_esquema_es_403() throws Exception {
			afirmarRechazadoPorCsrf(mockMvc.perform(conCookie(REFRESH)
					.header("Referer", "//akine.app/app")).andReturn());
		}

		@Test
		@DisplayName("un Referer sin host —mailto, opaco— no se puede recortar: 403")
		void un_referer_opaco_es_403() throws Exception {
			afirmarRechazadoPorCsrf(mockMvc.perform(conCookie(REFRESH)
					.header("Referer", "mailto:alguien@akine.app")).andReturn());
		}

		@Test
		@DisplayName("un Referer malformado no rompe el filtro: 403, no 500")
		void un_referer_malformado_es_403_y_no_500() throws Exception {
			// URI.create lanza IllegalArgumentException; si no estuviera atrapada, el filtro
			// devolveria 500 y el request quedaria sin la respuesta que el frontend ramifica.
			afirmarRechazadoPorCsrf(mockMvc.perform(conCookie(REFRESH)
					.header("Referer", "http://[esto no es una uri]:::")).andReturn());
		}

		@Test
		@DisplayName("un Referer vacio equivale a no mandarlo: 403")
		void un_referer_vacio_es_403() throws Exception {
			afirmarRechazadoPorCsrf(
					mockMvc.perform(conCookie(REFRESH).header("Referer", "")).andReturn());
		}

		@Test
		@DisplayName("un Referer en blanco equivale a no mandarlo: 403")
		void un_referer_en_blanco_es_403() throws Exception {
			afirmarRechazadoPorCsrf(
					mockMvc.perform(conCookie(REFRESH).header("Referer", "\t  ")).andReturn());
		}

		@Test
		@DisplayName("el usuario embebido en el Referer no confunde al recorte del host")
		void el_userinfo_del_referer_no_confunde_al_host() throws Exception {
			// https://akine.app@atacante.test/ tiene host atacante.test, no akine.app. Es la
			// trampa de leer la URL con los ojos en vez de parsearla.
			afirmarRechazadoPorCsrf(mockMvc.perform(conCookie(REFRESH)
					.header("Referer", "https://akine.app@atacante.test/trampa")).andReturn());
		}
	}

	// =================================================================================
	// La respuesta
	// =================================================================================

	@Nested
	@DisplayName("Respuesta de rechazo")
	class Respuesta {

		@Test
		@DisplayName("es un Problem Details completo, con los cinco campos")
		void es_un_problem_details_completo() throws Exception {
			mockMvc.perform(conCookie(REFRESH).header("Origin", HOSTIL))
					.andExpect(status().isForbidden())
					.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
					.andExpect(jsonPath("$.type").value(TYPE_CSRF))
					.andExpect(jsonPath("$.title").value("Origen no permitido"))
					.andExpect(jsonPath("$.status").value(403))
					.andExpect(jsonPath("$.detail").exists())
					.andExpect(jsonPath("$.instance").value(REFRESH));
		}

		@Test
		@DisplayName("la instancia es la ruta pedida, no una fija")
		void la_instancia_es_la_ruta_pedida() throws Exception {
			mockMvc.perform(conCookie(LOGOUT).header("Origin", HOSTIL))
					.andExpect(jsonPath("$.instance").value(LOGOUT));
		}

		@Test
		@DisplayName("NO devuelve el Origin recibido: reflejarlo es XSS reflejado servido en bandeja")
		void no_devuelve_el_origin_recibido() throws Exception {
			// El payload es el clasico: si alguna version futura decidiera "ayudar" al cliente
			// diciendole que origen mando, este test se pone rojo antes de que llegue a produccion.
			String payload = "https://\"><script>alert(1)</script>.atacante.test";

			MvcResult resultado = mockMvc.perform(conCookie(REFRESH)
							.header("Origin", payload))
					.andExpect(status().isForbidden())
					.andReturn();

			String cuerpo = resultado.getResponse().getContentAsString();
			assertThat(cuerpo)
					.doesNotContain("<script>")
					.doesNotContain("atacante.test")
					.doesNotContain(payload);
			// Tampoco por header: un Origin reflejado en Access-Control-Allow-Origin seria
			// exactamente el agujero que allowCredentials(true) vuelve explotable.
			assertThat(resultado.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
		}

		@Test
		@DisplayName("el mismo Referer hostil tampoco vuelve en el cuerpo")
		void no_devuelve_el_referer_recibido() throws Exception {
			MvcResult resultado = mockMvc.perform(conCookie(REFRESH)
							.header("Referer", "https://atacante.test/robar?token=secreto"))
					.andExpect(status().isForbidden())
					.andReturn();

			assertThat(resultado.getResponse().getContentAsString())
					.doesNotContain("atacante.test")
					.doesNotContain("secreto");
		}

		@Test
		@DisplayName("la respuesta es identica para cualquier origen rechazado: no es un oraculo")
		void la_respuesta_no_distingue_que_origen_se_rechazo() throws Exception {
			String unRechazo = mockMvc.perform(conCookie(REFRESH).header("Origin", HOSTIL))
					.andReturn().getResponse().getContentAsString();
			String otroRechazo = mockMvc.perform(conCookie(REFRESH)
							.header("Origin", "https://otro-atacante.test"))
					.andReturn().getResponse().getContentAsString();
			String sinOrigen = mockMvc.perform(conCookie(REFRESH))
					.andReturn().getResponse().getContentAsString();

			assertThat(unRechazo).isEqualTo(otroRechazo).isEqualTo(sinOrigen);
		}

		@Test
		@DisplayName("no manda cabecera que borre la cookie: rechazar no es cerrar la sesion")
		void no_toca_la_cookie_de_la_victima() throws Exception {
			// Si el rechazo por CSRF borrara akine_rt, un sitio hostil podria desloguear a la
			// victima justamente con el request que se supone que estamos frenando.
			MvcResult resultado = mockMvc.perform(conCookie(REFRESH).header("Origin", HOSTIL))
					.andReturn();

			assertThat(resultado.getResponse().getHeaders("Set-Cookie")).isEmpty();
		}
	}

	// =================================================================================
	// Normalizacion de la ruta: el alcance del filtro se define por comparacion textual
	// =================================================================================

	@Nested
	@DisplayName("Normalizacion de la ruta")
	class NormalizacionDeLaRuta {

		/**
		 * El alcance del filtro se decide con {@code List.contains(request.getRequestURI())}, o
		 * sea comparacion textual contra dos literales, sin normalizar. Toda variante de la ruta
		 * que el enrutamiento siga aceptando pero que no sea textualmente identica esquivaria el
		 * filtro.
		 *
		 * <p>Medido: ninguna variante llega al endpoint, y por dos motivos distintos. Las que
		 * llevan caracteres sospechosos —{@code ;x=1} y el segmento {@code .}— las corta el
		 * {@code StrictHttpFirewall} con <b>400</b>. Las que son rutas legitimas pero distintas
		 * —{@code REFRESH}, la barra final, la barra doble— caen en <b>401</b>: los
		 * {@code requestMatchers(...).permitAll()} de la cadena comparan con el mismo criterio
		 * textual, asi que la variante que esquiva al filtro de CSRF esquiva tambien al permitAll
		 * y termina en {@code anyRequest().authenticated()}. Es coherencia, no suerte, pero es
		 * coherencia entre dos piezas que nadie ata: si alguna de las dos aflojara su criterio sin
		 * la otra, aparece el hueco. Por eso estos tests estan escritos.
		 */
		@Test
		@DisplayName("una barra doble no abre un camino alternativo a /auth/refresh")
		void la_barra_doble_no_abre_un_camino_alternativo() throws Exception {
			afirmarQueNoLlegaAlController("//api/v1/auth/refresh");
		}

		@Test
		@DisplayName("un segmento punto no abre un camino alternativo")
		void el_segmento_punto_no_abre_un_camino_alternativo() throws Exception {
			afirmarQueNoLlegaAlController("/api/v1/auth/./refresh");
		}

		@Test
		@DisplayName("un parametro de matriz no abre un camino alternativo")
		void el_parametro_de_matriz_no_abre_un_camino_alternativo() throws Exception {
			afirmarQueNoLlegaAlController("/api/v1/auth/refresh;x=1");
		}

		@Test
		@DisplayName("la ruta en mayusculas no abre un camino alternativo")
		void la_ruta_en_mayusculas_no_abre_un_camino_alternativo() throws Exception {
			afirmarQueNoLlegaAlController("/api/v1/auth/REFRESH");
		}

		@Test
		@DisplayName("una barra final no abre un camino alternativo")
		void la_barra_final_no_abre_un_camino_alternativo() throws Exception {
			afirmarQueNoLlegaAlController("/api/v1/auth/refresh/");
		}

		@Test
		@DisplayName("una letra percent-encoded no abre un camino alternativo: %72efresh es refresh")
		void la_letra_codificada_no_abre_un_camino_alternativo() throws Exception {
			// Este SI abria. Medido contra el backend real antes del arreglo:
			//   POST /api/v1/auth/%72efresh con la cookie akine_rt y sin Origin -> 401 (llego
			//   al servicio, el filtro se salteo), contra el 403 csrf-rejected de la ruta
			//   normal. El enrutamiento decodifica %72 a 'r' y encuentra el handler; el filtro
			//   comparaba la URI cruda contra su lista y no la encontraba. El StrictHttpFirewall
			//   no lo frena y no tiene por que: %72 es un alfanumerico codificado.
			afirmarQueNoLlegaAlController("/api/v1/auth/%72efresh");
		}

		/**
		 * Ejercita una variante de la ruta con la cookie puesta y un Referer hostil, y exige que
		 * el request no termine ejecutando el endpoint de refresh.
		 *
		 * <p>Se acepta cualquier forma de corte —400 del firewall, 401 de la autorizacion, 403
		 * del propio filtro, 404 del enrutamiento, o la excepcion que el slice propaga cuando no
		 * hay handler—. Lo que no se acepta es que el controller lo atienda.
		 */
		private void afirmarQueNoLlegaAlController(String ruta) throws Exception {
			try {
				mockMvc.perform(post(ruta)
						.cookie(cookieDeRefresh())
						.header("Referer", HOSTIL + "/trampa")).andReturn();
			} catch (Exception cortadoAntesDelHandler) {
				// Tambien es no llegar.
			}
			assertThat(ControllerDeSonda.LLEGADAS.get())
					.as("la variante '%s' llego al endpoint de refresh esquivando el filtro", ruta)
					.isZero();
		}
	}

	/**
	 * Controller de sonda con las tres rutas de identidad que necesita este test, mas una de
	 * negocio.
	 *
	 * <p>Existe para poder afirmar algo mas fuerte que un codigo de estado: el contador dice si el
	 * request <b>llego o no llego</b> al otro lado del filtro. Sin el, un 403 del filtro y un 403
	 * de cualquier otra pieza de la cadena se verian igual.
	 *
	 * <p><b>El {@code @Profile} no es decorativo.</b> Vivir en las fuentes de test no alcanza para
	 * quedar afuera del contrato: {@code src/test} esta en el classpath y el component scan de
	 * cualquier {@code @SpringBootTest} —incluido el que genera el OpenAPI— levanta cualquier
	 * clase bajo {@code com.akine} que lleve un estereotipo. Sin el perfil, este controller se
	 * publicaba en el contrato y, como mapea las rutas reales de identidad devolviendo un
	 * {@code String}, <b>pisaba los schemas de login, refresh y logout</b>: el cliente TypeScript
	 * generado recibia {@code string} donde esperaba {@code AccessTokenResponse} y no compilaba.
	 * Ver {@link com.akine.PerfilesDeTest}.
	 */
	@RestController
	@Profile(SOLO_SLICE)
	static class ControllerDeSonda {

		static final AtomicInteger LLEGADAS = new AtomicInteger();

		@PostMapping(REFRESH)
		String refresh() {
			LLEGADAS.incrementAndGet();
			return "ok";
		}

		@PostMapping(LOGOUT)
		String logout() {
			LLEGADAS.incrementAndGet();
			return "ok";
		}

		@PostMapping(LOGIN)
		String login() {
			LLEGADAS.incrementAndGet();
			return "ok";
		}

		@GetMapping(NEGOCIO)
		String negocio() {
			LLEGADAS.incrementAndGet();
			return "ok";
		}
	}
}
