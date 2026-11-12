package com.akine.knowledge.application;

import com.akine.knowledge.domain.BibliotecaAjustes;
import com.akine.knowledge.domain.DetectorDeInstrucciones;
import com.akine.knowledge.domain.EstadoLibro;
import com.akine.knowledge.domain.Libro;
import com.akine.knowledge.domain.LibroFragmento;
import com.akine.knowledge.domain.LimpiadorDePaginas;
import com.akine.knowledge.domain.PaginaDeTexto;
import com.akine.knowledge.domain.Pedazo;
import com.akine.knowledge.domain.ProcesamientoAjustes;
import com.akine.knowledge.domain.Troceador;
import com.akine.knowledge.domain.Vectores;
import com.akine.knowledge.domain.exception.DocumentoIlegibleException;
import com.akine.knowledge.domain.port.BibliotecaVersionPort;
import com.akine.knowledge.domain.port.ExtractorDeTexto;
import com.akine.knowledge.domain.port.LibroFragmentoRepositoryPort;
import com.akine.knowledge.domain.port.LibroRepositoryPort;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import com.akine.platform.spi.archivo.AlmacenDeArchivos;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Procesa un libro PENDIENTE: texto, pedazos, deteccion de instrucciones, vectores y el paso a
 * LISTO (M30). Lo dispara el worker; no tiene actor humano (la auditoria va con actor nulo).
 *
 * <h2>Transacciones</h2>
 *
 * <ol>
 *   <li><b>Reclamo</b>: {@code REQUIRES_NEW} en {@code READ_COMMITTED} (en REPEATABLE READ un
 *       segundo reclamo simultaneo vuelve vacio), {@code SKIP LOCKED}, pasa a PROCESANDO con
 *       {@code procesando_desde} (identidad del intento) y {@code latido} = ahora.</li>
 *   <li><b>Trabajo</b>: fuera de toda transaccion. El progreso se mueve con UPDATE dirigidos y
 *       cortos, guardados por estado e intento, que ademas renuevan el latido; si no tocan
 *       fila el libro se borro o se reencolo y se abandona sin gastar mas vectores.</li>
 *   <li><b>Cierre</b>: una transaccion en {@code READ_COMMITTED} que toma primero la fila de
 *       {@code biblioteca_version} (orden de locks de la organizacion) y despues relee el libro
 *       con FOR UPDATE. Si ya no existe, no esta PROCESANDO o el intento cambio, descarta en
 *       silencio. Si sigue: fragmentos, LISTO, cambio de reemplazo y version.</li>
 * </ol>
 *
 * <p>Un PDF ilegible o casi sin texto termina en ERROR con un motivo legible; un reemplazo en
 * ERROR deja al original activo. Un lote de vectores que falla deja esos pedazos sin vector (los
 * recupera el reindexado) y el libro queda LISTO igual. Los pedazos sospechosos se guardan sin
 * vector: no entran en la busqueda semantica y no se gastan embeddings en ellos.
 *
 * <h2>Topes</h2>
 *
 * <p>Cada reclamo suma un intento. Un libro que vuelve a la cola por el barrido (el nodo se cayo,
 * se quedo sin memoria o se colgo) y ya tiene {@link #MAX_INTENTOS} intentos pasa a ERROR en el
 * reclamo siguiente, en vez de reintentarse para siempre; el reproceso manual los pone en cero.
 * Un {@code StackOverflowError} de la extraccion (PDF con estructuras anidadas sin fin) es ERROR
 * "protegido o danado", y mas de {@link #MAX_CARACTERES} caracteres extraidos, ERROR por texto.
 */
@Service
public class ProcesamientoDeLibroService {

	private static final Logger log = LoggerFactory.getLogger(ProcesamientoDeLibroService.class);

	static final int LOTE_VECTORES = 16;
	static final int MAX_INTENTOS = 3;
	static final long MAX_CARACTERES = 20_000_000L;
	static final String MOTIVO_SIN_TEXTO = "El PDF no tiene texto: parece escaneado";
	static final String MOTIVO_SIN_ARCHIVO = "No se encontró el archivo del libro";
	static final String MOTIVO_INESPERADO = "No se pudo procesar el libro";
	static final String MOTIVO_INTENTOS = "No se pudo procesar el libro después de 3 intentos";
	static final String MOTIVO_DANADO = "El PDF está protegido o dañado";
	static final String MOTIVO_DEMASIADO_TEXTO = "El PDF tiene demasiado texto para procesarlo";
	private static final int PROGRESO_EXTRAIDO = 10;
	private static final int PROGRESO_VECTORES = 85;

	/** Lo que el reclamo deja fijado: procesando_desde identifica a este intento. */
	record Reclamo(long libroId, long orgId, String storageKey, Instant desde) {
	}

	private final LibroRepositoryPort libros;
	private final LibroFragmentoRepositoryPort fragmentos;
	private final BibliotecaVersionPort version;
	private final AlmacenDeArchivos almacen;
	private final ExtractorDeTexto extractor;
	private final Troceador troceador;
	private final DetectorDeInstrucciones detector;
	private final ObjectProvider<ModeloEmbeddings> modelos;
	private final BibliotecaAjustes ajustes;
	private final ProcesamientoAjustes procesamientoAjustes;
	private final AuditTrail auditTrail;
	private final TransactionTemplate transaccion;
	private final TransactionTemplate transaccionLecturaConfirmada;
	private final TransactionTemplate transaccionReclamo;

	public ProcesamientoDeLibroService(
			LibroRepositoryPort libros,
			LibroFragmentoRepositoryPort fragmentos,
			BibliotecaVersionPort version,
			AlmacenDeArchivos almacen,
			ExtractorDeTexto extractor,
			Troceador troceador,
			DetectorDeInstrucciones detector,
			ObjectProvider<ModeloEmbeddings> modelos,
			BibliotecaAjustes ajustes,
			ProcesamientoAjustes procesamientoAjustes,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager) {
		this.libros = libros;
		this.fragmentos = fragmentos;
		this.version = version;
		this.almacen = almacen;
		this.extractor = extractor;
		this.troceador = troceador;
		this.detector = detector;
		this.modelos = modelos;
		this.ajustes = ajustes;
		this.procesamientoAjustes = procesamientoAjustes;
		this.auditTrail = auditTrail;
		this.transaccion = new TransactionTemplate(transactionManager);
		this.transaccionLecturaConfirmada = new TransactionTemplate(transactionManager);
		this.transaccionLecturaConfirmada.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.transaccionReclamo = new TransactionTemplate(transactionManager);
		this.transaccionReclamo.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.transaccionReclamo.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** Devuelve a PENDIENTE los PROCESANDO cuyo ultimo latido es anterior a {@code limite}. */
	public int devolverTrabados(Instant limite) {
		Integer devueltos = transaccion.execute(status -> libros.devolverTrabados(limite));
		if (devueltos != null && devueltos > 0) {
			log.info("Biblioteca: {} libros trabados en PROCESANDO volvieron a la cola", devueltos);
		}
		return devueltos == null ? 0 : devueltos;
	}

	/**
	 * Procesa el libro PENDIENTE mas viejo de cualquier organizacion.
	 *
	 * @return {@code false} si no habia ninguno
	 */
	public boolean procesarSiguiente(Instant ahora) {
		// DATETIME(6) redondea: el intento se fija en microsegundos para poder compararlo despues.
		Instant desde = ahora.truncatedTo(ChronoUnit.MICROS);
		boolean[] agotado = {false};
		Reclamo reclamo = transaccionReclamo.execute(status -> libros.reclamarPendiente(desde)
				.map(libro -> {
					if (libro.getIntentos() >= MAX_INTENTOS) {
						agotarIntentos(libro);
						agotado[0] = true;
						return null;
					}
					libro.marcarProcesando(desde);
					libros.guardarYSincronizar(libro);
					return new Reclamo(libro.getId(), libro.getOrganizationId(), libro.getStorageKey(), desde);
				})
				.orElse(null));
		if (reclamo == null) {
			return agotado[0];
		}
		try {
			procesar(reclamo);
		} catch (DocumentoIlegibleException ilegible) {
			fallar(reclamo, ilegible.getMotivoLegible());
		} catch (RuntimeException falla) {
			// Sin el mensaje: podria arrastrar texto del libro. La traza completa, solo en DEBUG.
			log.error("Biblioteca: fallo el procesamiento del libro {} ({})", reclamo.libroId(),
					falla.getClass().getName());
			log.debug("Biblioteca: detalle de la falla del libro {}", reclamo.libroId(), falla);
			fallar(reclamo, MOTIVO_INESPERADO);
		}
		return true;
	}

	/** Dentro del reclamo: el libro volvio a la cola con los intentos agotados. */
	private void agotarIntentos(Libro libro) {
		libro.marcarError(MOTIVO_INTENTOS);
		libros.guardarYSincronizar(libro);
		auditTrail.record(new AuditEntry(libro.getOrganizationId(), null, null, AuditEvents.LIBRO_CON_ERROR,
				LibroService.ENTIDAD, libro.getId(), EstadoLibro.PENDIENTE.name(), EstadoLibro.ERROR.name(),
				Map.of("motivo", MOTIVO_INTENTOS, "intentos", String.valueOf(libro.getIntentos())),
				null, null, Instant.now()));
		log.warn("Biblioteca: el libro {} paso a ERROR tras {} intentos", libro.getId(), libro.getIntentos());
	}

	private void procesar(Reclamo reclamo) {
		byte[] pdf = almacen.leer(LibroService.ESPACIO, reclamo.storageKey())
				.orElseThrow(() -> new DocumentoIlegibleException(MOTIVO_SIN_ARCHIVO));
		List<PaginaDeTexto> paginas;
		try {
			paginas = extractor.extraer(pdf, ajustes.maxPaginas());
		} catch (StackOverflowError recursion) {
			// PDF con estructuras anidadas sin fin: PDFBox recursa hasta agotar la pila.
			throw new DocumentoIlegibleException(MOTIVO_DANADO);
		}
		pdf = null; // el PDF puede pesar 100 MB: que no viva durante los vectores
		long caracteres = 0;
		for (PaginaDeTexto pagina : paginas) {
			caracteres += pagina.texto().length();
		}
		if (caracteres > MAX_CARACTERES) {
			throw new DocumentoIlegibleException(MOTIVO_DEMASIADO_TEXTO);
		}
		if (DetectorDeInstrucciones.casiSinTexto(paginas)) {
			throw new DocumentoIlegibleException(MOTIVO_SIN_TEXTO);
		}
		// Sin relleno (derechos, indice, miembros, referencias), encabezados ni pies repetidos: no
		// entran al indice. Las paginas del libro siguen siendo todas.
		List<Pedazo> pedazos = troceador.trocear(LimpiadorDePaginas.limpiar(paginas));
		if (pedazos.isEmpty()) {
			// Un indice vacio no reemplaza a nada: el libro termina en ERROR y, si es un reemplazo
			// (o un reproceso por version), el original sigue respondiendo.
			throw new DocumentoIlegibleException(MOTIVO_SIN_TEXTO);
		}
		if (!avanzar(reclamo, PROGRESO_EXTRAIDO)) {
			return;
		}
		ModeloEmbeddings modelo = modelos.getIfAvailable();
		if (modelo == null) {
			throw new IllegalStateException("La biblioteca no tiene modelo de embeddings configurado");
		}

		boolean[] sospechoso = new boolean[pedazos.size()];
		List<Integer> aVectorizar = new ArrayList<>();
		for (int i = 0; i < pedazos.size(); i++) {
			sospechoso[i] = detector.esSospechoso(pedazos.get(i).texto());
			if (!sospechoso[i]) {
				aVectorizar.add(i);
			}
		}
		byte[][] vectores = new byte[pedazos.size()][];
		int lotes = (aVectorizar.size() + LOTE_VECTORES - 1) / LOTE_VECTORES;
		for (int lote = 0; lote < lotes; lote++) {
			List<Integer> indices = aVectorizar.subList(lote * LOTE_VECTORES,
					Math.min(aVectorizar.size(), (lote + 1) * LOTE_VECTORES));
			vectorizar(reclamo, modelo, pedazos, indices, vectores);
			if (!avanzar(reclamo, PROGRESO_EXTRAIDO + PROGRESO_VECTORES * (lote + 1) / lotes)) {
				return;
			}
		}

		List<LibroFragmento> lista = new ArrayList<>(pedazos.size());
		for (int i = 0; i < pedazos.size(); i++) {
			Pedazo p = pedazos.get(i);
			lista.add(new LibroFragmento(reclamo.orgId(), reclamo.libroId(), p.orden(), p.paginaDesde(),
					p.paginaHasta(), p.texto(), sospechoso[i], vectores[i],
					vectores[i] == null ? null : modelo.id()));
		}
		cerrar(reclamo, paginas.size(), lista);
	}

	/** Un lote que falla deja sus pedazos sin vector; no frena el libro. */
	private static void vectorizar(Reclamo reclamo, ModeloEmbeddings modelo, List<Pedazo> pedazos,
			List<Integer> indices, byte[][] vectores) {
		try {
			List<float[]> calculados = modelo.pasajes(indices.stream().map(i -> pedazos.get(i).texto()).toList());
			if (calculados.size() != indices.size()) {
				throw new IllegalStateException("El modelo devolvio " + calculados.size()
						+ " vectores para " + indices.size() + " textos");
			}
			for (int k = 0; k < indices.size(); k++) {
				vectores[indices.get(k)] = Vectores.aBytes(calculados.get(k));
			}
		} catch (RuntimeException falla) {
			// Sin el texto: solo el libro y la clase del error.
			log.warn("Biblioteca: fallo un lote de vectores del libro {} ({}); sus pedazos quedan sin vector",
					reclamo.libroId(), falla.getClass().getName());
		}
	}

	/**
	 * Mueve el progreso y el latido (el barrido de trabados mira el latido, asi un libro grande
	 * no se reencola mientras avanza). {@code false}: el libro ya no es de este intento.
	 */
	private boolean avanzar(Reclamo reclamo, int progreso) {
		Integer filas = transaccion.execute(status ->
				libros.actualizarProgreso(reclamo.libroId(), reclamo.desde(), progreso, Instant.now()));
		if (filas == null || filas == 0) {
			log.debug("Biblioteca: el libro {} se borro o se reencolo durante el proceso; se descarta",
					reclamo.libroId());
			return false;
		}
		return true;
	}

	private void cerrar(Reclamo reclamo, int paginas, List<LibroFragmento> lista) {
		// La fila-lock se crea aparte: crearla dentro de la transaccion que la bloquea da deadlock.
		transaccion.executeWithoutResult(status -> version.asegurarFila(reclamo.orgId()));
		String[] archivoABorrar = {null};
		transaccionLecturaConfirmada.executeWithoutResult(status -> {
			version.bloquear(reclamo.orgId());
			Optional<Libro> releido = libros.bloquearPorId(reclamo.libroId());
			if (releido.isEmpty() || releido.get().getEstado() != EstadoLibro.PROCESANDO
					|| !reclamo.desde().equals(releido.get().getProcesandoDesde())) {
				log.debug("Biblioteca: el libro {} cambio antes del cierre; se descarta el resultado",
						reclamo.libroId());
				return;
			}
			Libro libro = releido.get();
			fragmentos.reemplazarDeLibro(libro.getId(), lista);
			Long reemplazaId = libro.getReemplazaLibroId();
			Boolean originalDesactivado = null;
			Optional<Libro> original = Optional.empty();
			Optional<Libro> viejoDeReproceso = Optional.empty();
			if (reemplazaId != null) {
				original = libros.bloquearPorId(reemplazaId);
				// Antes de soltar el vinculo: esReprocesoDe mira reemplaza_libro_id.
				viejoDeReproceso = original.filter(libro::esReprocesoDe);
				originalDesactivado = desactivarOriginal(original, libro);
				libro.soltarReemplazo();
			}
			libro.marcarListo(paginas, procesamientoAjustes.versionVigente());
			libros.guardarYSincronizar(libro);
			// Un reproceso por version (mismo archivo) no deja un duplicado inactivo: el viejo, sus
			// fragmentos y, despues del commit, su archivo se borran. Un reemplazo manual conserva
			// el original inactivo, como siempre.
			viejoDeReproceso.ifPresent(viejo -> {
				fragmentos.reemplazarDeLibro(viejo.getId(), List.of());
				auditar(viejo, AuditEvents.LIBRO_ELIMINADO, EstadoLibro.LISTO.name(), null, Map.of(
						"motivo", ReprocesoPorVersionService.MOTIVO,
						"reemplazadoPorLibroId", String.valueOf(libro.getId())));
				libros.delete(viejo);
				libros.flush();
				if (!viejo.getStorageKey().equals(libro.getStorageKey())) {
					archivoABorrar[0] = viejo.getStorageKey();
				}
			});
			version.avanzar(reclamo.orgId());

			long sospechosos = lista.stream().filter(LibroFragmento::isSospechoso).count();
			long conVector = lista.stream().filter(f -> f.getVector() != null).count();
			Map<String, String> detalles = new LinkedHashMap<>();
			detalles.put("paginas", String.valueOf(paginas));
			detalles.put("fragmentos", String.valueOf(lista.size()));
			detalles.put("fragmentosConVector", String.valueOf(conVector));
			detalles.put("fragmentosSospechosos", String.valueOf(sospechosos));
			auditar(libro, AuditEvents.LIBRO_PROCESADO, EstadoLibro.PROCESANDO.name(),
					EstadoLibro.LISTO.name(), detalles);
			if (reemplazaId != null) {
				auditar(libro, AuditEvents.LIBRO_REEMPLAZO_APLICADO, null, null, Map.of(
						"reemplazaLibroId", String.valueOf(reemplazaId),
						"originalDesactivado", String.valueOf(originalDesactivado),
						"originalEliminado", String.valueOf(viejoDeReproceso.isPresent())));
			}
		});
		if (archivoABorrar[0] != null) {
			almacen.borrar(LibroService.ESPACIO, archivoABorrar[0]);
		}
	}

	/**
	 * Desactiva el original y lo manda a la base ANTES de que el reemplazo suelte su vinculo:
	 * con el mismo hash, {@code uk_libro_hash_activo} chocaria si el nuevo quedara activo
	 * primero. Si el original ya no existe o ya estaba inactivo, no lo toca.
	 *
	 * <p>Antes copia al reemplazo el titulo, autores, edicion y area VIGENTES del original: el
	 * reemplazo los copio al subirse, y el admin pudo editar el original mientras se procesaba
	 * (la fila del reemplazo no se edita mientras esta pendiente: 409 {@code libro-en-proceso}).
	 *
	 * @return si se desactivo
	 */
	private boolean desactivarOriginal(Optional<Libro> releido, Libro reemplazo) {
		releido.ifPresent(viejo -> reemplazo.editarMetadatos(
				viejo.getTitulo(), viejo.getAutores(), viejo.getEdicion(), viejo.getArea()));
		Optional<Libro> original = releido.filter(Libro::isActivo);
		original.ifPresent(viejo -> {
			viejo.desactivar();
			libros.save(viejo);
		});
		libros.flush();
		return original.isPresent();
	}

	private void fallar(Reclamo reclamo, String motivo) {
		String recortado = motivo.length() > 500 ? motivo.substring(0, 500) : motivo;
		transaccion.executeWithoutResult(status -> {
			Instant ahora = Instant.now();
			if (libros.marcarError(reclamo.libroId(), reclamo.desde(), recortado, ahora) == 0) {
				log.debug("Biblioteca: el libro {} cambio antes de marcar el error; se descarta",
						reclamo.libroId());
				return;
			}
			auditTrail.record(new AuditEntry(reclamo.orgId(), null, null, AuditEvents.LIBRO_CON_ERROR,
					LibroService.ENTIDAD, reclamo.libroId(), EstadoLibro.PROCESANDO.name(),
					EstadoLibro.ERROR.name(), Map.of("motivo", recortado), null, null, ahora));
		});
	}

	private void auditar(Libro libro, String evento, String antes, String despues, Map<String, String> detalles) {
		auditTrail.record(new AuditEntry(libro.getOrganizationId(), null, null, evento, LibroService.ENTIDAD,
				libro.getId(), antes, despues, detalles, null, null, Instant.now()));
	}
}
