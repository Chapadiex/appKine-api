package com.akine.clinical.infrastructure;

import com.akine.clinical.infrastructure.config.ContenidoClinicoStorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El adaptador local del almacenamiento CLINICO.
 *
 * <p>Dos casos importan mas que el resto. El primero: <b>una clave que no tiene la forma que este
 * adaptador genera se rechaza</b>, para que el dia que alguien decida que la clave "podria" llevar
 * el nombre del archivo, esto falle antes de que la cadena llegue a {@code Path.resolve}.
 *
 * <p>El segundo: que escriba bajo <b>su</b> raiz y no bajo la de los adjuntos administrativos. La
 * separacion fisica de binarios clinicos es el argumento entero por el que este adaptador existe
 * duplicado en vez de compartirse, y un test que no la mire dejaria pasar el "arreglo" que la
 * borra.
 */
@DisplayName("LocalFileSystemContenidoClinicoStorage")
class LocalFileSystemContenidoClinicoStorageTest {

	private static final String CLAVE = "0123456789abcdef0123456789abcdef";

	@TempDir
	Path baseDir;

	@Test
	@DisplayName("guarda y devuelve el mismo contenido, bajo su propia raiz")
	void ida_y_vuelta() throws Exception {
		LocalFileSystemContenidoClinicoStorage storage = storage();
		byte[] contenido = "%PDF-1.7 sintetico".getBytes(StandardCharsets.US_ASCII);

		storage.guardar(CLAVE, contenido);

		assertThat(storage.leer(CLAVE)).contains(contenido);
		try (var archivos = Files.walk(baseDir)) {
			assertThat(archivos.filter(Files::isRegularFile).toList()).hasSize(1);
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
		LocalFileSystemContenidoClinicoStorage storage = storage();

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

	private LocalFileSystemContenidoClinicoStorage storage() {
		return new LocalFileSystemContenidoClinicoStorage(
				new ContenidoClinicoStorageProperties(baseDir.toString(), 4096L));
	}
}
