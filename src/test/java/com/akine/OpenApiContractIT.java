package com.akine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Gate de drift del contrato OpenAPI (AKINE-00.01).
 *
 * <p>El backend es propietario del contrato canonico. La estrategia adoptada es code-first
 * con contrato commiteado:
 *
 * <ul>
 *   <li>springdoc genera la especificacion desde los controllers anotados.</li>
 *   <li>El resultado vive versionado en {@code openapi/akine-api.yaml}.</li>
 *   <li>Este test compara lo generado contra lo commiteado y falla si difieren.</li>
 * </ul>
 *
 * <p>Consecuencia buscada: ningun cambio de API puede entrar sin que el diff del contrato
 * sea visible en el pull request. El frontend genera su cliente desde ese archivo, asi que
 * un contrato desactualizado significa un cliente desactualizado.
 *
 * <p>Para actualizar el contrato despues de cambiar la API:
 *
 * <pre>./mvnw verify -Dakine.contract.update=true</pre>
 *
 * <p>El orden de claves es determinista ({@code springdoc.writer-with-order-by-keys=true}):
 * sin eso, la comparacion daria falsos positivos en cada corrida.
 *
 * <p><b>Requiere Docker corriendo.</b>
 */
// El perfil de desarrollo es OBLIGATORIO aca, y por dos motivos que son el mismo arreglo:
// el secreto de firma ya no tiene default —sin un perfil de desarrollo activo la aplicacion no
// arranca— y la documentacion del contrato solo se publica en desarrollo, asi que sin el este
// test recibiria un 401 al pedir /v3/api-docs.yaml. Ver PerfilesDeEjecucion.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class OpenApiContractIT {

	/** Ruta del contrato canonico, relativa a la raiz del repositorio. */
	private static final Path CONTRATO = Path.of("openapi", "akine-api.yaml");

	private static final String FLAG_ACTUALIZAR = "akine.contract.update";

	private static final String COMANDO_ACTUALIZAR = "./mvnw verify -D" + FLAG_ACTUALIZAR + "=true";

	@Autowired
	private RestTestClient restTestClient;

	@Test
	@DisplayName("El contrato commiteado coincide con el que genera la implementacion")
	void el_contrato_no_tiene_drift() throws IOException {
		String generado = normalizar(descargarContrato());

		if (Boolean.getBoolean(FLAG_ACTUALIZAR)) {
			Files.createDirectories(CONTRATO.getParent());
			Files.writeString(CONTRATO, generado, StandardCharsets.UTF_8);
			System.out.println("[contrato] Regenerado: " + CONTRATO.toAbsolutePath());
			return;
		}

		if (!Files.exists(CONTRATO)) {
			fail("""
					No existe el contrato canonico en %s.

					Generalo con:
					    %s""".formatted(CONTRATO, COMANDO_ACTUALIZAR));
		}

		String commiteado = normalizar(Files.readString(CONTRATO, StandardCharsets.UTF_8));

		assertThat(generado)
				.as("""
						El contrato OpenAPI commiteado quedo desactualizado respecto de la \
						implementacion.

						Regeneralo y revisa el diff antes de commitear:
						    %s

						Si el cambio es incompatible, ademas hay que subir la version mayor de \
						akine.contract.version y coordinar la regeneracion del cliente en \
						appKine-web.""".formatted(COMANDO_ACTUALIZAR))
				.isEqualTo(commiteado);
	}

	@Test
	@DisplayName("El contrato declara su version SemVer y el endpoint tecnico")
	void el_contrato_declara_lo_esperado() {
		String contrato = descargarContrato();

		assertThat(contrato).contains("version:");
		assertThat(contrato).contains("/api/v1/version");
	}

	private String descargarContrato() {
		byte[] cuerpo = restTestClient.get().uri("/v3/api-docs.yaml")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.returnResult()
				.getResponseBody();

		assertThat(cuerpo)
				.as("springdoc debe publicar el contrato en /v3/api-docs.yaml")
				.isNotNull();

		return new String(cuerpo, StandardCharsets.UTF_8);
	}

	/** Normaliza fin de linea y espacios finales: el gate compara contenido, no formato. */
	private static String normalizar(String contenido) {
		return contenido == null ? "" : contenido.replace("\r\n", "\n").strip() + "\n";
	}
}
