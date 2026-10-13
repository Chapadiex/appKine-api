package com.akine.diferidos;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.akine.person.support.CoberturaFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La subida multipart del adjunto ADMINISTRATIVO (M25) de punta a punta: HTTP real, el parser
 * multipart del contenedor, la cadena de seguridad, MySQL y el almacenamiento en disco (cierre de
 * F3, faltante "probar la subida multipart de verdad").
 *
 * <p>{@code AdjuntoControllerTest} usa {@code MockMultipartFile} sobre MockMvc con el servicio
 * mockeado, y {@code AdjuntoConcurrenteIT} llama al servicio sin pasar por HTTP. Ninguno de los
 * dos ejercita el cuerpo {@code multipart/form-data} que manda un navegador ni lee de vuelta los
 * bytes que quedaron en el disco. Este test es el que lo hace.
 */
class AdjuntoMultipartIT extends BaseEscenarioDiferido {

	private static final byte[] UN_PDF =
			("%PDF-1.7\n% adjunto sintetico " + UUID.randomUUID() + "\n%%EOF\n")
					.getBytes(StandardCharsets.US_ASCII);

	private Sesion tenant;
	private long persona;

	@BeforeEach
	void sembrar() {
		tenant = altaCompleta("f3-multipart");
		CoberturaFixtures datos = new CoberturaFixtures(jdbc);
		persona = datos.persona(tenant.organizationId(), "Sintetica", "Mara", "39000222");
	}

	@Test
	@DisplayName("F3-M1 subir por multipart crea el adjunto, repetirlo es idempotente y la descarga devuelve los mismos bytes")
	void subir_repetir_y_descargar() throws Exception {
		Respuesta primera = subir("credencial.pdf", "application/pdf", UN_PDF);
		assertThat(primera.status()).as(primera.body()).isEqualTo(201);
		long adjuntoId = primera.json().get("id").asLong();

		Respuesta segunda = subir("credencial-otra-vez.pdf", "application/pdf", UN_PDF);
		assertThat(segunda.status()).as(segunda.body()).isEqualTo(200);
		assertThat(segunda.json().get("id").asLong()).isEqualTo(adjuntoId);

		Integer filas = jdbc.queryForObject(
				"SELECT COUNT(*) FROM adjunto_administrativo WHERE persona_id = ?", Integer.class, persona);
		assertThat(filas).isEqualTo(1);

		HttpResponse<byte[]> descarga = http.send(
				HttpRequest.newBuilder(uri(rutaAdjuntos() + "/" + adjuntoId + "/contenido"))
						.header("Authorization", "Bearer " + tenant.token())
						.GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());

		assertThat(descarga.statusCode()).isEqualTo(200);
		assertThat(descarga.body()).isEqualTo(UN_PDF);
		assertThat(descarga.headers().firstValue("Content-Type")).hasValueSatisfying(
				tipo -> assertThat(tipo).startsWith("application/pdf"));
		assertThat(descarga.headers().firstValue("Content-Disposition")).hasValueSatisfying(
				d -> assertThat(d).startsWith("attachment").contains("credencial.pdf"));
		assertThat(descarga.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
	}

	@Test
	@DisplayName("F3-M2 el tipo se decide por los bytes: un texto declarado como PDF es 400 archivo-no-aceptado y no deja fila")
	void el_content_type_declarado_no_alcanza() {
		Respuesta respuesta = subir("falso.pdf", "application/pdf",
				"<script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII));

		assertThat(respuesta.status()).as(respuesta.body()).isEqualTo(400);
		assertThat(respuesta.texto("type")).endsWith("archivo-no-aceptado");
		assertProblemaLimpio(respuesta);
		Integer filas = jdbc.queryForObject(
				"SELECT COUNT(*) FROM adjunto_administrativo WHERE persona_id = ?", Integer.class, persona);
		assertThat(filas).isZero();
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private String rutaAdjuntos() {
		return "/api/v1/personas/" + persona + "/adjuntos";
	}

	/** Arma el cuerpo multipart/form-data a mano, como lo manda un navegador. */
	private Respuesta subir(String nombre, String tipoDeclarado, byte[] contenido) {
		String frontera = "----akine" + UUID.randomUUID().toString().replace("-", "");
		ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
		escribir(cuerpo, "--" + frontera + "\r\n"
				+ "Content-Disposition: form-data; name=\"archivo\"; filename=\"" + nombre + "\"\r\n"
				+ "Content-Type: " + tipoDeclarado + "\r\n\r\n");
		cuerpo.writeBytes(contenido);
		escribir(cuerpo, "\r\n--" + frontera + "--\r\n");

		HttpRequest request = HttpRequest.newBuilder(
						uri(rutaAdjuntos() + "?categoria=CREDENCIAL_COBERTURA&titulo=Credencial"))
				.header("Authorization", "Bearer " + tenant.token())
				.header("Content-Type", "multipart/form-data; boundary=" + frontera)
				.POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray()))
				.build();
		return ejecutar(request);
	}

	private static void escribir(ByteArrayOutputStream destino, String texto) {
		destino.writeBytes(texto.getBytes(StandardCharsets.UTF_8));
	}
}
