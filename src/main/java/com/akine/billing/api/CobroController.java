package com.akine.billing.api;

import com.akine.billing.api.dto.AnularCobroRequest;
import com.akine.billing.api.dto.CobroResponse;
import com.akine.billing.api.dto.ImputarSaldoAFavorRequest;
import com.akine.billing.api.dto.RegistrarCobroRequest;
import com.akine.billing.api.dto.ReintegrarSaldoAFavorRequest;
import com.akine.billing.api.dto.ReintegroResponse;
import com.akine.billing.application.CobroPosteriorService;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.CobroView;
import com.akine.billing.application.ReintegroView;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Registro de cobros y recuperacion de comprobantes (M19).
 *
 * <p>Cobro no es deuda ni es caja: la deuda dice cuanto se debe (M18), el cobro dice que se pago y
 * la caja dice que el dinero entro al arqueo (M20, fuera del Paquete B).
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/cobros",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Cobros", description = "Dinero recibido, sus medios y sus imputaciones (M19)")
public class CobroController {

	private final CobroService cobroService;
	private final CobroPosteriorService cobroPosteriorService;
	private final BillingApiActor apiActor;

	public CobroController(
			CobroService cobroService,
			CobroPosteriorService cobroPosteriorService,
			BillingApiActor apiActor) {

		this.cobroService = cobroService;
		this.cobroPosteriorService = cobroPosteriorService;
		this.apiActor = apiActor;
	}

	@PostMapping
	@Operation(
			operationId = "registrarCobro",
			summary = "Registrar un cobro",
			description = """
					Recibe dinero por uno o varios medios y lo imputa a las deudas indicadas, \
					emitiendo un **comprobante correlativo por sede**.

					**Las dos sumas tienen que dar el total**: la de los medios y la de las \
					imputaciones. Son invariantes de RN-M19 que ninguna constraint de base puede \
					expresar, asi que las verifica el servidor y un cuerpo que no las cumple es \
					400. Sin la primera, un cobro de 8500 con un medio de 850 —un cero de menos— \
					entraria igual, la deuda quedaria saldada y en la caja faltaria plata que \
					nadie podria explicar.

					**El descuento del saldo es atomico.** Si entre que la pantalla mostro la \
					cuenta corriente y el operador confirmo otro cobro se llevo la plata, la \
					respuesta es 409 `saldo-insuficiente` y no un saldo negativo.

					**Manda `idempotencyKey`.** Un reintento devuelve el mismo cobro con el mismo \
					comprobante; sin ella, un doble click cobra dos veces. La idempotencia se \
					evalua antes de tocar el numerador, para que un reintento no consuma un numero \
					de comprobante que despues nadie usa: la numeracion fiscal con huecos es peor \
					que un cobro repetido.

					**Anticipo.** Lo que no se imputa puede quedar a favor del paciente, pero \
					**se declara** en `anticipo`: las imputaciones mas el anticipo tienen que dar \
					el total. Un cobro puede ser todo anticipo —sin imputaciones, y entonces con \
					`moneda`—. La plata entra a la caja ahora, una vez, y se aplica despues con \
					`imputarSaldoAFavor` o se devuelve con `reintegrarSaldoAFavor`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Cobro registrado"),
			@ApiResponse(
					responseCode = "400",
					description = "Los medios o las imputaciones no suman el total",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant; o, en un anticipo puro, la "
							+ "persona no es de la organizacion",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Deuda anulada, pagada o de otra persona; saldo insuficiente; o "
							+ "clave de idempotencia reusada con otro pedido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CobroResponse> registrar(
			@PathVariable long consultorioId,
			@RequestBody @Valid RegistrarCobroRequest request) {

		CobroView vista = cobroService.registrar(
				apiActor.current(), consultorioId, request.aDominio());

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/cobros/" + vista.id()))
				.body(CobroResponse.de(vista));
	}

	@PostMapping("/{cobroId}/imputaciones")
	@Operation(
			operationId = "imputarSaldoAFavor",
			summary = "Imputar el saldo a favor de un cobro a una deuda",
			description = """
					Aplica parte del **anticipo** de un cobro a una deuda que nacio despues \
					(RF-M19-003). Una deuda por pedido. **No mueve caja**: la plata entro cuando se \
					cobro.

					La deuda tiene que ser de la misma persona y la misma sede que el cobro, admitir \
					cobro y estar en su moneda. Un cobro no imputa dos veces a la misma deuda: se \
					imputa lo que corresponde de una vez.

					Si otro operador uso el anticipo entre que la pantalla lo mostro y este \
					confirmo, la respuesta es 409 `saldo-a-favor-insuficiente` con lo `disponible`. \
					Manda `idempotencyKey`: un reintento devuelve el mismo cobro sin imputar dos \
					veces.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Cobro con la imputacion nueva"),
			@ApiResponse(
					responseCode = "400",
					description = "Cuerpo invalido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El cobro o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Cobro anulado; saldo a favor insuficiente; deuda no cobrable, "
							+ "sin ese saldo o ya imputada por este cobro; o clave reusada",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CobroResponse> imputarSaldoAFavor(
			@PathVariable long consultorioId,
			@PathVariable long cobroId,
			@RequestBody @Valid ImputarSaldoAFavorRequest request) {

		return ResponseEntity.ok(CobroResponse.de(cobroPosteriorService.imputarSaldoAFavor(
				apiActor.current(), consultorioId, cobroId, request.aDominio())));
	}

	@PostMapping("/{cobroId}/anulacion")
	@Operation(
			operationId = "anularCobro",
			summary = "Anular un cobro",
			description = """
					Revierte un cobro con trazabilidad (RF-M19-007, RN-M19-004), en una sola \
					transaccion: cada imputacion **devuelve su importe a la deuda** —que vuelve a \
					`PENDIENTE` o `PARCIAL`— y cada movimiento de caja del cobro **se revierte** en \
					la jornada abierta hoy. Nada se borra: el cobro queda `ANULADO` con su motivo y \
					**conserva su comprobante**.

					Si el cobro entro en efectivo, la reversion exige caja abierta (409 \
					`caja-no-abierta`) y plata en el cajon (409 `caja-saldo-insuficiente`). Un cobro \
					que ya reintegro parte de su saldo a favor no se anula (409 \
					`cobro-con-reintegros`): esa plata saldria del cajon dos veces.

					Exige `cobro:register` **y** `caja:operate`: mueve plata del cajon.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Cobro anulado"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` o sin `caja:operate` en esa sede, o sin contexto",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El cobro o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ya anulado; con reintegros; sin caja abierta o sin plata en el cajon "
							+ "para revertir el efectivo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CobroResponse> anularCobro(
			@PathVariable long consultorioId,
			@PathVariable long cobroId,
			@RequestBody @Valid AnularCobroRequest request) {

		return ResponseEntity.ok(CobroResponse.de(cobroPosteriorService.anular(
				apiActor.current(), consultorioId, cobroId, request.motivo())));
	}

	@PostMapping("/{cobroId}/reintegros")
	@Operation(
			operationId = "reintegrarSaldoAFavor",
			summary = "Reintegrar el saldo a favor de un cobro",
			description = """
					Devuelve en dinero parte del **anticipo** de un cobro (DP-06). Es una salida de \
					caja de origen `REINTEGRO` y no una reversion: puede ser parcial, repetirse y \
					salir por otro medio que el que entro. **No toca ninguna deuda.**

					En efectivo exige caja abierta y plata en el cajon. Si el saldo a favor no \
					alcanza, 409 `saldo-a-favor-insuficiente` con lo `disponible`. Manda \
					`idempotencyKey`: un reintento no devuelve dos veces.

					Exige `cobro:register` **y** `caja:operate`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Reintegro registrado"),
			@ApiResponse(
					responseCode = "400",
					description = "Cuerpo invalido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` o sin `caja:operate` en esa sede, o sin contexto",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El cobro o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Cobro anulado; saldo a favor insuficiente; sin caja abierta o sin "
							+ "plata en el cajon; o clave reusada",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ReintegroResponse> reintegrarSaldoAFavor(
			@PathVariable long consultorioId,
			@PathVariable long cobroId,
			@RequestBody @Valid ReintegrarSaldoAFavorRequest request) {

		ReintegroView vista = cobroPosteriorService.reintegrar(
				apiActor.current(), consultorioId, cobroId, request.aDominio());

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/cobros/" + cobroId))
				.body(ReintegroResponse.de(vista));
	}

	@GetMapping("/{cobroId}")
	@Operation(
			summary = "Recuperar un comprobante",
			description = "Devuelve el cobro con su comprobante, sus medios y sus imputaciones. "
					+ "**Es la reimpresion**: sin esta lectura, un operador que necesita el "
					+ "comprobante de nuevo tendria como unica salida volver a registrar el cobro.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Cobro"),
			@ApiResponse(
					responseCode = "404",
					description = "El cobro o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CobroResponse> verCobro(
			@PathVariable long consultorioId,
			@PathVariable long cobroId) {

		return ResponseEntity.ok(CobroResponse.de(
				cobroService.ver(apiActor.current(), consultorioId, cobroId)));
	}

	@GetMapping
	@Operation(
			summary = "Cobros de un paciente",
			description = "Los cobros de un paciente en toda la organizacion, del mas reciente al "
					+ "mas viejo. Junto con las obligaciones forma su cuenta corriente.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Cobros del paciente"),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<CobroResponse>> cobrosDeLaPersona(
			@PathVariable long consultorioId,
			@RequestParam long personaId) {

		return ResponseEntity.ok(
				cobroService.deLaPersona(apiActor.current(), consultorioId, personaId).stream()
						.map(CobroResponse::de)
						.toList());
	}
}
