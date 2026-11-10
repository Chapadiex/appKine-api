package com.akine.knowledge.api;

import com.akine.knowledge.api.dto.LibroDatosRequest;
import com.akine.knowledge.api.dto.LibroDetalleResponse;
import com.akine.knowledge.api.dto.LibroPageResponse;
import com.akine.knowledge.api.dto.LibroResponse;
import com.akine.knowledge.application.LibroService;
import com.akine.knowledge.application.LibroService.ArchivoSubido;
import com.akine.knowledge.application.LibroService.DatosDeLibro;
import com.akine.knowledge.domain.EstadoVisibleLibro;
import com.akine.platform.spi.listado.OrdenDeListado;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Biblioteca de libros PDF de la organizacion (M30). Requiere {@code biblioteca:manage} y el
 * interruptor {@code akine.biblioteca.enabled}; apagado, todo responde 409
 * {@code biblioteca-deshabilitada}. Los {@code produces} incluyen {@code application/json} para
 * que los 204 no caigan en 406 con el {@code Accept} del cliente generado.
 */
@RestController
@RequestMapping(
		path = "/api/v1/organizations/{orgId}/biblioteca/libros",
		produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@Tag(name = "Biblioteca", description = "Libros PDF de la organizacion")
public class LibroController {

	private static final int TAMANO_MAXIMO = 100;

	private final LibroService libros;
	private final KnowledgeApiActor apiActor;

	public LibroController(LibroService libros, KnowledgeApiActor apiActor) {
		this.libros = libros;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(operationId = "listarLibros", summary = "Listar los libros de la biblioteca",
			description = "`q` busca en titulo y autores; `estado=INACTIVO` lista los dados de baja. "
					+ "`sort` ordena por titulo, creadoEn o estado.")
	public LibroPageResponse listar(
			@PathVariable long orgId,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String area,
			@RequestParam(required = false) EstadoVisibleLibro estado,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@Parameter(description = OrdenDeListado.DESCRIPCION + "creadoEn descendente.",
					schema = @Schema(allowableValues = {"titulo,asc", "titulo,desc", "creadoEn,asc",
							"creadoEn,desc", "estado,asc", "estado,desc"}))
			@RequestParam(name = OrdenDeListado.PARAMETRO, required = false) String sort) {
		var orden = OrdenDeListado.leer(sort, LibroService.ORDEN_DE_LIBROS.keySet());
		return LibroPageResponse.de(libros.listar(apiActor.current(), orgId, q, area, estado, orden,
				Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO)));
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(operationId = "subirLibro", summary = "Subir un libro PDF",
			description = "El tipo se decide por los bytes. 202: queda PENDIENTE y se procesa aparte. "
					+ "409 libro-duplicado si ya hay un libro activo con el mismo contenido.")
	public ResponseEntity<LibroResponse> subir(
			@PathVariable long orgId,
			@RequestPart("archivo") MultipartFile archivo,
			@RequestParam String titulo,
			@RequestParam(required = false) String autores,
			@RequestParam(required = false) String edicion,
			@RequestParam(required = false) String area,
			@Parameter(description = "Debe ser true: declara que se tienen los derechos")
			@RequestParam boolean declaracionDerechos) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(LibroResponse.de(libros.subir(
				apiActor.current(), orgId, new DatosDeLibro(titulo, autores, edicion, area),
				archivoDe(archivo), declaracionDerechos)));
	}

	@GetMapping("/{id}")
	@Operation(operationId = "verLibro", summary = "Detalle de un libro")
	public LibroDetalleResponse ver(@PathVariable long orgId, @PathVariable long id) {
		return LibroDetalleResponse.de(libros.detalle(apiActor.current(), orgId, id));
	}

	@PutMapping("/{id}")
	@Operation(operationId = "editarLibro", summary = "Editar titulo, autores, edicion y area",
			description = "Con `version`; si cambio, 409 concurrent-modification.")
	public LibroResponse editar(
			@PathVariable long orgId, @PathVariable long id,
			@RequestBody LibroDatosRequest request) {
		return LibroResponse.de(libros.editar(apiActor.current(), orgId, id,
				new DatosDeLibro(request.titulo(), request.autores(), request.edicion(), request.area()),
				request.version()));
	}

	@PostMapping(path = "/{id}/reemplazo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(operationId = "reemplazarLibro", summary = "Subir un archivo que reemplaza al libro",
			description = "Copia los datos del original; devuelve el libro nuevo, PENDIENTE. "
					+ "El original sigue vigente hasta que el nuevo este LISTO.")
	public ResponseEntity<LibroResponse> reemplazar(
			@PathVariable long orgId, @PathVariable long id,
			@RequestPart("archivo") MultipartFile archivo,
			@RequestParam boolean declaracionDerechos) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(LibroResponse.de(libros.reemplazar(
				apiActor.current(), orgId, id, archivoDe(archivo), declaracionDerechos)));
	}

	@PostMapping("/{id}/reproceso")
	@Operation(operationId = "reprocesarLibro", summary = "Volver a procesar un libro")
	public ResponseEntity<LibroResponse> reprocesar(@PathVariable long orgId, @PathVariable long id) {
		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(LibroResponse.de(libros.reprocesar(apiActor.current(), orgId, id)));
	}

	@PostMapping("/{id}/activacion")
	@Operation(operationId = "activarLibro", summary = "Activar un libro dado de baja")
	public LibroResponse activar(@PathVariable long orgId, @PathVariable long id) {
		return LibroResponse.de(libros.activar(apiActor.current(), orgId, id));
	}

	@PostMapping("/{id}/desactivacion")
	@Operation(operationId = "desactivarLibro", summary = "Dar de baja un libro sin borrarlo")
	public LibroResponse desactivar(@PathVariable long orgId, @PathVariable long id) {
		return LibroResponse.de(libros.desactivar(apiActor.current(), orgId, id));
	}

	@DeleteMapping("/{id}")
	@Operation(operationId = "eliminarLibro", summary = "Eliminar un libro y su archivo",
			description = "En cualquier estado; tambien elimina su reemplazo pendiente.")
	public ResponseEntity<Void> eliminar(@PathVariable long orgId, @PathVariable long id) {
		libros.eliminar(apiActor.current(), orgId, id);
		return ResponseEntity.noContent().build();
	}

	private static ArchivoSubido archivoDe(MultipartFile archivo) {
		try {
			return new ArchivoSubido(archivo.getOriginalFilename(), archivo.getBytes());
		} catch (IOException e) {
			throw new UncheckedIOException("No se pudo leer el archivo subido", e);
		}
	}
}
