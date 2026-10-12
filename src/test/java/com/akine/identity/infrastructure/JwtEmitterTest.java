package com.akine.identity.infrastructure;

import com.akine.platform.spi.security.AccessTokenClaims;
import com.akine.platform.spi.security.AccessTokenScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La firma y la verificación del access token, sin Spring.
 *
 * <p>Lo que importa es lo que el verificador RECHAZA: un token que el servidor no emitió —o que
 * emitió y alguien tocó— tiene que dar vacío, nunca una excepción (un 500 en el filtro tumbaría
 * el endpoint) y nunca claims. Los casos "con firma válida y cuerpo malo" se firman acá con el
 * mismo secreto, que es la única forma de llegar a la validación de claims sin pasar por la firma.
 */
class JwtEmitterTest {

	private static final String SECRETO = "secreto-de-prueba-de-al-menos-treinta-y-dos-bytes";
	private static final String HEADER = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");

	private final JwtEmitter emisor = emisor();

	@Test
	@DisplayName("un token emitido con contexto se verifica con sus mismos claims")
	void ida_y_vuelta() {
		var emitido = emisor.issue(8L, AccessTokenScope.CONTEXT, 10L, 20L, "ORG_ADMIN", "fam-1");

		AccessTokenClaims claims = emisor.verify(emitido.value()).orElseThrow();

		assertThat(claims.accountId()).isEqualTo(8L);
		assertThat(claims.organizationId()).isEqualTo(10L);
		assertThat(claims.consultorioId()).isEqualTo(20L);
		assertThat(claims.roleCode()).isEqualTo("ORG_ADMIN");
		assertThat(emitido.expiresInSeconds()).isEqualTo(600L);
		assertThat(emisor.verify(emisor.issue(8L, AccessTokenScope.PRE_CONTEXT, null, null, null, null)
				.value())).isPresent();
	}

	@Test
	@DisplayName("emitir con un alcance incoherente con el contexto es un error de programacion")
	void emision_incoherente() {
		assertThatThrownBy(() -> emisor.issue(8L, null, null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> emisor.issue(8L, AccessTokenScope.CONTEXT, 10L, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> emisor.issue(8L, AccessTokenScope.PRE_CONTEXT, 10L, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("un token tocado, con otro header o que no es base64 se rechaza sin excepcion")
	void forma_invalida() {
		String bueno = emisor.issue(8L, AccessTokenScope.PRE_CONTEXT, null, null, null, null).value();
		String[] partes = bueno.split("\\.");

		assertThat(emisor.verify(null)).isEmpty();
		assertThat(emisor.verify(" ")).isEmpty();
		assertThat(emisor.verify("a.b")).isEmpty();
		assertThat(emisor.verify(b64("{\"alg\":\"none\"}") + "." + partes[1] + "." + partes[2])).isEmpty();
		assertThat(emisor.verify(partes[0] + "." + partes[1] + ".!!no-base64!!")).isEmpty();
		assertThat(emisor.verify(partes[0] + "." + b64("{\"sub\":\"9\"}") + "." + partes[2]))
				.as("cuerpo cambiado con la firma original").isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"no es json",
			"{\"iss\":\"otro\",\"sub\":\"8\",\"jti\":\"j\",\"scope\":\"pre_context\",\"iat\":1,\"exp\":9999999999}",
			"{\"iss\":\"akine\",\"sub\":\"8\",\"scope\":\"pre_context\",\"iat\":1,\"exp\":9999999999}",
			"{\"iss\":\"akine\",\"sub\":\"ocho\",\"jti\":\"j\",\"scope\":\"pre_context\",\"iat\":1,\"exp\":9999999999}",
			"{\"iss\":\"akine\",\"sub\":\"8\",\"jti\":\"j\",\"scope\":\"pre_context\",\"iat\":1,\"exp\":2}",
			"{\"iss\":\"akine\",\"sub\":\"8\",\"jti\":\"j\",\"scope\":\"context\",\"iat\":1,\"exp\":9999999999}",
			"{\"iss\":\"akine\",\"sub\":\"8\",\"jti\":\"j\",\"scope\":\"pre_context\",\"org\":10,\"iat\":1,\"exp\":9999999999}"})
	@DisplayName("con firma valida, un cuerpo sin los claims coherentes no autentica")
	void claims_invalidos_con_firma_valida(String cuerpo) {
		assertThat(emisor.verify(firmado(cuerpo))).isEmpty();
	}

	@Test
	@DisplayName("con firma valida y claims completos, un token forjado aca verifica: la firma es la llave")
	void forjado_con_el_secreto() {
		long exp = Instant.now().plusSeconds(60).getEpochSecond();
		String cuerpo = "{\"iss\":\"akine\",\"sub\":\"8\",\"jti\":\"j\",\"scope\":\"context\","
				+ "\"org\":10,\"loc\":20,\"rol\":null,\"iat\":1,\"exp\":" + exp + "}";

		assertThat(emisor.verify(firmado(cuerpo))).get()
				.extracting(AccessTokenClaims::roleCode).isNull();
	}

	private static String firmado(String cuerpoJson) {
		String cuerpo = HEADER + "." + b64(cuerpoJson);
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRETO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return cuerpo + "." + Base64.getUrlEncoder().withoutPadding()
					.encodeToString(mac.doFinal(cuerpo.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String b64(String texto) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(texto.getBytes(StandardCharsets.UTF_8));
	}

	private static JwtEmitter emisor() {
		JwtEmitter.JwtProperties propiedades = new JwtEmitter.JwtProperties();
		propiedades.setSecret(SECRETO);
		propiedades.setAccessTtl(Duration.ofMinutes(10));
		return new JwtEmitter(propiedades, new MockEnvironment());
	}
}
