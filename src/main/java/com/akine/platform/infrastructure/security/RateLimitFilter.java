package com.akine.platform.infrastructure.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limite de intentos sobre los endpoints sensibles de identidad.
 *
 * <h2>Que protege</h2>
 *
 * <p>Login, refresh, pedido de restablecimiento y reenvio de activacion. Son los cuatro puntos
 * donde un desconocido puede repetir un intento sin costo: fuerza bruta de contrasenas, prueba
 * masiva de refresh robados y uso del correo saliente como amplificador de spam. El hash de
 * contrasena es caro a proposito (Argon2id, ADR-0018), asi que repetir el login tambien es un
 * vector de agotamiento de CPU. El alta self-service tiene su propio cupo, mas chico.
 *
 * <p><b>Y una ruta autenticada</b>, desde AKINE-01.03: el alta directa de colaboradores. Rompe
 * el patron a proposito. No la abusa un desconocido sino una sesion legitima comprometida, y lo
 * que se repite no es un intento de entrar sino una <b>pregunta</b>: "¿esta direccion tiene
 * cuenta?". Ver {@link #RUTA_DE_ALTA_DE_COLABORADOR}.
 *
 * <h2>La clave es ruta mas IP, y nunca el email</h2>
 *
 * <p><b>Esto no es una simplificacion: es la parte de seguridad de esta clase.</b> Un limite por
 * email convierte el contador en un oraculo de existencia de cuentas —se pregunta si el segundo
 * intento sobre una direccion se comporta distinto— y tira abajo la uniformidad que ADR-0018
 * construye en los tres endpoints publicos. Este filtro ni siquiera lee el cuerpo del request,
 * asi que por construccion no puede saber sobre que cuenta se esta operando.
 *
 * <p>El costo de esa decision, dicho de frente: varias personas detras del mismo NAT comparten
 * cupo. Por eso el limite es holgado —decenas de intentos por minuto, no tres— y busca frenar la
 * automatizacion, no castigar al que se equivoca dos veces escribiendo su contrasena.
 *
 * <h2>La IP es la del socket</h2>
 *
 * <p>{@code getRemoteAddr()}, no {@code X-Forwarded-For}: esa cabecera la escribe el cliente y
 * confiar en ella sin un proxy de confianza declarado permite evadir el limite entero cambiando
 * un header. <b>TODO(etapa de observabilidad e infraestructura):</b> cuando haya un proxy
 * inverso propio, declararlo como confiable y recien entonces leer la cabecera reenviada; hasta
 * entonces, detras de un balanceador todos los requests comparten la IP del balanceador y el
 * limite es efectivamente global, que falla del lado seguro.
 *
 * <h2>Corre antes de autenticar</h2>
 *
 * <p>Tiene que ser lo primero: limitar despues de verificar credenciales significaria pagar el
 * Argon2id de cada intento que se iba a rechazar igual.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

	/**
	 * El alta self-service. Tiene su propio cupo, mas chico que el del resto.
	 *
	 * <p><b>No estaba limitada, y era el hueco mas grande de esta clase.</b> ADR-0018 apoya
	 * explicitamente su decision de responder 202 uniforme en que "el rate limiting encarece la
	 * enumeracion masiva" —y esa mitigacion faltaba justo en el endpoint que el propio ADR llama
	 * "el mas facil de automatizar de todos", el unico que ni siquiera necesita una contrasena
	 * valida para preguntar—. Sin limite habia tres efectos, no uno: enumeracion de direcciones
	 * a velocidad de red, <b>bomba de correo dirigida</b> —cada request con un email registrado
	 * le encola al buzon de la victima un "ya tenes cuenta", sin tope— e <b>inundacion de
	 * tenants</b>, porque cada email libre crea cuenta, organizacion, consultorio, suscripcion y
	 * membership.
	 *
	 * <p><b>Por que el numero no es el mismo que el del login.</b> Un login fallido cuesta un
	 * Argon2id y nada mas, y equivocarse tres veces al tipear la contrasena es normal: por eso
	 * treinta. Un alta escribe cinco filas en cinco tablas y dispara un correo, y registrarse es
	 * un acto que una persona hace <b>una vez</b>: cinco por minuto y por IP le sobran incluso
	 * reintentando un formulario que fallo, y bajan el techo de la automatizacion en un factor
	 * de seis.
	 */
	static final String RUTA_DE_REGISTRO = "/api/v1/auth/register";

	/**
	 * El alta directa de colaboradores. Tercer cupo, distinto de los otros dos.
	 *
	 * <p><b>Es una mitigacion obligatoria, no una precaucion.</b> El endpoint recibe un email y
	 * responde 404 si no tiene cuenta: eso lo hace un <b>oraculo de enumeracion autenticado</b>.
	 * Un {@code ORG_ADMIN} comprometido puede recorrer direcciones y aprender cuales estan
	 * registradas en el SaaS entero. La decision del usuario del 24/08/2026 acepta ese 404 con
	 * dos contrapartidas: este limite y la auditoria de cada intento fallido. Retirar cualquiera
	 * de las dos obliga a volver a discutir el codigo de respuesta.
	 *
	 * <p><b>Por que diez y no treinta ni cinco.</b> No es un login fallido: no hay contrasena
	 * que tipear mal —el reintento honesto por torpeza no existe aca— ni Argon2id que agotar,
	 * asi que los treinta del cupo general sobran por un factor de tres. Tampoco es el alta
	 * publica: quien llega hasta aca ya se autentico, ya tiene contexto y ya tiene
	 * {@code colaborador:manage}, asi que los cinco del registro serian una molestia real para
	 * quien incorpora un equipo entero de una sentada. Es una accion administrativa deliberada,
	 * que una persona hace de a una y leyendo lo que escribe: diez por minuto le sobran, y le
	 * ponen a la automatizacion un techo de 14.400 direcciones por dia y por IP, cada una con su
	 * fila de auditoria. Sin limite, la misma lista se barre en segundos y en un solo evento.
	 *
	 * <p>El cupo es por IP y no por cuenta, igual que todo en esta clase: un contador por cuenta
	 * seria trivial de evadir rotando sesiones, y este filtro corre antes de autenticar, asi que
	 * ni siquiera sabe quien pregunta.
	 */
	static final String RUTA_DE_ALTA_DE_COLABORADOR = "/api/v1/memberships";

	/**
	 * Rutas limitadas con el cupo general.
	 *
	 * <p>Un endpoint publico de identidad que nace sin limite es un endpoint sin limite en
	 * produccion: la lista se completa al agregar la ruta, no despues.
	 */
	static final List<String> RUTAS_LIMITADAS = List.of(
			"/api/v1/auth/login",
			"/api/v1/auth/refresh",
			"/api/v1/auth/password-reset",
			"/api/v1/auth/activation/resend",
			// Invitacion a colaborar (M05). Las tres son publicas y las tres reciben un token:
			// sin limite, probar tokens al azar contra `accept` es gratis. Que el espacio de
			// busqueda sea enorme no reemplaza al limite —lo hace caro, no imposible— y ademas
			// `preview` responde distinto para un token vencido, que es una senal aprovechable.
			"/api/v1/auth/invitations");

	private final FixedWindowRateLimiter limiter;
	private final FixedWindowRateLimiter limiterDeRegistro;
	private final FixedWindowRateLimiter limiterDeAltaDeColaborador;
	private final Clock clock;
	private final boolean habilitado;

	public RateLimitFilter(
			FixedWindowRateLimiter limiter,
			FixedWindowRateLimiter limiterDeRegistro,
			FixedWindowRateLimiter limiterDeAltaDeColaborador,
			Clock clock,
			boolean habilitado) {

		this.limiter = limiter;
		this.limiterDeRegistro = limiterDeRegistro;
		this.limiterDeAltaDeColaborador = limiterDeAltaDeColaborador;
		this.clock = clock;
		this.habilitado = habilitado;
	}

	/**
	 * Solo se filtran las rutas sensibles.
	 *
	 * <p>Aplicarlo a toda la API convertiria una jornada normal de trabajo —muchos requests
	 * legitimos desde la misma IP del consultorio— en un corte de servicio.
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// La ruta sale DECODIFICADA (RequestPaths, via ProblemResponses). Mientras se comparaba
		// la URI cruda, POST /api/v1/auth/%6cogin se enrutaba a login() y este metodo devolvia
		// true: fuerza bruta de contrasenas sin ningun limite. Medido antes del arreglo.
		return !habilitado || limiterDe(ProblemResponses.rutaDe(request)) == null;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		Instant ahora = clock.instant();
		// Una sola vez: la ruta que se compara, la que entra en la clave del contador y la que
		// se loguea tienen que ser exactamente la misma cadena, o el limite y su diagnostico
		// hablan de dos requests distintos.
		String ruta = ProblemResponses.rutaDe(request);
		FixedWindowRateLimiter limitador = limiterDe(ruta);
		if (limitador == null) {
			// Solo alcanzable si el contenedor invoca el filtro sin pasar por shouldNotFilter.
			filterChain.doFilter(request, response);
			return;
		}

		String clave = claveDe(ruta, normalizarIp(request.getRemoteAddr()));
		if (limitador.permitir(clave, ahora)) {
			filterChain.doFilter(request, response);
			return;
		}

		long reintentarEn = limitador.segundosParaReintentar(clave, ahora);
		// La IP se loguea porque es un dato de conexion, no una credencial. El cuerpo del
		// request no se toca ni se registra: ahi viajan contrasenas y tokens (RN-M02-003).
		log.warn("Rate limit alcanzado: ruta={} ip={} reintentarEnSegundos={}",
				ruta, normalizarIp(request.getRemoteAddr()), reintentarEn);

		response.setHeader("Retry-After", Long.toString(reintentarEn));
		ProblemResponses.escribir(request, response, HttpStatus.TOO_MANY_REQUESTS,
				ProblemResponses.RATE_LIMITED,
				"Demasiados intentos",
				"Se alcanzo el limite de intentos. Espere unos instantes y vuelva a probar.");
	}

	/**
	 * La misma maquina tiene que contar una sola vez, llegue por IPv4 o por IPv6.
	 *
	 * <p>En un host dual-stack el loopback aparece como {@code 0:0:0:0:0:0:0:1} o como
	 * {@code 127.0.0.1} segun por donde entre el request, y una direccion IPv4 tunelizada llega
	 * como {@code ::ffff:192.0.2.1}. Como la clave del contador es {@code ruta|ip}, cada forma
	 * abre <b>su propio cupo</b>: el techo efectivo del limite pasa a ser el doble del
	 * configurado, y basta alternar la familia de direcciones para duplicarlo. Un limite que se
	 * puede duplicar cambiando de socket no es el limite que dice el numero.
	 *
	 * <p>Se normalizan las dos formas equivalentes: el prefijo {@code ::ffff:} de las direcciones
	 * IPv4 mapeadas se descarta, y el loopback IPv6 se colapsa al IPv4. No se intenta nada mas
	 * —agrupar por prefijo de red seria castigar a todo un rango por un solo abusador—, asi que
	 * dos direcciones realmente distintas siguen contando por separado, que es lo correcto.
	 */
	static String normalizarIp(String remota) {
		if (remota == null || remota.isBlank()) {
			return "desconocida";
		}
		String ip = remota.trim();
		if (ip.startsWith("::ffff:")) {
			ip = ip.substring("::ffff:".length());
		}
		if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
			return "127.0.0.1";
		}
		return ip;
	}

	/**
	 * Clave del contador.
	 *
	 * <p>Incluye la ruta para que agotar el cupo de login no deje sin refresh a quien ya tiene
	 * una sesion abierta desde la misma IP.
	 */
	private static String claveDe(String ruta, String ip) {
		return ruta + "|" + (ip == null ? "desconocida" : ip);
	}

	/**
	 * El contador que le corresponde a la ruta, o {@code null} si no esta limitada.
	 *
	 * <p>Son contadores <b>distintos</b> y no el mismo con dos topes: agotar el cupo del alta no
	 * puede dejar sin login a quien comparte la IP del consultorio, y al reves tampoco.
	 */
	private FixedWindowRateLimiter limiterDe(String ruta) {
		if (esFronteraDe(ruta, RUTA_DE_REGISTRO)) {
			return limiterDeRegistro;
		}
		if (esFronteraDe(ruta, RUTA_DE_ALTA_DE_COLABORADOR)) {
			return limiterDeAltaDeColaborador;
		}
		return RUTAS_LIMITADAS.stream().anyMatch(limitada -> esFronteraDe(ruta, limitada))
				? limiter
				: null;
	}

	/**
	 * Frontera exacta o con barra: {@code "/api/v1/auth/password-reset"} y su {@code "/confirm"}
	 * quedan limitados, y una ruta que apenas empiece igual no se cuela.
	 */
	private static boolean esFronteraDe(String ruta, String limitada) {
		return ruta.equals(limitada) || ruta.startsWith(limitada + "/");
	}
}
