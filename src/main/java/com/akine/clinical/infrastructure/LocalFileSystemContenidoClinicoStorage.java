package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.port.ContenidoClinicoStoragePort;
import com.akine.clinical.infrastructure.config.ContenidoClinicoStorageProperties;
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
 * Adaptador local del almacenamiento de contenidos CLINICOS.
 *
 * <h2>Por que este adaptador es una copia de {@code LocalFileSystemAdjuntoStorage} y no el mismo</h2>
 *
 * <p>Se evaluo extraerlo a {@code platform} y compartirlo; el diseño de la etapa (seccion 4) lo
 * rechaza con dos argumentos que conviene tener a mano antes de "arreglarlo":
 *
 * <ul>
 *   <li>{@code person} ya tiene el suyo cerrado y verificado. Moverlo es retrabajo sobre una etapa
 *       cerrada, con riesgo de regresion, a cambio de ahorrar ~120 lineas. Y ArchUnit prohibe que
 *       {@code clinical} lo importe, asi que "reusarlo" significaria publicarlo como contrato
 *       global.</li>
 *   <li>Raices de disco separadas son <b>segregacion fisica de binarios clinicos</b>: otro
 *       volumen, otro cifrado en reposo, otra politica de backup y de retencion. Es un control, no
 *       una consecuencia.</li>
 * </ul>
 *
 * <p><b>La condicion de salida es vinculante: al TERCER consumidor se extrae a {@code platform.spi}
 * y los tres adaptadores pasan a ser configuracion.</b> Dos implementaciones son una decision;
 * tres son un descuido.
 *
 * <h2>Por que la ruta no puede tener un path traversal, y no por un {@code if}</h2>
 *
 * <p>La ruta se compone <b>exclusivamente</b> con la {@code storageKey}, que la genera el servidor
 * como un UUID sin guiones. Ni el nombre del archivo, ni su extension, ni la historia, ni ningun
 * otro dato de origen externo participan de ella. Eso convierte al path traversal en una clase de
 * bug <b>imposible</b>, no en una prevenida: no hay ninguna cadena que un atacante controle y que
 * llegue a {@code Path.resolve}.
 *
 * <p>La validacion de {@link #CLAVE_VALIDA} igual esta, y no es redundante: es un cinturon contra
 * el dia en que alguien decida que la clave "podria" traer el nombre del archivo para debuggear
 * mas facil. Falla ruidosamente en vez de escribir donde no debe.
 *
 * <h2>Dos niveles de subdirectorio</h2>
 *
 * <p>{@code ab/cd/abcdef...}. Un solo directorio con decenas de miles de archivos degrada el
 * listado en varios sistemas de archivos y hace incomodo cualquier diagnostico manual. Los dos
 * niveles salen de los primeros cuatro caracteres del UUID, uniformemente distribuidos.
 *
 * <h2>Escritura atomica</h2>
 *
 * <p>Se escribe a un temporal en el mismo directorio y se mueve con {@code ATOMIC_MOVE}. Sin eso,
 * un proceso que muere a mitad de un informe de 8 MB deja un contenido truncado <b>que responde
 * como si estuviera bien</b>: la fila dice DISPONIBLE, la descarga devuelve 200 y el PDF no abre.
 * Con el movimiento atomico, el archivo o esta entero o no esta, y "no esta" es un estado que la
 * lectura sabe reportar — y que el servicio convierte en 409 y en {@code NO_DISPONIBLE}.
 *
 * <p><b>Lo que este adaptador NO hace, y hay que saberlo:</b> no cifra en reposo, no replica y no
 * borra nada. Lo primero y lo segundo son responsabilidad del volumen sobre el que corra —y son
 * una decision de despliegue, no de codigo—; lo tercero es deliberado: la baja de un adjunto
 * clinico es LOGICA y borrar el binario haria irreversible una operacion que la especificacion
 * quiso reversible (challenge seccion 5).
 */
@Component
public class LocalFileSystemContenidoClinicoStorage implements ContenidoClinicoStoragePort {

	private static final Logger log =
			LoggerFactory.getLogger(LocalFileSystemContenidoClinicoStorage.class);

	/** Exactamente 32 hexadecimales: la forma de un UUID sin guiones y nada mas. */
	private static final Pattern CLAVE_VALIDA = Pattern.compile("^[0-9a-f]{32}$");

	private final Path baseDir;
	private final long maxBytes;

	public LocalFileSystemContenidoClinicoStorage(ContenidoClinicoStorageProperties properties) {
		this.baseDir = Path.of(properties.baseDir()).toAbsolutePath().normalize();
		this.maxBytes = properties.maxBytes();
	}

	@Override
	public void guardar(String storageKey, byte[] contenido) {
		Path destino = rutaDe(storageKey);
		try {
			Files.createDirectories(destino.getParent());
			Path temporal = Files.createTempFile(destino.getParent(), "adjcli", ".tmp");
			try {
				Files.write(temporal, contenido);
				Files.move(temporal, destino,
						StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException | RuntimeException falla) {
				Files.deleteIfExists(temporal);
				throw falla;
			}
		} catch (IOException falla) {
			// La excepcion se deja propagar: la transaccion de negocio revierte y no queda una
			// fila apuntando a un contenido que no existe. Ver la cabecera de V46.
			log.error("No se pudo almacenar el contenido de un adjunto clinico", falla);
			throw new UncheckedIOException(
					"No se pudo almacenar el contenido del adjunto clinico", falla);
		}
	}

	@Override
	public long tamanoMaximo() {
		return maxBytes;
	}

	@Override
	public Optional<byte[]> leer(String storageKey) {
		Path origen = rutaDe(storageKey);
		if (!Files.isRegularFile(origen)) {
			log.warn("El almacenamiento clinico no tiene el contenido de un adjunto que la base "
					+ "referencia");
			return Optional.empty();
		}
		try {
			return Optional.of(Files.readAllBytes(origen));
		} catch (IOException falla) {
			log.error("No se pudo leer el contenido de un adjunto clinico", falla);
			return Optional.empty();
		}
	}

	private Path rutaDe(String storageKey) {
		if (storageKey == null || !CLAVE_VALIDA.matcher(storageKey).matches()) {
			throw new IllegalArgumentException(
					"La clave de almacenamiento no tiene la forma que este adaptador genera");
		}
		return baseDir
				.resolve(storageKey.substring(0, 2))
				.resolve(storageKey.substring(2, 4))
				.resolve(storageKey);
	}
}
