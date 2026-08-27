package com.akine.identity.api;

import com.akine.identity.api.dto.AccessTokenResponse;
import com.akine.identity.api.dto.ClosedSessionsResponse;
import com.akine.identity.api.dto.LoginRequest;
import com.akine.identity.api.dto.SelectContextRequest;
import com.akine.identity.application.SessionService;
import com.akine.identity.domain.port.IdentityClock;
import com.akine.platform.spi.security.AccessTokenClaims;
import com.akine.platform.spi.security.AccessTokenVerifier;
import com.akine.platform.spi.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Ciclo de vida de la sesion por HTTP: abrir, renovar, elegir contexto y cerrar (RF-M02-002).
 *
 * <h2>El reparto de los dos tokens</h2>
 *
 * <p><b>El access token va en el cuerpo. El refresh token va en la cookie {@code akine_rt} y en
 * ningun otro lado.</b> No aparece en el JSON, no aparece en un header propio y no aparece en
 * ningun log, ni truncado ni a nivel DEBUG (ADR-0017, RN-M02-003). Devolverlo tambien en el
 * cuerpo anularia el {@code httpOnly} de la cookie: bastaria un XSS para llevarse una sesion de
 * doce horas.
 *
 * <h2>Login sin seleccion de rol</h2>
 *
 * <p>El login emite un token {@code pre_context} (DP-02, ADR-0009). Con ese alcance ningun
 * endpoint de negocio responde: el frontend pide {@code GET /api/v1/me/contexts} y elige uno con
 * {@link #context}. No hay "login como profesional": se autentica una identidad, y despues se
 * elige donde trabajar.
 *
 * <h2>Que es publico y por que</h2>
 *
 * <p>Login, refresh y logout son publicos porque quien los usa <b>no tiene todavia</b> un access
 * vivo —o justamente lo perdio—. Un logout que exigiera un access valido no se podria ejecutar
 * en el unico momento en que hace falta. La seleccion de contexto y el cierre masivo si exigen
 * autenticacion: operan sobre una identidad ya establecida.
 */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Sesion", description = "Login, renovacion, seleccion de contexto y cierre de sesion")
public class AuthSessionController {

	private static final Logger log = LoggerFactory.getLogger(AuthSessionController.class);

	private static final String PREFIJO_BEARER = "Bearer ";

	private final SessionService sessionService;
	private final AccessTokenVerifier accessTokenVerifier;
	private final TenantContextHolder tenantContextHolder;
	private final IdentityClock clock;

	public AuthSessionController(
			SessionService sessionService,
			AccessTokenVerifier accessTokenVerifier,
			TenantContextHolder tenantContextHolder,
			IdentityClock clock) {
		this.sessionService = sessionService;
		this.accessTokenVerifier = accessTokenVerifier;
		this.tenantContextHolder = tenantContextHolder;
		this.clock = clock;
	}

	// =================================================================================
	// Abrir
	// =================================================================================

	@PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "login",
			summary = "Autenticar y abrir una sesion",
			description = """
					Verifica las credenciales y emite el par de tokens de una sesion nueva.

					El access token sale en el cuerpo y dura diez minutos. El refresh token NO \
					sale en el cuerpo: viaja en la cookie akine_rt, que es httpOnly, Secure, \
					SameSite=Strict y esta acotada a Path=/api/v1/auth. El cliente no puede \
					leerla ni necesita hacerlo; para renovar solo llama a POST /auth/refresh con \
					credentials incluidas.

					El token sale con alcance pre_context: autentica la identidad y no habilita \
					ninguna operacion de negocio. El paso siguiente es GET /api/v1/me/contexts y \
					despues POST /api/v1/auth/context.

					RECHAZO UNIFORME (ADR-0018): email inexistente, contrasena incorrecta y \
					contrasena correcta sobre una cuenta bloqueada, desactivada o pendiente de \
					activacion devuelven las tres el MISMO 401 con el MISMO cuerpo, y con un \
					tiempo de respuesta equivalente. No hay forma de distinguir los tres casos \
					desde el cliente, y es deliberado: la diferencia convertiria este endpoint en \
					un verificador de direcciones y de credenciales filtradas de otros sitios. \
					Quien tenga la cuenta bloqueada se entera por el canal administrativo.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Sesion abierta. El refresh viaja en la cabecera Set-Cookie",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AccessTokenResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el email o la contrasena",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "401",
					description = "Credenciales invalidas. Mismo cuerpo para las tres causas",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "429",
					description = "Demasiados intentos desde esta IP",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AccessTokenResponse> login(
			@Valid @RequestBody LoginRequest request, HttpServletRequest http) {

		SessionService.SesionEmitida sesion = sessionService.abrirSesion(
				request.email(), request.password(), datosDe(http));

		return conCookieDeSesion(sesion);
	}

	// =================================================================================
	// Renovar
	// =================================================================================

	@PostMapping("/refresh")
	@Operation(
			operationId = "refreshSession",
			summary = "Renovar la sesion presentando la cookie de refresh",
			description = """
					Canjea el refresh de la cookie akine_rt por un par nuevo. No lleva cuerpo: la \
					credencial es la cookie, y ponerla ademas en el body la expondria al \
					JavaScript de la pagina.

					ROTACION ESTRICTA: cada canje invalida el refresh presentado y entrega uno \
					nuevo en la misma familia, heredando el vencimiento ABSOLUTO de la sesion. \
					Renovar no extiende la sesion: a las doce horas del login cae aunque se haya \
					refrescado sin parar.

					DETECCION DE REUSO: presentar un refresh ya canjeado revoca la familia \
					completa —victima y atacante quedan afuera— y responde 401. Ese 401 es \
					IDENTICO al de un token inexistente o vencido: el cliente no puede distinguir \
					"te detectamos" de "no servia", y es a proposito.

					Si el contexto que la sesion recordaba dejo de ser accesible —membership \
					revocada, suscripcion cancelada— el par nuevo sale degradado a pre_context en \
					vez de fallar: la persona sigue autenticada y el frontend la manda a elegir \
					contexto otra vez.

					El frontend debe serializar las renovaciones concurrentes con una cola \
					single-flight: dos pestanas refrescando a la vez producen un reuso legitimo y \
					se quedan las dos afuera.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Sesion renovada, con un refresh nuevo en Set-Cookie",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AccessTokenResponse.class))),
			@ApiResponse(
					responseCode = "401",
					description = "Refresh ausente, invalido, vencido, revocado o reusado. Los "
							+ "cinco casos responden igual y la respuesta borra la cookie",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "El Origin del request no es uno de los permitidos por "
							+ "configuracion: se rechaza como CSRF",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "429",
					description = "Demasiados intentos desde esta IP",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AccessTokenResponse> refresh(HttpServletRequest http) {
		SessionService.SesionEmitida sesion = sessionService.refrescar(
				RefreshCookies.leer(http), datosDe(http));

		return conCookieDeSesion(sesion);
	}

	// =================================================================================
	// Contexto
	// =================================================================================

	@PostMapping(path = "/context", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "selectContext",
			summary = "Elegir el contexto de trabajo y renovar el access token",
			description = """
					Emite un access token acotado a una Organizacion y un Consultorio. Es el paso \
					que convierte un token pre_context en uno operativo (DP-02).

					NO renueva el refresh ni entrega una cookie nueva: la sesion es la misma, lo \
					que cambia es el alcance del access. Lo que si hace es dejar el contexto \
					recordado en la familia viva, para que la proxima renovacion vuelva sobre el \
					mismo lugar en vez de devolver a la pantalla de seleccion cada diez minutos.

					Sirve tambien para CAMBIAR de contexto sin volver a loguearse, y cada cambio \
					queda auditado.

					Un par no accesible —consultorio inexistente, de otra organizacion, \
					membership vencida o suscripcion cancelada— responde 404, nunca 403: un 403 \
					confirmaria que ese consultorio existe y bastaria recorrer ids para mapear \
					los centros de la competencia.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Access token acotado al contexto elegido",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AccessTokenResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Identificadores ausentes o no positivos",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El contexto no existe o no esta disponible para esta cuenta",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AccessTokenResponse> context(
			@Valid @RequestBody SelectContextRequest request, HttpServletRequest http) {

		IdentityApiActor actor = IdentityApiActor.current(tenantContextHolder);

		SessionService.AccesoEmitido acceso = sessionService.cambiarContexto(
				actor.accountId(),
				familiaDelRequest(http).orElse(null),
				request.organizationId(),
				request.consultorioId());

		return ResponseEntity.ok(AccessTokenResponse.from(acceso));
	}

	// =================================================================================
	// Cerrar
	// =================================================================================

	// Produces explicito por lo mismo que en AccountRegistrationController.activate: sin cuerpo
	// en el 204 el contrato solo declara problem+json, el cliente generado lo manda en Accept y
	// el produces de clase lo rechazaba con 406.
	@PostMapping(path = "/logout",
			produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE })
	@Operation(
			operationId = "logout",
			summary = "Cerrar la sesion en curso",
			description = """
					Revoca la familia de refresh a la que pertenece la cookie presentada y borra \
					la cookie del navegador.

					SIEMPRE responde 204, incluso sin cookie o con una que no corresponde a \
					ninguna sesion. Es idempotente por construccion y no es una comodidad: un \
					logout que fallara ante un token desconocido seria un oraculo de tokens \
					validos, y ademas dejaria al frontend sin poder limpiar su estado justo \
					cuando la cookie ya no sirve.

					Cierra SOLO esta sesion. Los otros dispositivos siguen abiertos; para \
					echarlos a todos esta DELETE /api/v1/auth/sessions.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "204",
					description = "Sesion cerrada, o no habia ninguna. Siempre este codigo"),
			@ApiResponse(
					responseCode = "403",
					description = "El Origin del request no es uno de los permitidos: se rechaza "
							+ "como CSRF",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> logout(HttpServletRequest http) {
		int revocadas = sessionService.cerrarSesion(RefreshCookies.leer(http));
		log.debug("Logout procesado: sesionesRevocadas={}", revocadas);

		return ResponseEntity.noContent()
				.header(RefreshCookies.header(), RefreshCookies.borrar())
				.build();
	}

	@DeleteMapping("/sessions")
	@Operation(
			operationId = "closeAllSessions",
			summary = "Cerrar todas las sesiones de la propia cuenta",
			description = """
					Revoca todos los refresh vivos de la cuenta autenticada, en todos sus \
					dispositivos, y borra la cookie de este navegador.

					Es la accion de "cerrar sesion en todos lados" que corresponde despues de \
					perder un telefono o de sospechar un acceso ajeno. Opera SOLO sobre la propia \
					cuenta: el identificador sale del principal y no hay parametro que lo cambie.

					Los access token ya emitidos siguen siendo criptograficamente validos hasta \
					diez minutos (ADR-0017): la revocacion es real sobre el refresh, no sobre el \
					access en vuelo.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Sesiones revocadas. Cero es un resultado valido",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ClosedSessionsResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ClosedSessionsResponse> closeAllSessions() {
		IdentityApiActor actor = IdentityApiActor.current(tenantContextHolder);
		int revocadas = sessionService.cerrarTodasLasSesiones(actor.accountId());

		return ResponseEntity.ok()
				.header(RefreshCookies.header(), RefreshCookies.borrar())
				.body(new ClosedSessionsResponse(revocadas));
	}

	// =================================================================================
	// Piezas compartidas
	// =================================================================================

	/**
	 * Respuesta comun de login y refresh: access en el cuerpo, refresh en la cookie.
	 *
	 * <p>Esta en un solo lugar para que sea imposible que un camino instale la cookie con otros
	 * atributos que el otro. Un {@code SameSite} que se olvide en una sola de las dos rutas es
	 * un agujero de CSRF que ningun test de la otra ruta detectaria.
	 */
	private ResponseEntity<AccessTokenResponse> conCookieDeSesion(
			SessionService.SesionEmitida sesion) {

		String cookie = RefreshCookies.instalar(
				sesion.refreshPlano(), sesion.refreshExpiraEn(), clock.now());

		return ResponseEntity.ok()
				.header(RefreshCookies.header(), cookie)
				.body(AccessTokenResponse.from(sesion.acceso()));
	}

	/**
	 * Familia de la sesion en curso, del claim {@code fam} del access token presentado.
	 *
	 * <p><b>Por que se vuelve a verificar el token que el filtro ya verifico.</b> El principal
	 * que publica {@code platform} implementa {@code AuthenticatedPrincipal}, que expone cuenta
	 * y contexto pero no la familia; el record concreto que si la lleva es package-private de
	 * {@code platform.infrastructure.security} y alcanzarlo desde aca seria una flecha
	 * {@code identity.api -> platform.infrastructure} que ArchUnit rechaza. Lo que si es legal
	 * —y barato, porque es una verificacion HMAC sobre un token que ya esta en memoria— es
	 * pedirle los claims al {@link AccessTokenVerifier} del {@code spi}.
	 *
	 * <p>La cookie de refresh no sirve para esto: viaja con {@code Path=/api/v1/auth} pero su
	 * valor es opaco y no porta la familia, que solo existe del lado del servidor.
	 *
	 * <p>{@code Optional.empty()} es un resultado valido: {@code cambiarContexto} emite el token
	 * igual, solo que ninguna fila recuerda el contexto y la proxima renovacion volvera a salir
	 * pre-contexto.
	 */
	private Optional<String> familiaDelRequest(HttpServletRequest http) {
		String authorization = http.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null || !authorization.startsWith(PREFIJO_BEARER)) {
			return Optional.empty();
		}
		return accessTokenVerifier.verify(authorization.substring(PREFIJO_BEARER.length()).trim())
				.map(AccessTokenClaims::familyId)
				.filter(familia -> !familia.isBlank());
	}

	/**
	 * Lo que se sabe del cliente, para que la persona reconozca una sesion ajena en el listado.
	 *
	 * <p>Se trunca el user agent porque es una cadena que manda el cliente y puede venir de
	 * cualquier largo. La IP es la del socket: {@code X-Forwarded-For} la escribe el cliente y
	 * confiar en ella sin un proxy declarado permite escribir cualquier cosa en la fila.
	 */
	private static SessionService.DatosDeCliente datosDe(HttpServletRequest http) {
		String userAgent = http.getHeader(HttpHeaders.USER_AGENT);
		if (userAgent != null && userAgent.length() > 255) {
			userAgent = userAgent.substring(0, 255);
		}
		return new SessionService.DatosDeCliente(http.getRemoteAddr(), userAgent);
	}
}
