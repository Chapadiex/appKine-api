package com.akine.billing.api;

import com.akine.billing.api.dto.CobroResponse;
import com.akine.billing.api.dto.RegistrarCobroRequest;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.CobroView;
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
	private final BillingApiActor apiActor;

	public CobroController(CobroService cobroService, BillingApiActor apiActor) {
		this.cobroService = cobroService;
		this.apiActor = apiActor;
	}

	@PostMapping
	@Operation(
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

					**No hay anticipos ni anulacion todavia.** Los dos exigen la Caja \
					(AKINE-07.03): un anticipo sin caja es plata que entro y que ningun arqueo \
					puede encontrar, y un reintegro saca dinero de una caja que no existe.""")
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
					description = "La sede no existe o es de otro tenant",
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
	public ResponseEntity<CobroResponse> ver(
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
	public ResponseEntity<List<CobroResponse>> deLaPersona(
			@PathVariable long consultorioId,
			@RequestParam long personaId) {

		return ResponseEntity.ok(
				cobroService.deLaPersona(apiActor.current(), consultorioId, personaId).stream()
						.map(CobroResponse::de)
						.toList());
	}
}
