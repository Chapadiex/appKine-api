package com.akine.identity.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.akine.platform.spi.config.PerfilesDeEjecucion;
import com.akine.platform.spi.security.AccessTokenClaims;
import com.akine.platform.spi.security.AccessTokenIssuer;
import com.akine.platform.spi.security.AccessTokenScope;
import com.akine.platform.spi.security.AccessTokenVerifier;
import com.akine.platform.spi.security.IssuedAccessToken;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Emisor y verificador del access token. HS256, sin {@code kid}.
 *
 * <h2>Por que HS256 y no RS256</h2>
 * <p>Quien firma y quien verifica son el mismo proceso. Un par asimetrico paga cuando hay un
 * verificador externo que no debe poder emitir; aca no lo hay, y el costo seria un par de
 * claves que gestionar y rotar sin ningun beneficio. La migracion, si algun dia aparece un
 * verificador externo, es un cambio de este archivo y de la configuracion: por eso tampoco hay
 * claim {@code kid}, que solo sirve para elegir entre varias claves que hoy no existen.
 *
 * <h2>Por que esta escrito a mano y no con una libreria</h2>
 * <p>El proyecto no tiene ninguna libreria de JWT entre sus dependencias, y agregarla implica
 * tocar {@code pom.xml}. Lo que hace falta es un subconjunto muy chico y muy estable de la
 * especificacion —un header fijo, HMAC-SHA256, base64url sin relleno— y esta implementado
 * cerrando las dos puertas por las que se cuelan los agujeros clasicos de JWT:
 * <ul>
 *   <li><b>{@code alg} no se negocia.</b> El header del token entrante se compara contra el
 *       header que emitimos nosotros, byte a byte. Un token con {@code "alg":"none"} o con
 *       {@code "alg":"RS256"} no llega siquiera a la verificacion de firma. Ese es el bug que
 *       ha roto a la mitad de las implementaciones de JWT de la historia.</li>
 *   <li><b>La firma se compara en tiempo constante</b> con {@link MessageDigest#isEqual}. Una
 *       comparacion con {@code equals} filtra, byte a byte, cuanto acerto el atacante.</li>
 * </ul>
 *
 * <h2>El secreto</h2>
 * <p>Minimo 32 bytes —256 bits, el largo del bloque de HMAC-SHA256— y <b>sin default</b>: sin
 * secreto configurado la aplicacion no arranca (ADR-0017). El valor de desarrollo existe, es
 * publico y vive unicamente en {@code application-local.yml}; presentarlo sin un perfil de
 * desarrollo activo tambien impide el arranque.
 *
 * <p><b>Por que la regla esta al derecho.</b> Antes el default estaba en {@code application.yml}
 * y el unico guardarrail era que el perfil se llamara {@code prod}, {@code produccion} o
 * {@code production}. Cualquier despliegue con otro nombre —{@code staging}, {@code docker},
 * {@code prd}, {@code k8s}— o sin perfil arrancaba firmando con una clave publica, y con esa
 * clave se forja a mano un token con {@code scope:"context"} y el {@code accountId} de un
 * profesional real: el {@code TenantContextFilter} lo valida contra la base, la membership
 * existe, y entra a la historia clinica sin credenciales. Con {@code "rol":"PLATFORM_ADMIN"} ni
 * siquiera consulta la base. Ver {@link PerfilesDeEjecucion}.
 *
 * <h2>Ventana de revocacion (D-3)</h2>
 * <p>No hay lista de revocacion por {@code jti}. Una cuenta bloqueada conserva su access token
 * criptograficamente valido hasta que vence: {@link JwtProperties#getAccessTtl()}, diez
 * minutos. Se acepta a cambio de no consultar la base en cada verificacion de firma.
 * <b>Para permisos y contexto la ventana es cero</b>, porque {@code TenantContextFilter}
 * revalida la membership contra la base en cada request.
 */
@Component
public class JwtEmitter implements AccessTokenIssuer, AccessTokenVerifier {

	private static final Logger log = LoggerFactory.getLogger(JwtEmitter.class);

	/** Emisor. Un token de otro sistema con nuestra misma clave no seria aceptado igual. */
	static final String ISSUER = "akine";

	/**
	 * Header fijo, ya serializado. Es la defensa contra la confusion de algoritmo: no se
	 * parsea el header del token entrante para decidir como verificarlo, se exige que sea
	 * exactamente este.
	 */
	private static final String HEADER_B64 = base64Url(
			"{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

	private static final String HMAC_SHA256 = "HmacSHA256";

	/** Largo minimo del secreto en bytes. Por debajo de esto HMAC-SHA256 pierde fuerza. */
	static final int MINIMO_BYTES_DE_SECRETO = 32;

	/**
	 * Secreto de desarrollo. Esta a la vista de todos a proposito: no es un secreto, es un
	 * marcador publico que permite trabajar en local sin configurar nada.
	 *
	 * <p><b>Ya no es el default de la configuracion.</b> Vive unicamente en
	 * {@code application-local.yml}, o sea que solo existe si hay un perfil de desarrollo activo.
	 * La comprobacion de abajo es la segunda barrera, por si alguien lo copia a otro lado.
	 */
	static final String SECRETO_DE_DESARROLLO =
			"akine-desarrollo-secreto-de-firma-no-usar-en-produccion";

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final byte[] secreto;
	private final Duration accessTtl;

	/**
	 * @param environment se usa solo para saber si hay un perfil de produccion activo. No se
	 *                    leen propiedades por aca: eso es trabajo de {@link JwtProperties}
	 */
	public JwtEmitter(JwtProperties properties, Environment environment) {
		String valor = properties.getSecret();
		boolean desarrollo = PerfilesDeEjecucion.esDesarrollo(environment);

		if (valor == null || valor.isBlank()) {
			// SIN SECRETO NO SE ARRANCA. Es el default y no admite excepcion: un arranque
			// fallido con este mensaje es infinitamente mas barato que un despliegue firmando
			// con una clave que nadie eligio.
			throw new IllegalStateException(
					"No hay secreto de firma para el access token. Configurar AKINE_JWT_SECRET "
							+ "(propiedad akine.security.jwt.secret) con al menos "
							+ MINIMO_BYTES_DE_SECRETO + " bytes. No hay valor por defecto fuera "
							+ "de los perfiles de desarrollo "
							+ PerfilesDeEjecucion.perfilesDeDesarrollo() + ".");
		}

		byte[] material = valor.getBytes(StandardCharsets.UTF_8);
		if (material.length < MINIMO_BYTES_DE_SECRETO) {
			throw new IllegalStateException(
					"El secreto de firma del access token debe tener al menos "
							+ MINIMO_BYTES_DE_SECRETO + " bytes. Configurar "
							+ "akine.security.jwt.secret (variable de entorno AKINE_JWT_SECRET).");
		}

		if (SECRETO_DE_DESARROLLO.equals(valor)) {
			if (!desarrollo) {
				throw new IllegalStateException(
						"Se esta usando el secreto de firma de DESARROLLO, que esta versionado "
								+ "en el repositorio y por lo tanto es publico, sin ningun perfil "
								+ "de desarrollo activo " + PerfilesDeEjecucion.perfilesDeDesarrollo()
								+ ". Configurar AKINE_JWT_SECRET con al menos "
								+ MINIMO_BYTES_DE_SECRETO + " bytes de material aleatorio.");
			}
			log.warn("Access token firmado con el secreto de DESARROLLO. Es publico: cualquiera "
					+ "que lea el repositorio puede emitir tokens. Solo para entorno local.");
		}

		this.secreto = material;
		this.accessTtl = properties.getAccessTtl();
	}

	@Override
	public IssuedAccessToken issue(
			long accountId,
			AccessTokenScope scope,
			Long organizationId,
			Long consultorioId,
			String roleCode,
			String familyId) {

		if (scope == null) {
			throw new IllegalArgumentException("El alcance del token es obligatorio");
		}
		boolean conContexto = scope == AccessTokenScope.CONTEXT;
		if (conContexto && (organizationId == null || consultorioId == null)) {
			// Un token que dice tener contexto pero no lo lleva completo pasaria la
			// autenticacion y despues no habria con que acotar ninguna consulta.
			throw new IllegalArgumentException(
					"Un token de alcance context exige organizacion y consultorio");
		}
		if (!conContexto && (organizationId != null || consultorioId != null)) {
			throw new IllegalArgumentException(
					"Un token pre-contexto no puede llevar organizacion ni consultorio");
		}

		Instant emitidoEn = Instant.now();
		Instant venceEn = emitidoEn.plus(accessTtl);
		String tokenId = UUID.randomUUID().toString();

		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("iss", ISSUER);
		claims.put("sub", Long.toString(accountId));
		claims.put("jti", tokenId);
		claims.put("iat", emitidoEn.getEpochSecond());
		claims.put("exp", venceEn.getEpochSecond());
		claims.put("scope", scope.claimValue());
		if (conContexto) {
			claims.put("org", organizationId);
			claims.put("loc", consultorioId);
			if (roleCode != null) {
				claims.put("rol", roleCode);
			}
		}
		if (familyId != null) {
			claims.put("fam", familyId);
		}

		String cuerpo = HEADER_B64 + "." + base64Url(JSON.writeValueAsBytes(claims));
		String compacto = cuerpo + "." + base64Url(firmar(cuerpo));

		AccessTokenClaims resultado = new AccessTokenClaims(
				accountId, tokenId, scope,
				conContexto ? organizationId : null,
				conContexto ? consultorioId : null,
				conContexto ? roleCode : null,
				familyId, emitidoEn, venceEn);

		return new IssuedAccessToken(compacto, resultado, accessTtl.toSeconds());
	}

	@Override
	public Optional<AccessTokenClaims> verify(String compactToken) {
		if (compactToken == null || compactToken.isBlank()) {
			return Optional.empty();
		}

		String[] partes = compactToken.split("\\.");
		if (partes.length != 3) {
			return Optional.empty();
		}

		// El header no se interpreta: se exige. Asi no hay forma de que el token elija con que
		// algoritmo se lo verifica, que es la vulnerabilidad clasica de JWT.
		if (!HEADER_B64.equals(partes[0])) {
			log.debug("Access token rechazado: header no soportado");
			return Optional.empty();
		}

		byte[] firmaPresentada;
		byte[] cuerpoJson;
		try {
			firmaPresentada = Base64.getUrlDecoder().decode(partes[2]);
			cuerpoJson = Base64.getUrlDecoder().decode(partes[1]);
		} catch (IllegalArgumentException noEsBase64) {
			return Optional.empty();
		}

		byte[] firmaEsperada = firmar(partes[0] + "." + partes[1]);
		if (!MessageDigest.isEqual(firmaEsperada, firmaPresentada)) {
			log.debug("Access token rechazado: firma invalida");
			return Optional.empty();
		}

		try {
			return interpretar(JSON.readTree(cuerpoJson));
		} catch (RuntimeException jsonInvalido) {
			// Firma valida y cuerpo ilegible no deberia ocurrir nunca —lo emitimos nosotros—,
			// pero un 500 aca convertiria un token raro en una caida del endpoint.
			log.warn("Access token con firma valida y cuerpo ilegible");
			return Optional.empty();
		}
	}

	private Optional<AccessTokenClaims> interpretar(JsonNode nodo) {
		if (!ISSUER.equals(texto(nodo, "iss"))) {
			return Optional.empty();
		}

		AccessTokenScope scope = AccessTokenScope.fromClaim(texto(nodo, "scope"));
		String sub = texto(nodo, "sub");
		String jti = texto(nodo, "jti");
		if (scope == null || sub == null || jti == null
				|| !nodo.has("exp") || !nodo.has("iat")) {
			return Optional.empty();
		}

		long accountId;
		try {
			accountId = Long.parseLong(sub);
		} catch (NumberFormatException noEsUnId) {
			return Optional.empty();
		}

		Instant emitidoEn = Instant.ofEpochSecond(nodo.get("iat").asLong());
		Instant venceEn = Instant.ofEpochSecond(nodo.get("exp").asLong());
		if (!Instant.now().isBefore(venceEn)) {
			log.debug("Access token rechazado: vencido");
			return Optional.empty();
		}

		Long organizationId = numero(nodo, "org");
		Long consultorioId = numero(nodo, "loc");
		if (scope == AccessTokenScope.CONTEXT && (organizationId == null || consultorioId == null)) {
			return Optional.empty();
		}
		if (scope == AccessTokenScope.PRE_CONTEXT && (organizationId != null || consultorioId != null)) {
			return Optional.empty();
		}

		return Optional.of(new AccessTokenClaims(
				accountId, jti, scope, organizationId, consultorioId,
				texto(nodo, "rol"), texto(nodo, "fam"), emitidoEn, venceEn));
	}

	private static String texto(JsonNode nodo, String campo) {
		JsonNode valor = nodo.get(campo);
		return valor == null || valor.isNull() ? null : valor.asString();
	}

	private static Long numero(JsonNode nodo, String campo) {
		JsonNode valor = nodo.get(campo);
		return valor == null || valor.isNull() || !valor.isNumber() ? null : valor.asLong();
	}

	private byte[] firmar(String cuerpo) {
		try {
			Mac mac = Mac.getInstance(HMAC_SHA256);
			mac.init(new SecretKeySpec(secreto, HMAC_SHA256));
			return mac.doFinal(cuerpo.getBytes(StandardCharsets.UTF_8));
		} catch (java.security.GeneralSecurityException imposible) {
			// HmacSHA256 es obligatorio en toda JVM y la clave ya fue validada en el
			// constructor: si esto ocurre, el proceso esta roto y no hay respuesta util.
			throw new IllegalStateException("No se pudo firmar el access token", imposible);
		}
	}

	private static String base64Url(byte[] datos) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(datos);
	}

	/**
	 * Configuracion de firma y vigencia del access token, bajo {@code akine.security.jwt}.
	 *
	 * <pre>
	 * akine:
	 *   security:
	 *     jwt:
	 *       secret: ${AKINE_JWT_SECRET:akine-desarrollo-secreto-de-firma-no-usar-en-produccion}
	 *       access-ttl: 10m
	 * </pre>
	 *
	 * <p>Se anota con {@code @Component} y no se registra en una clase de configuracion porque
	 * el unico consumidor es {@link JwtEmitter}: acoplarla al cableado del modulo obligaria a
	 * tocar un archivo que no le pertenece a esta pieza.
	 */
	@Component
	@ConfigurationProperties(prefix = "akine.security.jwt")
	public static class JwtProperties {

		/**
		 * Secreto simetrico de firma. Minimo 32 bytes.
		 *
		 * <p><b>Sin default a proposito.</b> Que este campo nazca vacio es lo que hace que la
		 * aplicacion no arranque cuando nadie configuro {@code AKINE_JWT_SECRET}. El valor de
		 * desarrollo lo aporta {@code application-local.yml}, que solo se lee con un perfil de
		 * desarrollo activo.
		 */
		private String secret;

		/**
		 * Vigencia del access token. Diez minutos.
		 *
		 * <p>Es tambien la ventana durante la cual una cuenta recien bloqueada conserva acceso
		 * (D-3). Subirlo agranda esa ventana; bajarlo multiplica los refresh.
		 */
		private Duration accessTtl = Duration.ofMinutes(10);

		public String getSecret() {
			return secret;
		}

		public void setSecret(String secret) {
			this.secret = secret;
		}

		public Duration getAccessTtl() {
			return accessTtl;
		}

		public void setAccessTtl(Duration accessTtl) {
			this.accessTtl = accessTtl;
		}
	}
}
