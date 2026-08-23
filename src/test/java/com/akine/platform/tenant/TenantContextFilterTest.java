package com.akine.platform.tenant;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akine.platform.infrastructure.tenant.TenantContextFilter;
import com.akine.platform.infrastructure.tenant.ThreadLocalTenantContextHolder;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.MembershipDirectory;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantMembership;
import com.akine.platform.spi.tenant.TenantOperationalStatus;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * La pieza de seguridad de AKINE-01.01: que ninguna consulta cruce tenants y que una membership
 * revocada deje de funcionar en el request siguiente.
 *
 * <p>Son tests unitarios: sin Spring, sin base, sin Testcontainers. La logica del filtro es
 * decision pura sobre el resultado del puerto, y probarla contra una base real solo agregaria
 * latencia y motivos de fallo ajenos a lo que se quiere verificar.
 */
@ExtendWith(MockitoExtension.class)
class TenantContextFilterTest {

	private static final Instant AHORA = Instant.parse("2026-08-22T12:00:00Z");

	private static final long CUENTA = 7L;
	private static final long ORGANIZACION = 100L;
	private static final long CONSULTORIO = 200L;

	@Mock
	private MembershipDirectory membershipDirectory;

	private ThreadLocalTenantContextHolder holder;
	private TenantContextFilter filtro;
	private MockHttpServletResponse response;
	private CadenaEspia cadena;

	@BeforeEach
	void preparar() {
		holder = new ThreadLocalTenantContextHolder();
		filtro = new TenantContextFilter(membershipDirectory, holder, Clock.fixed(AHORA, ZoneOffset.UTC));
		response = new MockHttpServletResponse();
		cadena = new CadenaEspia(holder);
	}

	@AfterEach
	void limpiar() {
		SecurityContextHolder.clearContext();
		holder.clear();
	}

	// =================================================================================
	// Camino feliz
	// =================================================================================

	@Test
	@DisplayName("Con contexto valido el holder queda poblado con el rol correcto")
	void contexto_valido_publica_el_contexto_con_su_rol() throws Exception {
		autenticar(principal(CUENTA, ORGANIZACION, CONSULTORIO, false));
		dadoQueElContextoResuelve(TenantOperationalStatus.ACTIVA, "PROFESIONAL");

		filtro.doFilter(request("GET", "/api/v1/pacientes"), response, cadena);

		assertThat(cadena.fueInvocada()).isTrue();
		RequestTenantContext publicado = cadena.contextoVisto().orElseThrow();
		assertThat(publicado.accountId()).isEqualTo(CUENTA);
		assertThat(publicado.organizationId()).isEqualTo(ORGANIZACION);
		assertThat(publicado.consultorioId()).isEqualTo(CONSULTORIO);
		assertThat(publicado.roleCode()).isEqualTo("PROFESIONAL");
		assertThat(publicado.operationalStatus()).isEqualTo(TenantOperationalStatus.ACTIVA);
	}

	@Test
	@DisplayName("El contexto se limpia al terminar el request: el hilo no se lo lleva puesto")
	void el_contexto_se_limpia_al_terminar() throws Exception {
		autenticar(principal(CUENTA, ORGANIZACION, CONSULTORIO, false));
		dadoQueElContextoResuelve(TenantOperationalStatus.ACTIVA, "ORG_ADMIN");

		filtro.doFilter(request("GET", "/api/v1/pacientes"), response, cadena);

		// Los hilos del contenedor se reutilizan: si el contexto sobreviviera, el proximo
		// usuario que caiga en este hilo heredaria el tenant del anterior.
		assertThat(holder.current()).isEmpty();
	}

	// =================================================================================
	// B-2: el codigo de estado, que es un bug de verdad
	// =================================================================================

	@Test
	@DisplayName("Autenticado SIN contexto seleccionado responde 403 missing-tenant-context, "
			+ "y JAMAS 401: un 401 le borraria el token al usuario y lo dejaria en un bucle de login")
	void sin_contexto_seleccionado_responde_403_y_nunca_401() throws Exception {
		autenticar(principal(CUENTA, null, null, false));

		filtro.doFilter(request("GET", "/api/v1/pacientes"), response, cadena);

		// El interceptor del frontend hace `if (error.status === 401) tokenStore.clear()`.
		// Con 401, un usuario recien logueado sin contexto perderia el token, volveria al
		// login, se autenticaria de nuevo, seguiria sin contexto: bucle cerrado.
		assertThat(response.getStatus()).isNotEqualTo(401);
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getContentType()).startsWith("application/problem+json");
		assertThat(response.getContentAsString())
				.contains("https://akine.app/problems/missing-tenant-context");
		assertThat(cadena.fueInvocada()).isFalse();
		assertThat(holder.current()).isEmpty();
	}

	// =================================================================================
	// Cross-tenant: 404, nunca 403
	// =================================================================================

	@Test
	@DisplayName("Un consultorio de otra organizacion responde 404, no 403: un 403 confirmaria "
			+ "que ese consultorio existe")
	void consultorio_de_otra_organizacion_responde_404_y_no_403() throws Exception {
		autenticar(principal(CUENTA, ORGANIZACION, 999L, false));
		given(membershipDirectory.resolveMembership(CUENTA, ORGANIZACION, 999L, AHORA))
				.willReturn(Optional.empty());

		filtro.doFilter(request("GET", "/api/v1/pacientes"), response, cadena);

		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(response.getStatus()).isNotEqualTo(403);
		assertThat(response.getContentAsString()).contains("https://akine.app/problems/not-found");
		// El cuerpo es generico: no dice que el consultorio exista, ni que sea de otra
		// organizacion, ni que falten permisos. Cualquiera de esas tres cosas confirmaria la
		// existencia de datos de otro tenant.
		assertThat(response.getContentAsString())
				.doesNotContain("organizacion")
				.doesNotContain("consultorio")
				.doesNotContain("permiso");
		assertThat(cadena.fueInvocada()).isFalse();
	}

	@Test
	@DisplayName("Una membership vencida (valid_until en el pasado) es rechazada")
	void membership_vencida_es_rechazada() throws Exception {
		autenticar(principal(CUENTA, ORGANIZACION, CONSULTORIO, false));
		// El puerto evalua la vigencia contra el instante que le pasa el filtro y devuelve
		// vacio: para el filtro, una membership vencida es indistinguible de una inexistente.
		given(membershipDirectory.resolveMembership(CUENTA, ORGANIZACION, CONSULTORIO, AHORA))
				.willReturn(Optional.empty());

		filtro.doFilter(request("GET", "/api/v1/pacientes"), response, cadena);

		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(cadena.fueInvocada()).isFalse();
	}

	// =================================================================================
	// T-7: ventana de revocacion cero
	// =================================================================================

	@Test
	@DisplayName("Una membership revocada entre dos requests hace fallar el segundo: ventana cero")
	void membership_revocada_entre_dos_requests_hace_fallar_el_segundo() throws Exception {
		autenticar(principal(CUENTA, ORGANIZACION, CONSULTORIO, false));
		given(membershipDirectory.resolveMembership(CUENTA, ORGANIZACION, CONSULTORIO, AHORA))
				.willReturn(
						Optional.of(new TenantMembership(1L, "PROFESIONAL", TenantOperationalStatus.ACTIVA)),
						Optional.empty());

		MockHttpServletResponse primera = new MockHttpServletResponse();
		CadenaEspia cadenaPrimera = new CadenaEspia(holder);
		filtro.doFilter(request("GET", "/api/v1/pacientes"), primera, cadenaPrimera);

		MockHttpServletResponse segunda = new MockHttpServletResponse();
		CadenaEspia cadenaSegunda = new CadenaEspia(holder);
		filtro.doFilter(request("GET", "/api/v1/pacientes"), segunda, cadenaSegunda);

		// El MISMO token, valido criptograficamente en los dos requests. Lo que cambio es la
		// base, y el filtro la vuelve a leer siempre: sin cache no hay ventana de gracia.
		assertThat(primera.getStatus()).isEqualTo(200);
		assertThat(cadenaPrimera.fueInvocada()).isTrue();
		assertThat(segunda.getStatus()).isEqualTo(404);
		assertThat(cadenaSegunda.fueInvocada()).isFalse();
	}

	// =================================================================================
	// Estado de la suscripcion
	// =================================================================================

	@Test
	@DisplayName("Una suscripcion CANCELADA es rechazada: su contexto no puede usarse")
	void suscripcion_cancelada_es_rechazada() throws Exception {
		autenticar(principal(CUENTA, ORGANIZACION, CONSULTORIO, false));
		dadoQueElContextoResuelve(TenantOperationalStatus.CANCELADA, "ORG_ADMIN");

		filtro.doFilter(request("GET", "/api/v1/pacientes"), response, cadena);

		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(cadena.fueInvocada()).isFalse();
	}

	@Test
	@DisplayName("Una organizacion dada de baja es rechazada igual que una cancelada")
	void organizacion_dada_de_baja_es_rechazada() throws Exception {
		autenticar(principal(CUENTA, ORGANIZACION, CONSULTORIO, false));
		dadoQueElContextoResuelve(TenantOperationalStatus.BAJA, "ORG_ADMIN");

		filtro.doFilter(request("GET", "/api/v1/pacientes"), response, cadena);

		assertThat(response.getStatus()).isEqualTo(404);
	}

	@Test
	@DisplayName("Con la suscripcion SUSPENDIDA la lectura sigue permitida (suspender bloquea, no destruye)")
	void suscripcion_suspendida_permite_lectura() throws Exception {
		autenticar(principal(CUENTA, ORGANIZACION, CONSULTORIO, false));
		dadoQueElContextoResuelve(TenantOperationalStatus.SUSPENDIDA, "ADMINISTRATIVO");

		filtro.doFilter(request("GET", "/api/v1/pacientes"), response, cadena);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(cadena.fueInvocada()).isTrue();
		assertThat(cadena.contextoVisto().orElseThrow().operationalStatus())
				.isEqualTo(TenantOperationalStatus.SUSPENDIDA);
	}

	@Test
	@DisplayName("Con la suscripcion SUSPENDIDA una mutacion de negocio devuelve 409 subscription-suspended")
	void suscripcion_suspendida_rechaza_mutacion_de_negocio() throws Exception {
		autenticar(principal(CUENTA, ORGANIZACION, CONSULTORIO, false));
		dadoQueElContextoResuelve(TenantOperationalStatus.SUSPENDIDA, "ADMINISTRATIVO");

		filtro.doFilter(request("POST", "/api/v1/pacientes"), response, cadena);

		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(response.getContentAsString())
				.contains("https://akine.app/problems/subscription-suspended");
		assertThat(cadena.fueInvocada()).isFalse();
	}

	@Test
	@DisplayName("Con la suscripcion SUSPENDIDA la administracion de la suscripcion sigue "
			+ "escribible: si no, la suspension seria terminal de hecho")
	void suscripcion_suspendida_permite_administrar_la_suscripcion() throws Exception {
		autenticar(principal(CUENTA, ORGANIZACION, CONSULTORIO, false));
		dadoQueElContextoResuelve(TenantOperationalStatus.SUSPENDIDA, "ORG_ADMIN");

		filtro.doFilter(
				request("POST", "/api/v1/organizations/100/subscription/transitions"), response, cadena);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(cadena.fueInvocada()).isTrue();
	}

	// =================================================================================
	// Rutas exceptuadas y PLATFORM_ADMIN
	// =================================================================================

	@Test
	@DisplayName("Una ruta exceptuada no consulta la base en absoluto")
	void ruta_exceptuada_no_consulta_la_base() throws Exception {
		autenticar(principal(CUENTA, null, null, false));

		for (String ruta : List.of(
				"/api/v1/version",
				"/api/v1/me/contexts",
				"/actuator/health",
				"/v3/api-docs",
				"/swagger-ui/index.html",
				"/api/v1/auth/login",
				"/api/v1/auth/refresh")) {

			MockHttpServletResponse respuesta = new MockHttpServletResponse();
			CadenaEspia espia = new CadenaEspia(holder);
			filtro.doFilter(request("GET", ruta), respuesta, espia);

			assertThat(espia.fueInvocada()).as("la ruta %s debe pasar sin contexto", ruta).isTrue();
			assertThat(respuesta.getStatus()).as("la ruta %s no debe ser rechazada", ruta).isEqualTo(200);
		}

		// Esto es lo que se esta probando: el health check y la seleccion de contexto responden
		// aunque la base este caida o el usuario no tenga ninguna membership.
		verifyNoInteractions(membershipDirectory);
	}

	@Test
	@DisplayName("Una ruta que solo EMPIEZA como una exceptuada si se filtra: el prefijo tiene frontera")
	void ruta_parecida_a_una_exceptuada_si_se_filtra() throws Exception {
		autenticar(principal(CUENTA, null, null, false));

		filtro.doFilter(request("GET", "/actuator-falso/secretos"), response, cadena);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(cadena.fueInvocada()).isFalse();
	}

	@Test
	@DisplayName("Un PLATFORM_ADMIN pasa sin contexto de tenant y sin consultar la base")
	void platform_admin_pasa_sin_contexto() throws Exception {
		autenticar(principal(CUENTA, null, null, true));

		filtro.doFilter(request("POST", "/api/v1/organizations"), response, cadena);

		// Un administrador de plataforma no tiene por que ser miembro de ninguna organizacion:
		// exigirle contexto haria imposible dar de alta la primera.
		assertThat(cadena.fueInvocada()).isTrue();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(cadena.contextoVisto()).isEmpty();
		verifyNoInteractions(membershipDirectory);
	}

	@Test
	@DisplayName("Sin principal el filtro deja pasar sin contexto: autenticar es trabajo de 01.02")
	void sin_principal_el_filtro_no_decide_nada() throws Exception {
		filtro.doFilter(request("GET", "/api/v1/pacientes"), response, cadena);

		assertThat(cadena.fueInvocada()).isTrue();
		assertThat(cadena.contextoVisto()).isEmpty();
		verifyNoInteractions(membershipDirectory);
	}

	@Test
	@DisplayName("El principal anonimo de Spring Security no cuenta como autenticado")
	void principal_anonimo_no_cuenta() throws Exception {
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated("anonymousUser", null, List.of()));

		filtro.doFilter(request("GET", "/api/v1/pacientes"), response, cadena);

		assertThat(cadena.fueInvocada()).isTrue();
		verifyNoInteractions(membershipDirectory);
	}

	// =================================================================================
	// Ayudas
	// =================================================================================

	private void dadoQueElContextoResuelve(TenantOperationalStatus estado, String rol) {
		given(membershipDirectory.resolveMembership(anyLong(), anyLong(), anyLong(), eq(AHORA)))
				.willReturn(Optional.of(new TenantMembership(1L, rol, estado)));
	}

	private void autenticar(AuthenticatedPrincipal principal) {
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}

	private static MockHttpServletRequest request(String metodo, String ruta) {
		MockHttpServletRequest request = new MockHttpServletRequest(metodo, ruta);
		request.setRequestURI(ruta);
		return request;
	}

	private static AuthenticatedPrincipal principal(
			long accountId, Long organizationId, Long consultorioId, boolean platformAdmin) {
		return new PrincipalDePrueba(accountId, organizationId, consultorioId, platformAdmin);
	}

	/**
	 * Fixture del principal. En 01.02 lo implementa {@code identity} desde el JWT; aca alcanza
	 * con un record, y eso demuestra que el contrato del {@code spi} no arrastra nada del
	 * mecanismo de autenticacion.
	 */
	private record PrincipalDePrueba(
			long accountId, Long organizationId, Long consultorioId, boolean platformAdmin)
			implements AuthenticatedPrincipal {
	}

	/**
	 * Cadena que registra si se la invoco y que contexto veia el holder en ese momento.
	 *
	 * <p>Mirar el holder DESPUES del filtro no serviria: el filtro lo limpia en un
	 * {@code finally}, que es justamente lo que debe hacer. El unico lugar donde se puede
	 * observar el contexto publicado es dentro de la cadena.
	 */
	private static final class CadenaEspia implements FilterChain {

		private final ThreadLocalTenantContextHolder holder;
		private final List<Optional<RequestTenantContext>> invocaciones = new ArrayList<>();

		private CadenaEspia(ThreadLocalTenantContextHolder holder) {
			this.holder = holder;
		}

		@Override
		public void doFilter(ServletRequest request, ServletResponse response) {
			invocaciones.add(holder.current());
		}

		boolean fueInvocada() {
			return !invocaciones.isEmpty();
		}

		Optional<RequestTenantContext> contextoVisto() {
			return invocaciones.isEmpty() ? Optional.empty() : invocaciones.get(0);
		}
	}
}
