package com.akine.platform.infrastructure.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.security.web.util.matcher.IpAddressMatcher;

import com.akine.platform.spi.security.IpDeCliente;

import jakarta.servlet.http.HttpServletRequest;

/**
 * La IP del cliente, con proxies de confianza declarados. La usan el rate limit y, por el
 * {@code spi} ({@link IpDeCliente}), la fila de cada sesion de {@code identity}.
 *
 * <h2>El defecto que cierra (simulacro de G-13)</h2>
 *
 * <p>{@link RateLimitFilter} usaba {@code getRemoteAddr()}. Detras del nginx de la imagen del
 * frontend (G-3, el {@code location /api/} hace proxy) <b>todas</b> las requests llegan desde la
 * IP del nginx, asi que el cupo de 30 logins por minuto y por IP se volvia un cupo de 30 logins
 * por minuto <b>para todo el sistema</b> —y el de 5 altas, cinco altas por minuto para todos—.
 * Un solo atacante agotaba el login de todos los centros.
 *
 * <h2>La regla</h2>
 *
 * <ul>
 *   <li>Si la conexion <b>no</b> viene de un proxy declarado en
 *       {@code akine.security.rate-limit.trusted-proxies} ({@code AKINE_TRUSTED_PROXIES}), la IP es la del
 *       socket y {@code X-Forwarded-For} se ignora. Esa cabecera la escribe cualquiera: si se le
 *       creyera siempre, evadir el limite seria cambiar un header por request.</li>
 *   <li>Si viene de un proxy de confianza, se recorre {@code X-Forwarded-For} <b>de derecha a
 *       izquierda</b> —cada proxy agrega al final la IP de quien le hablo— saltando los saltos
 *       que tambien son de confianza, y la IP es <b>la primera que no lo es</b>. Lo que esta a su
 *       izquierda lo escribio el cliente y no vale nada.</li>
 *   <li>Una entrada que no es una IP literal corta el recorrido y se queda con el ultimo salto
 *       confiable: falla del lado seguro (el cupo pasa a ser el de ese proxy) y nunca dispara
 *       una resolucion DNS de algo que mando un desconocido.</li>
 * </ul>
 *
 * <p>Sin proxies declarados —el default— el comportamiento es exactamente el anterior.
 *
 * <h2>Por que no {@code server.forward-headers-strategy=native}</h2>
 *
 * <p>Spring Boot lo resuelve con el {@code RemoteIpValve} de Tomcat, y se evaluo. Se descarto por
 * tres motivos: su {@code internal-proxies} es una <b>expresion regular</b> cuyo default confia en
 * todo el espacio privado (10/8, 172.16/12, 192.168/16, 100.64/10...), asi que activarlo sin
 * reescribirla le cree el {@code X-Forwarded-For} a cualquier cliente de la red interna del
 * despliegue; cambia {@code getRemoteAddr()}, el esquema y {@code isSecure()} de <b>toda</b> la
 * aplicacion, que es mas alcance que el que este defecto pide; y una regex de IPs escrita por un
 * operador a mano es la forma mas facil de equivocarse en silencio. Aca la configuracion son
 * CIDR, vacia por defecto, y la regla se prueba con unitarios.
 */
public final class ResolutorDeIpDeCliente implements IpDeCliente {

	static final String CABECERA = "X-Forwarded-For";

	/** Octeto 0–255: {@code 999.1.1.1} no es un literal, y {@code InetAddress} lo tomaria por un host. */
	private static final String OCTETO = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
	private static final Pattern IPV4 = Pattern.compile(OCTETO + "(\\." + OCTETO + "){3}");
	private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]+");

	private final List<IpAddressMatcher> confiables;

	private ResolutorDeIpDeCliente(List<IpAddressMatcher> confiables) {
		this.confiables = confiables;
	}

	/** Sin proxies de confianza: siempre la IP del socket. */
	public static ResolutorDeIpDeCliente sinProxies() {
		return new ResolutorDeIpDeCliente(List.of());
	}

	/**
	 * Desde la lista declarada: IPs o rangos CIDR, IPv4 o IPv6, separados por coma.
	 *
	 * @throws IllegalArgumentException si una entrada no es una IP literal o un CIDR. Se falla al
	 *                                  arrancar: un proxy mal escrito que se ignorara en silencio
	 *                                  volveria a dejar el limite global sin que nadie lo note
	 */
	public static ResolutorDeIpDeCliente desde(List<String> declarados) {
		if (declarados == null) {
			return sinProxies();
		}
		List<IpAddressMatcher> matchers = new ArrayList<>();
		for (String declarado : declarados) {
			if (declarado == null || declarado.isBlank()) {
				continue;
			}
			String valor = declarado.trim();
			String direccion = valor.contains("/") ? valor.substring(0, valor.indexOf('/')) : valor;
			if (!esIpLiteral(direccion)) {
				throw new IllegalArgumentException(
						"akine.security.rate-limit.trusted-proxies (AKINE_TRUSTED_PROXIES): '" + valor + "' no es una IP ni un CIDR");
			}
			matchers.add(new IpAddressMatcher(valor));
		}
		return new ResolutorDeIpDeCliente(List.copyOf(matchers));
	}

	@Override
	public String resolver(HttpServletRequest request) {
		if (confiables.isEmpty()) {
			return RateLimitFilter.normalizarIp(request.getRemoteAddr());
		}
		Enumeration<String> cabeceras = request.getHeaders(CABECERA);
		return resolver(request.getRemoteAddr(),
				cabeceras == null ? List.of() : Collections.list(cabeceras));
	}

	/**
	 * La regla, sin servlet.
	 *
	 * @param remota    la IP del socket
	 * @param reenviadas cada valor de {@code X-Forwarded-For}, en el orden en que llegaron (una
	 *                  request puede traer la cabecera repetida: se leen como una sola lista)
	 */
	String resolver(String remota, List<String> reenviadas) {
		String actual = RateLimitFilter.normalizarIp(remota);
		if (!esConfiable(actual)) {
			return actual;
		}
		List<String> saltos = new ArrayList<>();
		for (String valor : reenviadas) {
			if (valor != null) {
				saltos.addAll(Arrays.asList(valor.split(",")));
			}
		}
		for (int i = saltos.size() - 1; i >= 0; i--) {
			String salto = RateLimitFilter.normalizarIp(saltos.get(i));
			if (!esIpLiteral(salto)) {
				return actual;
			}
			actual = salto;
			if (!esConfiable(salto)) {
				return salto;
			}
		}
		// Todos los saltos eran de confianza: el cliente es el de mas a la izquierda.
		return actual;
	}

	private boolean esConfiable(String ip) {
		return esIpLiteral(ip) && confiables.stream().anyMatch(matcher -> coincide(matcher, ip));
	}

	private static boolean coincide(IpAddressMatcher matcher, String ip) {
		try {
			return matcher.matches(ip);
		} catch (IllegalArgumentException familiaDistinta) {
			return false;
		}
	}

	/**
	 * Solo IPs literales. Nunca se le pasa a {@code InetAddress} algo que pueda ser un nombre de
	 * host: resolverlo seria una consulta DNS por cada request, dirigida por un desconocido.
	 */
	static boolean esIpLiteral(String valor) {
		if (valor == null || valor.isEmpty()) {
			return false;
		}
		return IPV4.matcher(valor).matches()
				|| (valor.indexOf(':') >= 0 && IPV6.matcher(valor).matches());
	}
}
