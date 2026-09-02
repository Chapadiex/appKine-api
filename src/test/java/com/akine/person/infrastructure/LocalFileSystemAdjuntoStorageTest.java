package com.akine.person.infrastructure;

import com.akine.person.infrastructure.config.AdjuntoStorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El adaptador local de almacenamiento.
 *
 * <p>El caso que importa es el ultimo: <b>una clave que no tiene la forma que este adaptador
 * genera se rechaza</b>. No es que hoy pueda llegar una —la genera el servidor como un UUID— sino
 * que el dia que alguien decida que la clave "podria" llevar el nombre del archivo para debuggear
 * mas comodo, esto falla antes de que la cadena llegue a {@code Path.resolve}.
 */
@DisplayName("LocalFileSystemAdjuntoStorage")
class LocalFileSystemAdjuntoStorageTest {

	private static final String CLAVE = "0123456789abcdef0123456789abcdef";

	@TempDir
	Path baseDir;

	@Test
	@DisplayName("guarda y devuelve el mismo contenido")
	void ida_y_vuelta() {
		LocalFileSystemAdjuntoStorage storage = storage();
		byte[] contenido = "%PDF-1.7 sintetico".getBytes(StandardCharsets.US_ASCII);

		storage.guardar(CLAVE, contenido);

		assertThat(storage.leer(CLAVE)).contains(contenido);
	}

	@Test
	@DisplayName("guardar dos veces la misma clave reemplaza sin dejar temporales")
	void reescribe_sin_dejar_basura() throws Exception {
		LocalFileSystemAdjuntoStorage storage = storage();
		storage.guardar(CLAVE, "uno".getBytes(StandardCharsets.US_ASCII));
		storage.guardar(CLAVE, "dos".getBytes(StandardCharsets.US_ASCII));

		assertThat(storage.leer(CLAVE))
				.contains("dos".getBytes(StandardCharsets.US_ASCII));
		try (var archivos = java.nio.file.Files.walk(baseDir)) {
			assertThat(archivos.filter(java.nio.file.Files::isRegularFile).toList()).hasSize(1);
		}
	}

	@Test
	@DisplayName("una clave sin contenido devuelve vacio, no una excepcion")
	void contenido_ausente_es_vacio() {
		assertThat(storage().leer(CLAVE)).isEqualTo(Optional.empty());
	}

	@Test
	@DisplayName("rechaza cualquier clave que no sea un UUID sin guiones")
	void la_clave_tiene_forma_fija() {
		LocalFileSystemAdjuntoStorage storage = storage();

		for (String invalida : new String[] {
				null, "", "../../etc/passwd", "0123456789ABCDEF0123456789abcdef",
				"0123456789abcdef0123456789abcde", "0123456789abcdef0123456789abcdef.pdf"}) {

			assertThatThrownBy(() -> storage.leer(invalida))
					.as("clave rechazada: %s", invalida)
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	@DisplayName("publica el tope de tamano configurado")
	void publica_el_tope() {
		assertThat(storage().tamanoMaximo()).isEqualTo(4096L);
	}

	private LocalFileSystemAdjuntoStorage storage() {
		return new LocalFileSystemAdjuntoStorage(
				new AdjuntoStorageProperties(baseDir.toString(), 4096L));
	}
}
