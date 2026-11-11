package com.akine.knowledge.application;

import com.akine.knowledge.domain.EstadoLibro;
import com.akine.knowledge.domain.Libro;
import com.akine.knowledge.domain.ProcesamientoAjustes;
import com.akine.knowledge.domain.port.BibliotecaVersionPort;
import com.akine.knowledge.domain.port.LibroRepositoryPort;
import com.akine.platform.spi.archivo.AlmacenDeArchivos;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reproceso por version de la ingesta (M30, consulta v2): un libro LISTO cuyo indice se armo con
 * una version anterior a la vigente se vuelve a procesar en segundo plano, <b>con el mecanismo de
 * reemplazo</b>. Se crea un reemplazo PENDIENTE con el mismo archivo (copiado a una clave propia:
 * si compartieran la clave, borrar el viejo inactivo o un reemplazo fallido borraria el archivo
 * del otro) y el worker lo procesa como cualquier reemplazo. El indice viejo responde hasta que el
 * nuevo queda LISTO; ahi el cierre desactiva el viejo en la misma transaccion.
 *
 * <p>Uno por vuelta: el worker lo llama solo cuando no hay ningun PENDIENTE. Si el reproceso
 * termina en ERROR, el viejo sigue activo y el reemplazo fallido vuelve a la cola sin poner los
 * intentos en cero; con {@link ProcesamientoDeLibroService#MAX_INTENTOS} intentos ya no se elige
 * mas. Un libro con un reemplazo manual (otro archivo) en curso o fallido no se reprocesa.
 *
 * <p>Orden de locks, como el resto de la organizacion: fila de {@code biblioteca_version} (creada
 * aparte), despues el libro con FOR UPDATE. La copia del archivo va antes, fuera de la
 * transaccion; si al final no se usa, se borra.
 */
@Service
public class ReprocesoPorVersionService {

	private static final Logger log = LoggerFactory.getLogger(ReprocesoPorVersionService.class);

	static final String MOTIVO = "version-de-procesamiento";

	private final LibroRepositoryPort libros;
	private final BibliotecaVersionPort version;
	private final AlmacenDeArchivos almacen;
	private final ProcesamientoAjustes ajustes;
	private final AuditTrail auditTrail;
	private final TransactionTemplate transaccion;
	private final TransactionTemplate transaccionLecturaConfirmada;

	public ReprocesoPorVersionService(LibroRepositoryPort libros, BibliotecaVersionPort version,
			AlmacenDeArchivos almacen, ProcesamientoAjustes ajustes, AuditTrail auditTrail,
			PlatformTransactionManager transactionManager) {
		this.libros = libros;
		this.version = version;
		this.almacen = almacen;
		this.ajustes = ajustes;
		this.auditTrail = auditTrail;
		this.transaccion = new TransactionTemplate(transactionManager);
		this.transaccionLecturaConfirmada = new TransactionTemplate(transactionManager);
		this.transaccionLecturaConfirmada.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	/**
	 * Encola el reproceso del libro mas viejo que lo necesita.
	 *
	 * @return {@code false} si no habia ninguno
	 */
	public boolean encolarSiguiente() {
		int vigente = ajustes.versionVigente();
		Optional<Libro> candidato = transaccionLecturaConfirmada.execute(status ->
				libros.aReprocesarPorVersion(vigente, ProcesamientoDeLibroService.MAX_INTENTOS));
		if (candidato == null || candidato.isEmpty()) {
			return false;
		}
		long libroId = candidato.get().getId();
		long orgId = candidato.get().getOrganizationId();
		// La fila-lock se crea aparte: crearla dentro de la transaccion que la bloquea da deadlock.
		transaccion.executeWithoutResult(status -> version.asegurarFila(orgId));

		boolean hayFallido = Boolean.TRUE.equals(transaccionLecturaConfirmada.execute(status ->
				libros.reemplazoDe(libroId).isPresent()));
		String clave = hayFallido ? null : copiarArchivo(candidato.get());
		boolean[] usada = {false};
		try {
			transaccionLecturaConfirmada.executeWithoutResult(status -> {
				version.bloquear(orgId);
				Optional<Libro> releido = libros.bloquearPorId(libroId);
				if (releido.isEmpty() || !aReprocesar(releido.get(), vigente)) {
					return; // cambio desde que se eligio: lo vuelve a mirar la proxima vuelta
				}
				Libro original = releido.get();
				Optional<Libro> reemplazo = libros.reemplazoDe(libroId);
				if (reemplazo.isPresent()) {
					reintentar(original, reemplazo.get());
				}
				else if (clave != null) {
					Libro nuevo = libros.guardarYSincronizar(Libro.reprocesoDe(original, clave, vigente));
					usada[0] = true;
					auditar(nuevo, EstadoLibro.PENDIENTE.name(), original.getId(), vigente);
				}
			});
		} catch (DataIntegrityViolationException | OptimisticLockingFailureException carrera) {
			// Otro nodo encolo el mismo reproceso, o el reemplazo cambio entre la lectura y el guardado.
			log.debug("Biblioteca: el reproceso del libro {} lo tomo otro ({})", libroId,
					carrera.getClass().getSimpleName());
		} finally {
			if (clave != null && !usada[0] && !clave.equals(candidato.get().getStorageKey())) {
				almacen.borrar(LibroService.ESPACIO, clave);
			}
		}
		return true;
	}

	/** El reemplazo fallido vuelve a la cola si es un reproceso y le quedan intentos. */
	private void reintentar(Libro original, Libro reemplazo) {
		if (!reemplazo.esReprocesoDe(original) || reemplazo.getEstado() != EstadoLibro.ERROR
				|| reemplazo.getIntentos() >= ProcesamientoDeLibroService.MAX_INTENTOS) {
			return;
		}
		reemplazo.reintentarReproceso();
		libros.guardarYSincronizar(reemplazo);
		auditar(reemplazo, EstadoLibro.ERROR.name(), original.getId(), reemplazo.getVersionProcesamiento());
	}

	private static boolean aReprocesar(Libro libro, int vigente) {
		return libro.isActivo() && libro.getEstado() == EstadoLibro.LISTO && libro.getReemplazaLibroId() == null
				&& libro.getVersionProcesamiento() < vigente;
	}

	/**
	 * Copia el PDF a una clave nueva. Si el archivo del original ya no esta, el reemplazo apunta a
	 * la misma clave (no hay nada que compartir): termina en ERROR "no se encontro el archivo" y,
	 * agotados los intentos, no se vuelve a elegir.
	 */
	private String copiarArchivo(Libro original) {
		Optional<byte[]> pdf = almacen.leer(LibroService.ESPACIO, original.getStorageKey());
		if (pdf.isEmpty()) {
			log.warn("Biblioteca: el libro {} no tiene archivo; su reproceso va a terminar en ERROR",
					original.getId());
			return original.getStorageKey();
		}
		String clave = UUID.randomUUID().toString().replace("-", "") + ".pdf";
		almacen.guardar(LibroService.ESPACIO, clave, pdf.get());
		return clave;
	}

	private void auditar(Libro libro, String antes, long originalId, int versionDestino) {
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("motivo", MOTIVO);
		detalles.put("reemplazaLibroId", String.valueOf(originalId));
		detalles.put("versionProcesamiento", String.valueOf(versionDestino));
		auditTrail.record(new AuditEntry(libro.getOrganizationId(), null, null, AuditEvents.LIBRO_REPROCESADO,
				LibroService.ENTIDAD, libro.getId(), antes, EstadoLibro.PENDIENTE.name(), detalles, null, null,
				Instant.now()));
	}
}
