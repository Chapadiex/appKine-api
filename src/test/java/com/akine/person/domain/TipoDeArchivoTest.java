package com.akine.person.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El detector de tipo real, que es lo que cierra el caso borde "archivo malicioso".
 *
 * <p>Lo que estos casos fijan es que la decision <b>no dependa de nada que el cliente elija</b>:
 * ni la extension, ni el {@code Content-Type} declarado. Si alguna vez esta clase empieza a mirar
 * el nombre del archivo, estos tests lo agarran.
 */
@DisplayName("TipoDeArchivo")
class TipoDeArchivoTest {

	@Test
	@DisplayName("reconoce PDF, PNG y JPEG por su firma binaria")
	void reconoce_los_tres_permitidos() {
		assertThat(TipoDeArchivo.detectar("%PDF-1.7\nalgo".getBytes(StandardCharsets.US_ASCII)))
				.isEqualTo("application/pdf");
		assertThat(TipoDeArchivo.detectar(new byte[] {
				(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00}))
				.isEqualTo("image/png");
		assertThat(TipoDeArchivo.detectar(new byte[] {
				(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00}))
				.isEqualTo("image/jpeg");
	}

	@Test
	@DisplayName("un HTML con nombre de PDF se rechaza: decide el contenido, no el nombre")
	void el_archivo_malicioso_no_pasa() {
		byte[] htmlConScript = "<html><script>alert(1)</script></html>"
				.getBytes(StandardCharsets.UTF_8);

		assertThat(TipoDeArchivo.detectar(htmlConScript)).isNull();
	}

	@Test
	@DisplayName("un contenido vacio o mas corto que la firma no es ningun tipo")
	void vacio_y_truncado_no_son_tipos() {
		assertThat(TipoDeArchivo.detectar(null)).isNull();
		assertThat(TipoDeArchivo.detectar(new byte[0])).isNull();
		// Empieza como un PDF y se corta antes de completar la firma: no alcanza.
		assertThat(TipoDeArchivo.detectar(new byte[] {0x25, 0x50})).isNull();
	}

	@Test
	@DisplayName("declara los tipos permitidos, para poder decirlos en el error")
	void publica_la_lista_blanca() {
		assertThat(TipoDeArchivo.permitidos())
				.contains("application/pdf", "image/png", "image/jpeg");
	}
}
