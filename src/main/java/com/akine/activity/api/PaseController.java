package com.akine.activity.api;

import com.akine.activity.api.dto.PaseResponses;
import com.akine.activity.api.dto.ProductoRequests;
import com.akine.activity.application.AjustarSaldoCommand;
import com.akine.activity.application.AjusteDePaseService;
import com.akine.activity.application.CrearProductoCommand;
import com.akine.activity.application.EditarProductoCommand;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.ProductoView;
import com.akine.activity.application.ResultadoDeVenta;
import com.akine.activity.application.VenderPaseCommand;
import com.akine.activity.application.VentaDePaseService;
import com.akine.activity.domain.TipoProducto;
import com.akine.platform.spi.listado.OrdenDeListado;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
	private static final String P403_AJUSTE = "Sin `pase:adjust` en esa sede, o sin contexto activo";
	/** Tope duro por request del ledger paginado, el mismo de todos los listados de la API. */
	private static final int TAMANO_MAXIMO = 100;
	/**
	 * Tope de pagina (AKINE-09.02): con {@code page} enorme el offset pasaba Integer.MAX_VALUE y
	 * Spring Data respondia 500. Mas alla del tope la pagina sale vacia, como cualquier pagina
	 * posterior a la ultima.
	 */
	private static final int PAGINA_MAXIMA = 100_000;
	private static final String P404 = "La sede, el recurso o la persona no existen, o son de otro tenant";

	private final ProductoService productoService;
	private final VentaDePaseService ventaService;
	private final AjusteDePaseService ajusteService;
	private final ActivityApiActor apiActor;

	public PaseController(
			ProductoService productoService, VentaDePaseService ventaService,
			AjusteDePaseService ajusteService, ActivityApiActor apiActor) {
		this.productoService = productoService;
		this.ventaService = ventaService;
		this.ajusteService = ajusteService;
		this.apiActor = apiActor;
	}

	// =================================================================================
	// Productos (RF-M29-001)
	// =================================================================================

	@PostMapping("/productos")
	@Operation(
			operationId = "crearProducto",
			summary = "Crear un producto de pack o de abono",
			description = """
					Define un pack de creditos sobre una oferta de la sede: cantidad, precio y \
					vigencia en dias. La oferta tiene que declarar `esquemaCobro = POR_PACK`, que es \
					la politica que despues hace que una asistencia consuma credito (RN-M29-005). \
					**No crea saldo a nadie** (CA-M29-001-06).

					Con `tipo = ABONO` (desde 0.109.0, RF-M29-007) define un abono: la oferta tiene \
					que declarar `POR_ABONO`, `creditos` son las asistencias que permite cada periodo \
					y `vigenciaDias` la duracion maxima del periodo, que se elige al vender.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Producto creado"),
			@ApiResponse(responseCode = "400", description = "Datos invalidos",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = P403_PRODUCTO,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = P404,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La oferta esta de baja o no cobra "
					+ "POR_PACK (o POR_ABONO para un abono) (`conflict`), o ya hay un producto vigente "
					+ "con ese nombre (`producto-nombre-en-uso`)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PaseResponses.Producto> crearProducto(
			@PathVariable long consultorioId, @RequestBody @Valid ProductoRequests.Crear request) {

		ProductoView creado = productoService.crear(apiActor.current(), consultorioId,
				new CrearProductoCommand(request.ofertaId(), request.nombre(), request.descripcion(),
						request.creditos(), request.precio(), request.moneda(), request.vigenciaDias(),
						request.vigenciaDesde(), request.vigenciaHasta(), tipo(request.tipo())));
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
						request.vigenciaDesde(), request.vigenciaHasta(),
						// AKINE-09.02: Boolean y no boolean. Con el primitivo, un PUT que omitia el
						// campo —"los ausentes se conservan"— era 400 de cuerpo ilegible (Jackson 3).
						Boolean.TRUE.equals(request.limpiarVigenciaHasta()),
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
					existente con 200 sin duplicar pase, creditos ni deuda (CA-M29-002-06).

					**Abono** (desde 0.109.0, RF-M29-007, RF-M18-010): si el producto es `ABONO`, \
					`periodoDesde` es obligatorio y el pase cubre ese periodo; la obligacion nace con \
					`origen = VENTA_ABONO`. Un periodo, una obligacion: un abono del mismo producto \
					para la misma persona con periodo superpuesto es 409 `abono-periodo-superpuesto`. \
					Renovar es vender el periodo siguiente.

					**Renovacion automatica** (desde 0.116.0): con `renovacionAutomatica = true` en un \
					abono, el sistema vende solo el periodo siguiente —con el precio del producto de ese \
					momento y su propia obligacion— al acercarse el fin de este, a nombre de quien \
					vendio. En un pack es 400.""")
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
					+ "de su ventana, o el periodo del abono ya termino o es mas largo que el producto "
					+ "(`producto-no-vendible`), la ficha de la persona esta de baja (`conflict`), la "
					+ "clave se reuso con otro pedido (`idempotency-key-conflict`) o la persona ya "
					+ "tiene ese abono en un periodo superpuesto (`abono-periodo-superpuesto`)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PaseResponses.ResultadoVenta> venderPase(
			@PathVariable long consultorioId, @RequestBody @Valid ProductoRequests.Vender request) {

		ResultadoDeVenta resultado = ventaService.vender(apiActor.current(), consultorioId,
				new VenderPaseCommand(request.productoId(), request.personaId(), request.idempotencyKey(),
						request.periodoDesde(), request.periodoHasta(),
						Boolean.TRUE.equals(request.renovacionAutomatica())));
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

	@GetMapping("/pases/{paseId}/movimientos")
	@Operation(
			operationId = "listarMovimientosDePase",
			summary = "Ledger de un pase, paginado",
			description = """
					Los movimientos del pase del mas viejo al mas nuevo, de a paginas (RF-M29-009, \
					desde 0.116.0): el orden en que se lee `saldoResultante`. `size` se acota a 100 por \
					request. Para un pase con historia larga reemplaza al ledger entero que trae \
					`verPase`, que no cambia.

					DP-45: `sort` ordena por ocurridoEn, tipo o cantidad. Sin `q`: el ledger de un \
					pase no tiene texto en el que buscar, se lee por tipo y fecha.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Una pagina del ledger"),
			@ApiResponse(responseCode = "400", description = "`sort` fuera del enum",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin `pase:read` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = P404,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PaseResponses.PaginaDeMovimientos listarMovimientosDePase(
			@PathVariable long consultorioId,
			@PathVariable long paseId,
			@Parameter(description = "Numero de pagina, base cero", example = "0")
			@RequestParam(defaultValue = "0") int page,
			@Parameter(description = "Elementos por pagina. Se acota a 100", example = "20")
			@RequestParam(defaultValue = "20") int size,
			@Parameter(description = OrdenDeListado.DESCRIPCION + "ocurridoEn ascendente.",
					schema = @Schema(allowableValues = {"ocurridoEn,asc", "ocurridoEn,desc",
							"tipo,asc", "tipo,desc", "cantidad,asc", "cantidad,desc"}))
			@RequestParam(name = OrdenDeListado.PARAMETRO, required = false) String sort) {

		var orden = OrdenDeListado.leer(sort, VentaDePaseService.ORDEN_DE_MOVIMIENTOS.keySet());
		return PaseResponses.PaginaDeMovimientos.de(ventaService.movimientos(apiActor.current(), consultorioId,
				paseId, orden, Math.min(Math.max(page, 0), PAGINA_MAXIMA),
				Math.min(Math.max(size, 1), TAMANO_MAXIMO)));
	}

	@PutMapping("/pases/{paseId}/renovacion")
	@Operation(
			operationId = "configurarRenovacionDeAbono",
			summary = "Prender o apagar la renovacion automatica de un abono",
			description = """
					Con `activa = true` el sistema vende el periodo siguiente del abono cuando este se \
					acerca a su fin (desde 0.116.0): una venta mas, con el precio del producto de ese \
					momento, su movimiento `COMPRA` y **su propia obligacion** `VENTA_ABONO`, a nombre de \
					quien la prende. **No cobra** (DP-06). El periodo nuevo hereda la renovacion; el \
					viejo queda con `renovacionAutomatica = false` y el nuevo con `renuevaPaseId`. Si el \
					periodo siguiente no se puede vender (producto de baja, persona de baja, periodo ya \
					ocupado), la renovacion se apaga y el motivo queda en `renovacionOmitidaMotivo`.

					Apagarla no anula un periodo ya renovado: para eso, `anularPase` sobre el nuevo. \
					Idempotente: pedir lo que ya esta devuelve 200 sin escribir. Solo abonos.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El abono con la renovacion configurada"),
			@ApiResponse(responseCode = "400", description = "Es un pack, o datos invalidos",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin `pase:manage` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = P404,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Version vieja (`concurrent-modification`), "
					+ "abono anulado (`pase-no-ajustable`, `causa = ESTADO`) o ya renovado "
					+ "(`pase-no-ajustable`, `causa = YA_RENOVADO`: se configura sobre el periodo nuevo)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PaseResponses.Pase configurarRenovacionDeAbono(
			@PathVariable long consultorioId,
			@PathVariable long paseId,
			@RequestBody @Valid ProductoRequests.ConfigurarRenovacion request) {

		return PaseResponses.Pase.de(ventaService.configurarRenovacion(apiActor.current(), consultorioId,
				paseId, request.activa(), request.version()));
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

	// =================================================================================
	// Ajuste y anulacion (RF-M29-005, AKINE-08.07)
	// =================================================================================

	@PostMapping("/pases/{paseId}/ajustes")
	@Operation(
			operationId = "ajustarSaldoDePase",
			summary = "Ajustar a mano el saldo de un pase",
			description = """
					Suma o resta creditos con motivo obligatorio (RF-M29-005). Deja un movimiento \
					`AJUSTE` en el ledger con actor y motivo y lo audita (CA-M29-005-06). **No toca la \
					deuda ni la caja**: un credito no es plata (RN-M29-006).

					El saldo final no puede ser negativo (RN-M29-008) ni superar los creditos \
					comprados, y el pase tiene que estar `ACTIVO` o `AGOTADO` y dentro de su vigencia. \
					`version` es la que el cliente leyo: un consumo que entro en el medio la movio y la \
					respuesta es 409 `concurrent-modification`. Reenviar el mismo ajuste con la misma \
					version devuelve el estado actual sin ajustar dos veces.

					La respuesta trae el ledger **entero**. Desde 0.117.0, con `sinMovimientos=true` \
					trae el pase con `movimientos` vacio y sin leer el ledger: es para la pantalla que \
					ya lo pagina con `listarMovimientosDePase`. Una lista vacia con ese parametro **no** \
					significa que el pase no tenga movimientos. Aditivo: sin el parametro la respuesta \
					es la de siempre.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El pase ajustado, con su ledger (vacio con "
					+ "`sinMovimientos=true`)"),
			@ApiResponse(responseCode = "400", description = "Cantidad cero, sin motivo o datos invalidos",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = P403_AJUSTE,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = P404,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Version vieja "
					+ "(`concurrent-modification`), o pase anulado, vencido o saldo final fuera de "
					+ "rango (`pase-no-ajustable`, con `motivo` en texto y, desde 0.111.0, `causa`: `ESTADO` "
					+ "si esta anulado o vencido, `FUERA_DE_VIGENCIA` si su vigencia ya termino o "
					+ "`FUERA_DE_RANGO` si el saldo final sale de 0..creditosIniciales; aditivo)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PaseResponses.Detalle ajustarSaldoDePase(
			@PathVariable long consultorioId,
			@PathVariable long paseId,
			@RequestBody @Valid ProductoRequests.Ajustar request,
			@Parameter(description = "true: devuelve el pase con `movimientos` vacio, sin el ledger. "
					+ "Aditivo desde 0.117.0", example = "true")
			@RequestParam(name = "sinMovimientos", defaultValue = "false") boolean sinMovimientos) {

		return PaseResponses.Detalle.de(ajusteService.ajustar(apiActor.current(), consultorioId, paseId,
				new AjustarSaldoCommand(request.cantidad(), request.motivo(), request.version()),
				!sinMovimientos));
	}

	@PostMapping("/pases/{paseId}/anulacion")
	@Operation(
			operationId = "anularPase",
			summary = "Anular un pase vendido por error",
			description = """
					Pasa el pase a `ANULADO` con motivo, descarta su saldo con un movimiento \
					`AJUSTE` y **anula la deuda de la venta** en la misma transaccion: la obligacion \
					no se borra, queda `ANULADA` con su motivo. Idempotente: anular uno anulado \
					devuelve 200 sin escribir (`anulado = false`).

					Solo un pase **sin creditos usados** —con consumos, anularlo regalaria las clases \
					tomadas; para descartar el resto se ajusta el saldo— y con una deuda **sin \
					cobros**: con plata imputada primero se anula o reintegra el cobro (RF-M19-010). En \
					los dos casos no se escribe nada.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El pase anulado y su deuda"),
			@ApiResponse(responseCode = "400", description = "Sin motivo o datos invalidos",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = P403_AJUSTE,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = P404,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Version vieja "
					+ "(`concurrent-modification`), creditos usados (`pase-no-ajustable` con `motivo` y, desde "
					+ "0.111.0, `causa = CREDITOS_USADOS`) o deuda con cobros imputados (`pase-con-cobros`, con "
					+ "`obligacionId`, `cobrado` y, desde 0.111.0, `moneda` ISO 4217 de la obligacion). Lo de "
					+ "0.111.0 es aditivo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PaseResponses.ResultadoAnulacion anularPase(
			@PathVariable long consultorioId,
			@PathVariable long paseId,
			@RequestBody @Valid ProductoRequests.Anular request) {

		return PaseResponses.ResultadoAnulacion.de(ajusteService.anular(
				apiActor.current(), consultorioId, paseId, request.motivo(), request.version()));
	}

	/** {@code null} es {@code PACK}, lo que la API aceptaba antes de 0.109.0. */
	private static TipoProducto tipo(String valor) {
		if (valor == null || valor.isBlank()) {
			return TipoProducto.PACK;
		}
		try {
			return TipoProducto.valueOf(valor.strip());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("El tipo de producto es PACK o ABONO");
		}
	}
}
