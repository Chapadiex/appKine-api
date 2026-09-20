package com.akine.billing.api;

import com.akine.billing.api.dto.AgregarItemRequest;
import com.akine.billing.api.dto.AnularPresentacionRequest;
import com.akine.billing.api.dto.CrearPresentacionRequest;
import com.akine.billing.api.dto.CuentaCorrienteFinanciadorResponse;
import com.akine.billing.api.dto.FinanciadorPagoResponse;
import com.akine.billing.api.dto.ObligacionResponse;
import com.akine.billing.api.dto.PresentacionItemResponse;
import com.akine.billing.api.dto.PresentacionResponse;
import com.akine.billing.api.dto.RegistrarDebitoRequest;
import com.akine.billing.api.dto.RegistrarFacturaRequest;
import com.akine.billing.api.dto.RegistrarPagoFinanciadorRequest;
import com.akine.billing.api.dto.ValidacionPresentacionResponse;
import com.akine.billing.application.FinanciadorPagoService;
import com.akine.billing.application.PresentacionCommands;
import com.akine.billing.application.PresentacionService;
import com.akine.billing.application.PresentacionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * Presentaciones a financiadores y cuenta corriente (M21).
 *
 * <p><b>Prestado no es presentado, no es facturado y no es cobrado</b> (RN-M21-001). Confirmar un
 * lote de doscientas sesiones no mueve un peso: la unica operacion de este controller que genera
 * caja es el pago, y la unica que salda deuda es la conciliacion.
 *
 * <p>Todo bajo {@code cobro:register} con la sede como alcance — el mismo permiso con el que se
 * administra la deuda del paciente, porque la cuenta corriente del financiador es la otra mitad de
 * lo mismo. No se creo un permiso nuevo: el catalogo de la matriz §5 es cerrado y uno que repartiera
 * los mismos roles con el mismo alcance no discriminaria a nadie.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(
		name = "Presentaciones",
		description = "Lotes reclamados a financiadores, debitos, pagos y conciliacion (M21)")
public class PresentacionController {

	private static final int LIMITE_POR_DEFECTO = 50;

	private final PresentacionService presentaciones;
	private final FinanciadorPagoService pagos;
	private final BillingApiActor apiActor;

	public PresentacionController(
			PresentacionService presentaciones,
			FinanciadorPagoService pagos,
			BillingApiActor apiActor) {

		this.presentaciones = presentaciones;
		this.pagos = pagos;
		this.apiActor = apiActor;
	}

	// =================================================================================
	// Armado del lote
	// =================================================================================

	@GetMapping("/prestaciones-elegibles")
	@Operation(
			summary = "Prestaciones que se le pueden reclamar a un financiador",
			description = """
					Lista las deudas de financiador pendientes de un periodo (RF-M21-001).

					Ya vienen filtradas: deja afuera las anuladas, las sin saldo y **las que ya \
					estan vivas en otro lote**, de modo que armar el reclamo no pueda duplicar una \
					prestacion (RN-M21-003).

					> **Hoy devuelve lista vacia en cualquier despliegue real**, y no es un defecto \
					de esta operacion. No existe ninguna obligacion con responsable `FINANCIADOR` \
					porque el devengado nunca se recableo contra convenios y aranceles. Es la \
					reserva declarada en el design challenge de AKINE-07.04.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Prestaciones elegibles"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o el financiador no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public List<ObligacionResponse> elegibles(
			@PathVariable long consultorioId,
			@RequestParam long financiadorId,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
			@RequestParam(defaultValue = "50") int limite,
			@RequestParam(defaultValue = "0") int desplazamiento) {

		return presentaciones.elegibles(
						apiActor.current(), consultorioId, financiadorId, desde, hasta,
						limite, desplazamiento)
				.stream()
				.map(ObligacionResponse::de)
				.toList();
	}

	@PostMapping("/presentaciones")
	@Operation(
			summary = "Abrir el borrador de un lote",
			description = """
					Crea la presentacion en estado `BORRADOR`, opcionalmente con las prestaciones \
					que ya se eligieron (RF-M21-002).

					**Crear no reclama nada.** Hasta que se confirma, el lote no tiene numero y no \
					existe para el financiador.

					El importe de cada prestacion lo copia el servidor del saldo de la obligacion: \
					reclamarle a la obra social un numero que no sea la deuda devengada no es un \
					caso de uso, es un error.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Borrador creado"),
			@ApiResponse(
					responseCode = "400",
					description = "Periodo invalido o cuerpo incompleto",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, el financiador o alguna obligacion no existen",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`obligacion-ya-presentada` u `obligacion-no-presentable`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PresentacionResponse> crear(
			@PathVariable long consultorioId,
			@RequestBody @Valid CrearPresentacionRequest request) {

		PresentacionView vista = presentaciones.crear(
				apiActor.current(), consultorioId,
				new PresentacionCommands.Alta(
						request.financiadorId(), request.periodoDesde(), request.periodoHasta(),
						request.moneda(), request.obligacionIds()));

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId
						+ "/presentaciones/" + vista.id()))
				.body(PresentacionResponse.de(vista));
	}

	@PostMapping("/presentaciones/{presentacionId}/items")
	@Operation(
			summary = "Agregar una prestacion al borrador",
			description = """
					Suma una deuda de financiador al lote (RF-M21-002).

					**Incluirla no le mueve el saldo a la obligacion.** Reclamar no es cobrar: la \
					deuda se salda al conciliar el lote, prestacion por prestacion.

					Una prestacion no puede estar viva en dos lotes a la vez (RN-M21-003). Lo hace \
					cumplir un unique de la base sobre una columna generada, y el 409 lleva el id \
					del lote que la tiene para poder ir a mirarlo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Prestacion agregada"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La presentacion o la obligacion no existen",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`presentacion-no-editable`, `obligacion-ya-presentada` u "
							+ "`obligacion-no-presentable`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PresentacionItemResponse> agregarItem(
			@PathVariable long consultorioId,
			@PathVariable long presentacionId,
			@RequestBody @Valid AgregarItemRequest request) {

		PresentacionItemResponse item = PresentacionItemResponse.de(presentaciones.agregarItem(
				apiActor.current(), consultorioId, presentacionId, request.obligacionId()));

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/presentaciones/"
						+ presentacionId + "/items/" + item.id()))
				.body(item);
	}

	/**
	 * <b>Responde 204 sin cuerpo</b>, y por eso el {@code produces} de clase declara
	 * {@code application/json} ademas de {@code application/problem+json}.
	 *
	 * <p>Sin eso, el cliente generado manda {@code Accept: application/problem+json} y la
	 * negociacion de contenido corta con <b>406 antes de entrar al metodo</b>. Rompio activacion de
	 * cuenta y recuperacion de contraseña, y ningun test lo agarro: los unitarios del frontend usan
	 * {@code HttpTestingController}, que no negocia contenido, y los de integracion mandan el
	 * {@code Accept} de MockMvc.
	 */
	@DeleteMapping("/presentaciones/{presentacionId}/items/{itemId}")
	@Operation(
			summary = "Quitar una prestacion del borrador",
			description = """
					Saca una deuda del lote mientras se arma (RF-M21-002).

					**Solo en `BORRADOR`.** Una presentacion confirmada existe del otro lado del \
					mostrador: cambiar lo que se reclamo despues de reclamarlo, sin que el \
					financiador se entere, no es una correccion. Lo que corresponde entonces es un \
					**debito**, que deja motivo, actor e instante.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Prestacion quitada"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La presentacion o la prestacion no existen",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`presentacion-no-editable`: el lote ya se envio",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> quitarItem(
			@PathVariable long consultorioId,
			@PathVariable long presentacionId,
			@PathVariable long itemId) {

		presentaciones.quitarItem(apiActor.current(), consultorioId, presentacionId, itemId);
		return ResponseEntity.noContent().build();
	}

	// =================================================================================
	// Validacion y confirmacion
	// =================================================================================

	@GetMapping("/presentaciones/{presentacionId}/validacion")
	@Operation(
			summary = "Revisar el lote antes de confirmarlo",
			description = """
					Devuelve las prestaciones que no se pueden reclamar, con el motivo de cada una \
					(RF-M21-003).

					**Es una lectura: no cambia estado y no bloquea nada.** Lo que bloquea es \
					confirmar, que la vuelve a correr del lado del servidor — el frontend nunca es \
					autoridad. Tenerla aparte permite mostrar los problemas **mientras se arma** \
					el lote, en vez de descubrirlos todos juntos al apretar el boton.

					> **Todavia no valida orden, autorizacion ni credencial.** Esos tres requisitos \
					viven en el arancel congelado del convenio y el devengado no los copia a la \
					obligacion. Misma causa que la bandeja de elegibles vacia.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Resultado de la revision"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La presentacion no existe",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ValidacionPresentacionResponse validar(
			@PathVariable long consultorioId,
			@PathVariable long presentacionId) {

		return ValidacionPresentacionResponse.de(
				presentaciones.validar(apiActor.current(), consultorioId, presentacionId));
	}

	@PostMapping("/presentaciones/{presentacionId}/confirmacion")
	@Operation(
			summary = "Confirmar el envio del lote",
			description = """
					Asigna numero, congela el total y saca el lote del borrador (RF-M21-004).

					**Confirmar no cobra.** No toca el saldo de ninguna obligacion y no genera \
					ningun movimiento de caja: presentado y cobrado son dos estados distintos \
					(RN-M21-001).

					El numero se pide **despues** de validar: un lote que no se puede confirmar no \
					puede consumir un numero de la serie, porque el hueco despues no se puede \
					explicar. El correlativo es por sede y financiador, y se asigna con un `UPDATE` \
					atomico, nunca con `MAX + 1`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Lote confirmado"),
			@ApiResponse(
					responseCode = "400",
					description = "`presentacion-vacia`: el lote no tiene prestaciones",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La presentacion no existe",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`presentacion-no-editable` o `presentacion-con-hallazgos`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PresentacionResponse confirmar(
			@PathVariable long consultorioId,
			@PathVariable long presentacionId) {

		return PresentacionResponse.de(
				presentaciones.confirmar(apiActor.current(), consultorioId, presentacionId));
	}

	// =================================================================================
	// Factura, debitos, pagos y conciliacion
	// =================================================================================

	@PostMapping("/presentaciones/{presentacionId}/factura")
	@Operation(
			summary = "Registrar el comprobante externo",
			description = """
					Asocia la factura que el centro emitio por este lote (RF-M21-005).

					**El sistema no la genera ni la numera: la registra.** Se emite fuera de AKINE. \
					Lo unico que el sistema puede hacer es impedir que el mismo numero quede \
					asociado a dos lotes del mismo financiador, y eso lo garantiza un unique de la \
					base.

					**Facturar no es cobrar**: el lote sigue con el mismo saldo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Factura registrada"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La presentacion no existe",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`presentacion-estado-invalido` o `factura-duplicada`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PresentacionResponse registrarFactura(
			@PathVariable long consultorioId,
			@PathVariable long presentacionId,
			@RequestBody @Valid RegistrarFacturaRequest request) {

		return PresentacionResponse.de(presentaciones.registrarFactura(
				apiActor.current(), consultorioId, presentacionId,
				new PresentacionCommands.Factura(request.numero(), request.fecha())));
	}

	@PostMapping("/presentaciones/{presentacionId}/items/{itemId}/debito")
	@Operation(
			summary = "Registrar el rechazo de una prestacion",
			description = """
					Asienta el debito que el financiador informo (RF-M21-006).

					**No borra la prestacion ni perdona la deuda** (RN-M21-004). La sesion original \
					queda intacta, la obligacion sigue pendiente, y la prestacion vuelve a estar \
					**disponible para otro lote** — que es como se re-presenta lo rechazado.

					El saldo del lote se mueve con una condicion del motor y no con un lock, asi \
					que un aviso de debito y una transferencia cargados a la vez no pueden colarse \
					los dos: el segundo recibe 409 con el saldo actual.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Debito registrado"),
			@ApiResponse(
					responseCode = "400",
					description = "Importe fuera de rango o motivo ausente",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La presentacion o la prestacion no existen",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`presentacion-estado-invalido`, `presentacion-saldo-insuficiente` "
							+ "o `item-no-debitable`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PresentacionItemResponse debitar(
			@PathVariable long consultorioId,
			@PathVariable long presentacionId,
			@PathVariable long itemId,
			@RequestBody @Valid RegistrarDebitoRequest request) {

		return PresentacionItemResponse.de(presentaciones.debitar(
				apiActor.current(), consultorioId, presentacionId, itemId,
				new PresentacionCommands.Debito(request.importe(), request.motivo())));
	}

	@PostMapping("/presentaciones/{presentacionId}/pagos")
	@Operation(
			summary = "Registrar un pago del financiador",
			description = """
					Asienta la plata recibida contra el lote (RF-M21-007).

					**Es el unico punto de M21 que genera caja** (RN-M21-002), y el movimiento se \
					asienta en la misma transaccion: si el asiento falla, el pago no se registra. \
					Con transferencia o tarjeta ese movimiento **no afecta el arqueo**, porque esa \
					plata nunca toco el cajon; con efectivo hace falta jornada abierta.

					**Esto no salda ninguna obligacion.** Un pago es un importe global y no viene \
					con el detalle de que prestaciones cubre; repartirlo exigiria una regla de \
					imputacion que el financiador no informo. Las obligaciones se saldan al \
					conciliar.

					**Un pago mayor que el saldo se rechaza:** no esta pagando este lote. Uno menor \
					se acepta y el residual queda, que es exactamente lo que la conciliacion pide \
					resolver.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Pago registrado"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La presentacion no existe",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`presentacion-saldo-insuficiente`, `presentacion-estado-invalido`, "
							+ "`caja-no-abierta` (efectivo sin jornada) o "
							+ "`idempotency-key-conflict`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<FinanciadorPagoResponse> registrarPago(
			@PathVariable long consultorioId,
			@PathVariable long presentacionId,
			@RequestBody @Valid RegistrarPagoFinanciadorRequest request) {

		FinanciadorPagoResponse pago = FinanciadorPagoResponse.de(pagos.registrar(
				apiActor.current(), consultorioId, presentacionId,
				new PresentacionCommands.Pago(
						request.importe(), request.medio(), request.fechaPago(),
						request.referencia(), request.idempotencyKey())));

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/presentaciones/"
						+ presentacionId + "/pagos/" + pago.id()))
				.body(pago);
	}

	@GetMapping("/presentaciones/{presentacionId}/pagos")
	@Operation(
			summary = "Los pagos de un lote",
			description = "Lo que el financiador pago contra esta presentacion, en orden.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pagos del lote"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La presentacion no existe",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public List<FinanciadorPagoResponse> pagosDelLote(
			@PathVariable long consultorioId,
			@PathVariable long presentacionId) {

		return pagos.deLaPresentacion(apiActor.current(), consultorioId, presentacionId).stream()
				.map(FinanciadorPagoResponse::de)
				.toList();
	}

	@PostMapping("/presentaciones/{presentacionId}/conciliacion")
	@Operation(
			summary = "Cerrar el lote",
			description = """
					Da por explicado todo lo reclamado y salda las prestaciones aceptadas \
					(RF-M21-008).

					**Exige saldo cero y no ofrece ajuste.** Cerrar con diferencia haria que el \
					residual dejara de estar en ninguna parte como lo que es —plata reclamada y no \
					cobrada— y que la cuenta corriente cuadrara por definicion; prorratearlo entre \
					las prestaciones inventaria un debito que el financiador nunca informo. El \
					sistema **nombra el residual y se niega a fingir**: el 409 lo lleva, y la \
					salida es registrar el debito o el pago que falta.

					**Aca, y solo aca, se salda la deuda.** Cada prestacion que sigue `INCLUIDO` \
					pasa a `ACEPTADO` y su obligacion se descuenta por el importe exacto que se \
					presento. Una `DEBITADO` no salda nada: esa deuda sigue existiendo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Lote conciliado"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La presentacion no existe",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`presentacion-no-concilia` (lleva `residual`) o "
							+ "`presentacion-estado-invalido`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PresentacionResponse conciliar(
			@PathVariable long consultorioId,
			@PathVariable long presentacionId) {

		return PresentacionResponse.de(
				presentaciones.conciliar(apiActor.current(), consultorioId, presentacionId));
	}

	@PostMapping("/presentaciones/{presentacionId}/anulacion")
	@Operation(
			summary = "Descartar un borrador",
			description = """
					Marca el borrador como anulado, con motivo, y libera sus prestaciones.

					**No borra nada y solo funciona sobre un borrador.** Una presentacion enviada \
					no se anula: ya existe del otro lado del mostrador, y si el financiador la \
					rechaza entera eso son **debitos sobre todos sus items**, que es lo que \
					efectivamente paso. Hacer desaparecer el lote borraria la unica evidencia de \
					que se reclamo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Borrador descartado"),
			@ApiResponse(
					responseCode = "400",
					description = "Motivo ausente",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La presentacion no existe",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`presentacion-no-editable`: el lote ya se envio",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PresentacionResponse anular(
			@PathVariable long consultorioId,
			@PathVariable long presentacionId,
			@RequestBody @Valid AnularPresentacionRequest request) {

		return PresentacionResponse.de(presentaciones.anular(
				apiActor.current(), consultorioId, presentacionId, request.motivo()));
	}

	// =================================================================================
	// Consultas
	// =================================================================================

	@GetMapping("/presentaciones")
	@Operation(
			summary = "Bandejas por estado",
			description = """
					Lista los lotes de la sede, filtrados y paginados.

					Es la pantalla principal de M21: una bandeja por estado deja ver de un vistazo \
					que falta presentar, que espera respuesta y que quedo sin conciliar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Lotes de la sede"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public List<PresentacionResponse> buscar(
			@PathVariable long consultorioId,
			@RequestParam(required = false) String estado,
			@RequestParam(required = false) Long financiadorId,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
			@RequestParam(defaultValue = "50") int limite,
			@RequestParam(defaultValue = "0") int desplazamiento) {

		return presentaciones.buscar(
						apiActor.current(), consultorioId, estado, financiadorId, desde, hasta,
						limite <= 0 ? LIMITE_POR_DEFECTO : limite, desplazamiento)
				.stream()
				.map(PresentacionResponse::de)
				.toList();
	}

	@GetMapping("/presentaciones/{presentacionId}")
	@Operation(
			summary = "Detalle de un lote",
			description = "El lote con sus prestaciones, sus estados y sus importes.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Detalle del lote"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La presentacion no existe",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PresentacionResponse detalle(
			@PathVariable long consultorioId,
			@PathVariable long presentacionId) {

		return PresentacionResponse.de(
				presentaciones.detalle(apiActor.current(), consultorioId, presentacionId));
	}

	@GetMapping("/financiadores/{financiadorId}/cuenta-corriente")
	@Operation(
			summary = "Cuenta corriente de un financiador",
			description = """
					Lo reclamado, lo debitado, lo cobrado y el saldo con un financiador.

					**Cruza sedes a proposito:** la relacion comercial es de la organizacion aunque \
					cada lote se arme en una sede, y un centro con dos consultorios negocia una \
					sola cuenta con cada obra social.

					**Y esto no es la caja.** La caja es un cajon con jornada y arqueo, que se \
					cuenta; esto es una relacion que vive en meses y que nadie cuenta. El unico \
					lugar donde se tocan es el pago.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Estado de cuenta"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o el financiador no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public CuentaCorrienteFinanciadorResponse cuentaCorriente(
			@PathVariable long consultorioId,
			@PathVariable long financiadorId) {

		return CuentaCorrienteFinanciadorResponse.de(
				pagos.cuentaCorriente(apiActor.current(), consultorioId, financiadorId));
	}
}
