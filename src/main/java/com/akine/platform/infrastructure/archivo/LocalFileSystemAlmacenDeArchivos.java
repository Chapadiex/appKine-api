package com.akine.platform.infrastructure.archivo;

import com.akine.platform.spi.archivo.AlmacenDeArchivos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Adaptador local de {@link AlmacenDeArchivos}: {@code <base>/<espacio>/ab/cd/<clave>}.
 *
 * <p>Misma tecnica que {@code LocalFileSystemContenidoClinicoStorage}: clave validada (sin
 * separadores ni {@code ..}: un solo punto para la extension), dos niveles de carpeta tomados de
 * los primeros cuatro caracteres de la clave, y escritura a un temporal con {@code ATOMIC_MOVE}.
 */
@Component
public class LocalFileSystemAlmacenDeArchivos implements AlmacenDeArchivos {

	private static final Logger log = LoggerFactory.getLogger(LocalFileSystemAlmacenDeArchivos.class);

	private static final Pattern ESPACIO_VALIDO = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
	/** Cuatro alfanumericos (para las carpetas), resto alfanumerico, guion o guion bajo, extension opcional. */
	private static final Pattern CLAVE_VALIDA =
			Pattern.compile("^[A-Za-z0-9]{4}[A-Za-z0-9_-]{0,59}([.][A-Za-z0-9]{1,8})?$");

	private final Path baseDir;

	public LocalFileSystemAlmacenDeArchivos(ArchivosProperties properties) {
		this.baseDir = Path.of(properties.baseDir()).toAbsolutePath().normalize();
	}

	@Override
	public void guardar(String espacio, String clave, byte[] contenido) {
		Path destino = rutaDe(espacio, clave);
		try {
			Files.createDirectories(destino.getParent());
			Path temporal = Files.createTempFile(destino.getParent(), "arch", ".tmp");
			try {
				Files.write(temporal, contenido);
				Files.move(temporal, destino,
						StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException | RuntimeException falla) {
				Files.deleteIfExists(temporal);
				throw falla;
			}
		} catch (IOException falla) {
			log.error("No se pudo almacenar un archivo del espacio {}", espacio, falla);
			throw new UncheckedIOException("No se pudo almacenar el archivo", falla);
		}
	}

	@Override
	public Optional<byte[]> leer(String espacio, String clave) {
		Path origen = rutaDe(espacio, clave);
		if (!Files.isRegularFile(origen)) {
			log.warn("El almacen no tiene un archivo que la base referencia (espacio {})", espacio);
			return Optional.empty();
		}
		try {
			return Optional.of(Files.readAllBytes(origen));
		} catch (IOException falla) {
			log.error("No se pudo leer un archivo del espacio {}", espacio, falla);
			return Optional.empty();
		}
	}

	@Override
	public void borrar(String espacio, String clave) {
		try {
			Files.deleteIfExists(rutaDe(espacio, clave));
		} catch (IOException falla) {
			log.error("No se pudo borrar un archivo del espacio {}", espacio, falla);
			throw new UncheckedIOException("No se pudo borrar el archivo", falla);
		}
	}

	private Path rutaDe(String espacio, String clave) {
		if (espacio == null || !ESPACIO_VALIDO.matcher(espacio).matches()
				|| clave == null || !CLAVE_VALIDA.matcher(clave).matches()) {
			throw new IllegalArgumentException(
					"El espacio o la clave de almacenamiento no tienen la forma esperada");
		}
		return baseDir
				.resolve(espacio)
				.resolve(clave.substring(0, 2))
				.resolve(clave.substring(2, 4))
				.resolve(clave);
	}
}
