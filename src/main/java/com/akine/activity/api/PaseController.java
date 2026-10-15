package com.akine.activity.api;

import com.akine.activity.api.dto.PaseResponses;
import com.akine.activity.api.dto.ProductoRequests;
import com.akine.activity.application.CrearProductoCommand;
import com.akine.activity.application.EditarProductoCommand;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.ProductoView;
import com.akine.activity.application.ResultadoDeVenta;
import com.akine.activity.application.VenderPaseCommand;
import com.akine.activity.application.VentaDePaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Productos de pack y pases de una sede (M29, AKINE-08.06/08.07).
 *
 * <p><b>Vender no es cobrar</b>: la venta devenga una obligacion y nada mas (DP-06). El consumo
 * de creditos no tiene endpoint propio: lo produce el registro de asistencia de 08.03.
 *
 * <p>{@code produces} declara los dos tipos: el cliente generado manda
 * {@code application/problem+json} en el {@code Accept} y sin el segundo la negociacion corta con
 * 406 antes de entrar al metodo.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Pases", description = "Productos de pack, venta de pases y ledger de creditos (M29)")
public class PaseController {

	private static final String P403_PRODUCTO = "Sin `producto:manage` en esa sede, o sin contexto activo";
	private static final String P404 = "La sede, el recurso o la persona no existen, o son de otro tenant";

	private final ProductoService productoService;
	private final VentaDePaseService ventaService;
	private final ActivityApiActor apiActor;

	public PaseController(
			ProductoService productoService, VentaDePaseService ventaService, ActivityApiActor apiActor) {
		this.productoService = productoService;
		this.ventaService = ventaService;
		this.apiActor = apiActor;
	}

	// =================================================================================
	// Productos (RF-M29-001)
	// =================================================================================

	@PostMapping("/productos")
	@Operation(
			operationId = "crearProducto",
			summary = "Crear un producto de pack",
			description = """
					Define un pack de creditos sobre una oferta de la sede: cantidad, precio y \
					vigencia en dias. La oferta tiene que declarar `esquemaCobro = POR_PACK`, que es \
					la politica que despues hace que una asistencia consuma credito (RN-M29-005). \
					**No crea saldo a nadie** (CA-M29-001-06).""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Producto creado"),
			@ApiResponse(responseCode = "400", description = "Datos invalidos",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = P403_PRODUCTO,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = P404,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La oferta esta de baja o no cobra "
					+ "POR_PACK (`conflict`), o ya hay un producto vigente con ese nombre "
					+ "(`producto-nombre-en-uso`)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PaseResponses.Producto> crearProducto(
			@PathVariable long consultorioId, @RequestBody @Valid ProductoRequests.Crear request) {

		ProductoView creado = productoService.crear(apiActor.current(), consultorioId,
				new CrearProductoCommand(request.ofertaId(), request.nombre(), request.descripcion(),
						request.creditos(), request.precio(), request.moneda(), request.vigenciaDias(),
						request.vigenciaDesde(), request.vigenciaHasta()));
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/productos/" + creado.id()))
				.body(PaseResponses.Producto.de(creado));
	}

	@GetMapping("/productos")
	@Operation(
			operationId = "listarProductos",
			summary = "Catalogo de productos de pack de la sede",
			description = "Los vigentes primero. Con `incluirBajas=true` tambien los dados de baja.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Catalogo"),
			@ApiResponse(responseCode = "403", description = "Sin `producto:read` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = P404,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public List<PaseResponses.Producto> listarProductos(
			@PathVariable long consultorioId,
			@RequestParam(name = "incluirBajas", defaultValue = "false") boolean incluirBajas) {

		return productoService.listar(apiActor.current(), consultorioId, incluirBajas).stream()
				.map(PaseResponses.Producto::de)
				.toList();
	}

	@GetMapping("/productos/{productoId}")
	@Operation(operationId = "verProducto", summary = "Ver un producto de pack")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El producto"),
			@ApiResponse(responseCode = "403", description = "Sin `producto:read` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = P404,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PaseResponses.Producto verProducto(@PathVariable long consultorioId, @PathVariable long productoId) {
		return PaseResponses.Producto.de(productoService.find(apiActor.current(), consultorioId, productoId));
	}

	@PutMapping("/productos/{productoId}")
	@Operation(
			operationId = "editarProducto",
			summary = "Editar un producto de pack",
			description = """
					Los campos ausentes se conservan. **Los pases ya vendidos no cambian**: llevan \
					su propio snapshot. La oferta y el tipo no se editan.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Producto editado"),
			@ApiResponse(responseCode = "400", description = "Datos invalidos",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = P403_PRODUCTO,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = P404,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Version vieja "
					+ "(`concurrent-modification`), producto dado de baja (`conflict`) o nombre en "
					+ "uso (`producto-nombre-en-uso`)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PaseResponses.Producto editarProducto(
			@PathVariable long consultorioId,
			@PathVariable long productoId,
			@RequestBody @Valid ProductoRequests.Editar request) {

		return PaseResponses.Producto.de(productoService.editar(apiActor.current(), consultorioId, productoId,
				new EditarProductoCommand(request.nombre(), request.descripcion(), request.creditos(),
						request.precio(), request.moneda(), request.vigenciaDias(),
						request.vigenciaDesde(), request.vigenciaHasta(), request.limpiarVigenciaHasta(),
						request.version())));
	}

	@PostMapping("/productos/{productoId}/baja")
	@Operation(
			operationId = "darDeBajaProducto",
			summary = "Dar de baja un producto de pack",
			description = """
					Baja logica con motivo: el producto no se vende mas. **No cascadea**: los pases \
					vendidos conservan saldo y vigencia, y sus clases los siguen consumiendo. \
					Idempotente.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Producto dado de baja"),
			@ApiResponse(responseCode = "400", description = "Falta el motivo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = P403_PRODUCTO,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = P404,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PaseResponses.Producto darDeBajaProducto(
			@PathVariable long consultorioId,
			@PathVariable long productoId,
			@RequestBody @Valid ProductoRequests.Baja request) {

		return PaseResponses.Producto.de(
				productoService.darDeBaja(apiActor.current(), consultorioId, productoId, request.motivo()));
	}

	// =================================================================================
	// Pases (RF-M29-002, RF-M29-009)
	// =================================================================================

	@PostMapping("/pases")
	@Operation(
			operationId = "venderPase",
			summary = "Vender un pase a una persona",
			description = """
					Crea el pase con el snapshot del producto y su saldo inicial, el movimiento \
					`COMPRA` del ledger y **una obligacion** a nombre de la persona, en una sola \
					transaccion (RF-M29-002, RF-M18-009). **No cobra** (DP-06): la deuda se paga con \
					`POST /cobros`. La persona no se convierte en paciente.

					Con `idempotencyKey`, un reintento con el mismo pedido devuelve la venta \
					existente con 200 sin duplicar pase, creditos ni deuda (CA-M29-002-06).""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Pase vendido"),
			@ApiResponse(responseCode = "200", description = "Reintento con la misma clave y el "
					+ "mismo pedido: se devuelve la venta ya hecha"),
			@ApiResponse(responseCode = "400", description = "Datos invalidos",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin `pase:manage` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = P404,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "El producto esta dado de baja o fuera "
					+ "de su ventana (`producto-no-vendible`), la ficha de la persona esta de baja "
					+ "(`conflict`), o la clave se reuso con otro pedido (`idempotency-key-conflict`)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PaseResponses.ResultadoVenta> venderPase(
			@PathVariable long consultorioId, @RequestBody @Valid ProductoRequests.Vender request) {

		ResultadoDeVenta resultado = ventaService.vender(apiActor.current(), consultorioId,
				new VenderPaseCommand(request.productoId(), request.personaId(), request.idempotencyKey()));
		PaseResponses.ResultadoVenta cuerpo = PaseResponses.ResultadoVenta.de(resultado);
		if (!resultado.creada()) {
			return ResponseEntity.ok(cuerpo);
		}
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/pases/" + resultado.pase().id()))
				.body(cuerpo);
	}

	@GetMapping("/pases/{paseId}")
	@Operation(
			operationId = "verPase",
			summary = "Ver un pase con su ledger",
			description = "Saldo, vigencia y todos los movimientos, del mas viejo al mas nuevo (RF-M29-009).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El pase y su ledger"),
			@ApiResponse(responseCode = "403", description = "Sin `pase:read` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = P404,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PaseResponses.Detalle verPase(@PathVariable long consultorioId, @PathVariable long paseId) {
		return PaseResponses.Detalle.de(ventaService.find(apiActor.current(), consultorioId, paseId));
	}

	@GetMapping("/personas/{personaId}/pases")
	@Operation(
			operationId = "listarPasesDePersona",
			summary = "Pases de una persona en la sede",
			description = "Los que compro en esta sede, el que vence mas tarde primero. Una persona "
					+ "de otro tenant es 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Los pases"),
			@ApiResponse(responseCode = "403", description = "Sin `pase:read` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = P404,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public List<PaseResponses.Pase> listarPasesDePersona(
			@PathVariable long consultorioId, @PathVariable long personaId) {

		return ventaService.deLaPersona(apiActor.current(), consultorioId, personaId).stream()
				.map(PaseResponses.Pase::de)
				.toList();
	}
}
