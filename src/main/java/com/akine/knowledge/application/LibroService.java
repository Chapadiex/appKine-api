package com.akine.knowledge.application;

import com.akine.knowledge.domain.BibliotecaAjustes;
import com.akine.knowledge.domain.EstadoLibro;
import com.akine.knowledge.domain.EstadoVisibleLibro;
import com.akine.knowledge.domain.Libro;
import com.akine.knowledge.domain.LibroFragmento;
import com.akine.knowledge.domain.exception.LibroArchivoNoAceptadoException;
import com.akine.knowledge.domain.exception.LibroArchivoNoAceptadoException.Causa;
import com.akine.knowledge.domain.exception.LibroDuplicadoException;
import com.akine.knowledge.domain.exception.LibroEnProcesoException;
import com.akine.knowledge.domain.exception.LibroNoEncontradoException;
import com.akine.knowledge.domain.port.BibliotecaVersionPort;
import com.akine.knowledge.domain.port.LibroFragmentoRepositoryPort;
import com.akine.knowledge.domain.port.LibroRepositoryPort;
import com.akine.platform.spi.archivo.AlmacenDeArchivos;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.listado.OrdenDeListado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ABM de los libros de la biblioteca (M30): alta, reemplazo, reproceso, edicion, activacion,
 * desactivacion y baja.
 *
 * <p>Cada operacion pasa por {@link BibliotecaAcceso#exigir}. El procesamiento (texto, fragmentos,
 * vectores y el paso a LISTO) no es de esta clase: lo hace el worker.
 *
 * <h2>Transacciones</h2>
 *
 * <p>Programaticas, porque una violacion del unique {@code uk_libro_hash_activo} marca la
 * transaccion como rollback-only y hay que atraparla <b>afuera</b> de ella. El alta y el reemplazo
 * van en {@code READ_COMMITTED}: dos altas simultaneas del mismo archivo se serializan en el
 * unique, una gana y la otra recibe 409 {@code libro-duplicado}, nunca 500.
 */
@Service
public class LibroService {

	private static final Logger log = LoggerFactory.getLogger(LibroService.class);

	static final String ESPACIO = "biblioteca";
	static final String ENTIDAD = "Libro";
	private static final byte[] FIRMA_PDF = {0x25, 0x50, 0x44, 0x46, 0x2D};
	private static final int TITULO_MAX = 300;
	private static final int AUTORES_MAX = 500;
	private static final int EDICION_MAX = 100;
	private static final int AREA_MAX = 100;

	/** Campo del contrato a propiedad de la entidad. */
	public static final Map<String, String> ORDEN_DE_LIBROS = Map.of(
			"titulo", "titulo",
			"creadoEn", "createdAt",
			"estado", "estado");

	/** Datos editables de un libro; normaliza espacios y valida largos. */
	public record DatosDeLibro(String titulo, String autores, String edicion, String area) {

		public DatosDeLibro {
			titulo = titulo == null ? "" : titulo.strip();
			if (titulo.isEmpty() || titulo.length() > TITULO_MAX) {
				throw new IllegalArgumentException(
						"El titulo es obligatorio y admite hasta " + TITULO_MAX + " caracteres");
			}
			autores = opcional(autores, AUTORES_MAX, "autores");
			edicion = opcional(edicion, EDICION_MAX, "edicion");
			area = opcional(area, AREA_MAX, "area");
		}

		private static String opcional(String valor, int max, String campo) {
			if (valor == null || valor.isBlank()) {
				return null;
			}
			String limpio = valor.strip();
			if (limpio.length() > max) {
				throw new IllegalArgumentException(
						"El campo " + campo + " admite hasta " + max + " caracteres");
			}
			return limpio;
		}
	}

	/** El archivo recibido. */
	public record ArchivoSubido(String nombre, byte[] contenido) {
	}

	/** Fragmento sospechoso: pagina y primeros 200 caracteres. */
	public record SospechosoView(int paginaDesde, String extracto) {

		static SospechosoView de(LibroFragmento f) {
			String t = f.getTexto();
			return new SospechosoView(f.getPaginaDesde(), t.length() <= 200 ? t : t.substring(0, 200));
		}
	}

	/** Un libro con lo que se calcula al leerlo. */
	public record LibroDetalle(
			LibroView libro, long fragmentos, long fragmentosConVector,
			List<SospechosoView> sospechosos) {
	}

	private record Contenido(String hash) {
	}

	private final BibliotecaAcceso acceso;
	private final BibliotecaAjustes ajustes;
	private final LibroRepositoryPort libros;
	private final LibroFragmentoRepositoryPort fragmentos;
	private final BibliotecaVersionPort version;
	private final AlmacenDeArchivos almacen;
	private final AuditTrail auditTrail;
	private final TransactionTemplate transaccion;
	private final TransactionTemplate transaccionLecturaConfirmada;

	public LibroService(
			BibliotecaAcceso acceso,
			BibliotecaAjustes ajustes,
			LibroRepositoryPort libros,
			LibroFragmentoRepositoryPort fragmentos,
			BibliotecaVersionPort version,
			AlmacenDeArchivos almacen,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager) {
		this.acceso = acceso;
		this.ajustes = ajustes;
		this.libros = libros;
		this.fragmentos = fragmentos;
		this.version = version;
		this.almacen = almacen;
		this.auditTrail = auditTrail;
		this.transaccion = new TransactionTemplate(transactionManager);
		this.transaccionLecturaConfirmada = new TransactionTemplate(transactionManager);
		this.transaccionLecturaConfirmada
				.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	// ---------------------------------------------------------------- lecturas

	/**
	 * @param estado {@code INACTIVO} lista los dados de baja; cualquier otro, los activos en ese
	 *     estado; {@code null}, todos
	 */
	public Page<LibroView> listar(
			OperatingActor actor, long orgId, String q, String area, EstadoVisibleLibro estado,
			Optional<OrdenDeListado> orden, int page, int size) {
		acceso.exigir(actor, orgId);
		Sort sort = orden
				.map(pedido -> pedido.aSort(ORDEN_DE_LIBROS.get(pedido.campo()), "id"))
				.orElse(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		String texto = q == null || q.isBlank() ? null : q.strip();
		String filtroArea = area == null || area.isBlank() ? null : area.strip();
		EstadoLibro filtroEstado = null;
		Boolean filtroActivo = null;
		if (estado == EstadoVisibleLibro.INACTIVO) {
			filtroActivo = false;
		} else if (estado != null) {
			filtroEstado = EstadoLibro.valueOf(estado.name());
			filtroActivo = true;
		}
		EstadoLibro e = filtroEstado;
		Boolean a = filtroActivo;
		return transaccion.execute(status -> libros.buscar(
				orgId, texto, filtroArea, e, a, PageRequest.of(page, size, sort))
				.map(LibroView::de));
	}

	public LibroDetalle detalle(OperatingActor actor, long orgId, long libroId) {
		acceso.exigir(actor, orgId);
		return transaccion.execute(status -> {
			Libro libro = cargar(libroId, orgId);
			return new LibroDetalle(
					LibroView.de(libro),
					fragmentos.contarPorLibro(libroId),
					fragmentos.contarConVectorPorLibro(libroId),
					fragmentos.sospechososDe(libroId).stream().map(SospechosoView::de).toList());
		});
	}

	// ---------------------------------------------------------------- altas

	/** Alta de un libro nuevo, PENDIENTE y activo. */
	public LibroView subir(
			OperatingActor actor, long orgId, DatosDeLibro datos, ArchivoSubido archivo,
			boolean declaracionDerechos) {
		acceso.exigir(actor, orgId);
		exigirDeclaracion(declaracionDerechos);
		Contenido contenido = validar(archivo);
		return guardarNuevo(actor, orgId, datos, archivo, contenido, null, AuditEvents.LIBRO_SUBIDO);
	}

	/**
	 * Sube un archivo nuevo que reemplazara a {@code libroId}. El original sigue vigente hasta
	 * que el reemplazo quede LISTO (eso lo hace el worker). Un reemplazo anterior en ERROR se
	 * elimina antes de crear este.
	 */
	public LibroView reemplazar(
			OperatingActor actor, long orgId, long libroId, ArchivoSubido archivo,
			boolean declaracionDerechos) {
		acceso.exigir(actor, orgId);
		exigirDeclaracion(declaracionDerechos);
		Contenido contenido = validar(archivo);
		return guardarNuevo(actor, orgId, null, archivo, contenido, libroId,
				AuditEvents.LIBRO_REEMPLAZADO);
	}

	/**
	 * Alta y reemplazo comparten el camino: bajo el lock de la organizacion se comprueba que el
	 * hash no este en uso (un reemplazo en curso tiene hash_activo NULL y el unique no lo ve) y
	 * se inserta. uk_libro_hash_activo y uk_libro_reemplaza quedan de respaldo.
	 */
	private LibroView guardarNuevo(
			OperatingActor actor, long orgId, DatosDeLibro datosAlta, ArchivoSubido archivo,
			Contenido contenido, Long reemplazaLibroId, String evento) {
		// Primero el archivo, despues la fila; si la fila falla el archivo se borra.
		String clave = UUID.randomUUID().toString().replace("-", "") + ".pdf";
		almacen.guardar(ESPACIO, clave, archivo.contenido());
		// La fila-lock se crea aparte: crearla dentro de la transaccion que la bloquea da deadlock.
		transaccion.executeWithoutResult(status -> version.asegurarFila(orgId));
		List<String> aBorrar = new ArrayList<>();
		try {
			LibroView creado = transaccionLecturaConfirmada.execute(status -> {
				aBorrar.clear();
				version.bloquear(orgId);
				DatosDeLibro datos = datosAlta;
				if (reemplazaLibroId != null) {
					Libro original = cargar(reemplazaLibroId, orgId);
					if (!original.isActivo()) {
						throw new LibroNoEncontradoException();
					}
					exigirQuieto(original);
					if (libros.reemplazoPendienteDe(reemplazaLibroId).isPresent()) {
						throw new LibroEnProcesoException();
					}
					libros.reemplazoDe(reemplazaLibroId).ifPresent(fallido -> {
						eliminarFila(actor, fallido, "reemplazo-fallido");
						aBorrar.add(fallido.getStorageKey());
					});
					libros.flush();
					datos = new DatosDeLibro(original.getTitulo(), original.getAutores(),
							original.getEdicion(), original.getArea());
				}
				libros.hashEnUso(orgId, contenido.hash(), -1L).ifPresent(existente -> {
					throw new LibroDuplicadoException(existente.getId());
				});
				Libro nuevo = libros.guardarYSincronizar(new Libro(
						orgId, datos.titulo(), datos.autores(), datos.edicion(), datos.area(),
						clave, nombreSeguro(archivo.nombre()), "application/pdf",
						archivo.contenido().length, contenido.hash(), reemplazaLibroId,
						Instant.now(), actor.accountId()));
				auditar(actor, evento, nuevo, null, nuevo.estadoVisible().name(),
						reemplazaLibroId == null ? Map.of()
								: Map.of("reemplazaLibroId", String.valueOf(reemplazaLibroId)));
				return LibroView.de(nuevo);
			});
			aBorrar.forEach(this::borrarArchivo);
			return creado;
		} catch (DataIntegrityViolationException choque) {
			borrarArchivo(clave);
			// Respaldo de la base: otro libro con ese hash, u otro reemplazo del mismo original.
			Optional<Libro> ganador = transaccionLecturaConfirmada
					.execute(status -> libros.hashEnUso(orgId, contenido.hash(), -1L));
			if (ganador != null && ganador.isPresent()) {
				throw new LibroDuplicadoException(ganador.get().getId());
			}
			if (reemplazaLibroId != null) {
				throw new LibroEnProcesoException();
			}
			throw choque;
		} catch (RuntimeException falla) {
			borrarArchivo(clave);
			throw falla;
		}
	}

	// ---------------------------------------------------------------- cambios

	public LibroView editar(
			OperatingActor actor, long orgId, long libroId, DatosDeLibro datos, long versionLeida) {
		acceso.exigir(actor, orgId);
		return transaccion.execute(status -> {
			Libro libro = cargar(libroId, orgId);
			if (libro.getVersion() != versionLeida) {
				throw new OptimisticLockingFailureException(
						"El libro cambio desde que se leyo (version " + libro.getVersion() + ")");
			}
			libro.editarMetadatos(datos.titulo(), datos.autores(), datos.edicion(), datos.area());
			Libro guardado = libros.guardarYSincronizar(libro);
			auditar(actor, AuditEvents.LIBRO_EDITADO, guardado, null, null, Map.of());
			return LibroView.de(guardado);
		});
	}

	/**
	 * Vuelve a procesar el libro: PENDIENTE, progreso 0. Los fragmentos quedan hasta el worker.
	 * Sale de la busqueda mientras tanto, asi que avanza la version. Un reemplazo en ERROR no lo
	 * impide (se deja como esta).
	 */
	public LibroView reprocesar(OperatingActor actor, long orgId, long libroId) {
		acceso.exigir(actor, orgId);
		return transaccion.execute(status -> {
			Libro libro = cargar(libroId, orgId);
			exigirQuieto(libro);
			if (libros.reemplazoPendienteDe(libroId).isPresent()) {
				throw new LibroEnProcesoException();
			}
			String antes = libro.getEstado().name();
			libro.reencolar();
			Libro guardado = libros.guardarYSincronizar(libro);
			version.avanzar(orgId);
			auditar(actor, AuditEvents.LIBRO_REPROCESADO, guardado, antes,
					guardado.getEstado().name(), Map.of());
			return LibroView.de(guardado);
		});
	}

	/** Reactiva un libro. 409 libro-duplicado si el contenido ya esta en uso. */
	public LibroView activar(OperatingActor actor, long orgId, long libroId) {
		acceso.exigir(actor, orgId);
		transaccion.executeWithoutResult(status -> version.asegurarFila(orgId));
		try {
			return transaccionLecturaConfirmada.execute(status -> {
				version.bloquear(orgId);
				Libro libro = cargar(libroId, orgId);
				if (libro.isActivo()) {
					return LibroView.de(libro);
				}
				libros.hashEnUso(orgId, libro.getHashSha256(), libroId).ifPresent(otro -> {
					throw new LibroDuplicadoException(otro.getId());
				});
				libro.activar();
				Libro guardado = libros.guardarYSincronizar(libro);
				version.avanzar(orgId);
				auditar(actor, AuditEvents.LIBRO_ACTIVADO, guardado, "INACTIVO",
						guardado.estadoVisible().name(), Map.of());
				return LibroView.de(guardado);
			});
		} catch (DataIntegrityViolationException choque) {
			Optional<Libro> otro = transaccionLecturaConfirmada.execute(status ->
					libros.hashEnUso(orgId, cargar(libroId, orgId).getHashSha256(), libroId));
			if (otro != null && otro.isPresent()) {
				throw new LibroDuplicadoException(otro.get().getId());
			}
			throw choque;
		}
	}

	public LibroView desactivar(OperatingActor actor, long orgId, long libroId) {
		acceso.exigir(actor, orgId);
		return transaccion.execute(status -> {
			Libro libro = cargar(libroId, orgId);
			if (!libro.isActivo()) {
				return LibroView.de(libro);
			}
			String antes = libro.estadoVisible().name();
			libro.desactivar();
			Libro guardado = libros.guardarYSincronizar(libro);
			version.avanzar(orgId);
			auditar(actor, AuditEvents.LIBRO_DESACTIVADO, guardado, antes, "INACTIVO", Map.of());
			return LibroView.de(guardado);
		});
	}

	/**
	 * Elimina el libro en cualquier estado, junto con su reemplazo (en el estado que sea: apunta
	 * a el con una FK sin accion de borrado) en la misma transaccion; despues borra los archivos.
	 */
	public void eliminar(OperatingActor actor, long orgId, long libroId) {
		acceso.exigir(actor, orgId);
		List<String> claves = transaccion.execute(status -> {
			Libro libro = cargar(libroId, orgId);
			List<String> aBorrar = new ArrayList<>();
			libros.reemplazoDe(libroId).ifPresent(reemplazo -> {
				aBorrar.add(reemplazo.getStorageKey());
				eliminarFila(actor, reemplazo, "reemplazo-pendiente");
			});
			aBorrar.add(libro.getStorageKey());
			eliminarFila(actor, libro, null);
			version.avanzar(orgId);
			return aBorrar;
		});
		claves.forEach(this::borrarArchivo);
	}

	/** Audita (titulo y hash, sin contenido) y borra la fila; los fragmentos cascadean. */
	private void eliminarFila(OperatingActor actor, Libro libro, String motivo) {
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("titulo", libro.getTitulo());
		detalles.put("hashSha256", libro.getHashSha256());
		if (motivo != null) {
			detalles.put("motivo", motivo);
		}
		auditar(actor, AuditEvents.LIBRO_ELIMINADO, libro, libro.estadoVisible().name(), null,
				detalles);
		libros.delete(libro);
	}

	// ---------------------------------------------------------------- privados

	private Libro cargar(long libroId, long orgId) {
		return libros.findByIdAndOrganization(libroId, orgId)
				.orElseThrow(LibroNoEncontradoException::new);
	}

	private static void exigirQuieto(Libro libro) {
		if (libro.getEstado() == EstadoLibro.PENDIENTE || libro.getEstado() == EstadoLibro.PROCESANDO) {
			throw new LibroEnProcesoException();
		}
	}

	private static void exigirDeclaracion(boolean declaracionDerechos) {
		if (!declaracionDerechos) {
			throw new IllegalArgumentException(
					"Hay que declarar que se tienen los derechos para cargar el libro");
		}
	}

	private Contenido validar(ArchivoSubido archivo) {
		byte[] bytes = archivo == null ? null : archivo.contenido();
		if (bytes == null || bytes.length == 0) {
			throw new LibroArchivoNoAceptadoException(Causa.VACIO);
		}
		if (bytes.length > ajustes.maxBytes()) {
			throw new LibroArchivoNoAceptadoException(Causa.MUY_GRANDE);
		}
		if (bytes.length < FIRMA_PDF.length) {
			throw new LibroArchivoNoAceptadoException(Causa.NO_ES_PDF);
		}
		for (int i = 0; i < FIRMA_PDF.length; i++) {
			if (bytes[i] != FIRMA_PDF[i]) {
				throw new LibroArchivoNoAceptadoException(Causa.NO_ES_PDF);
			}
		}
		return new Contenido(sha256(bytes));
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String nombreSeguro(String nombre) {
		String base = nombre == null ? "" : nombre.replace('\\', '/');
		base = base.substring(base.lastIndexOf('/') + 1).strip();
		if (base.isEmpty()) {
			base = "libro.pdf";
		}
		return base.length() <= 255 ? base : base.substring(0, 255);
	}

	private void borrarArchivo(String clave) {
		try {
			almacen.borrar(ESPACIO, clave);
		} catch (RuntimeException falla) {
			log.warn("No se pudo borrar un archivo de la biblioteca; queda huerfano", falla);
		}
	}

	private void auditar(
			OperatingActor actor, String evento, Libro libro, String antes, String despues,
			Map<String, String> detalles) {
		auditTrail.record(new AuditEntry(
				libro.getOrganizationId(), null, actor.accountId(), evento, ENTIDAD, libro.getId(),
				antes, despues, detalles, null, MDC.get("traceId"), Instant.now()));
	}
}
