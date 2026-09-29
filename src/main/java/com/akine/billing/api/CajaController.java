package com.akine.billing.api;

import com.akine.billing.api.dto.AbrirCajaRequest;
import com.akine.billing.api.dto.CerrarCajaRequest;
import com.akine.billing.api.dto.JornadaCajaResponse;
import com.akine.billing.api.dto.MovimientoCajaResponse;
import com.akine.billing.api.dto.RegistrarMovimientoRequest;
import com.akine.billing.api.dto.RevertirMovimientoRequest;
import com.akine.billing.application.CajaService;
import com.akine.billing.application.JornadaCajaView;
import com.akine.billing.application.MovimientoCajaService;
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
 * Caja diaria: apertura, movimientos reales, arqueo y cierres historicos (M20).
 *
 * <p><b>Caja no es cobro.</b> El cobro dice que alguien pago una deuda; la caja dice que entro o
 * salio plata de un cajon concreto, en una jornada concreta, con un responsable. La relacion no es
 * uno a uno: un cobro con dos medios produce dos movimientos, uno con tarjeta produce uno que no
 * afecta el arqueo, y una jornada existe sin ningun cobro.
 *
 * <p>Todas las operaciones exigen <b>{@code caja:operate}</b> con la sede como alcance. El registro
 * de un cobro <b>no</b> lo exige: el movimiento que genera es una consecuencia del cobro, no una
 * operacion de caja, y exigirlo alli haria que poder cobrar dependiera del medio de pago elegido.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/caja",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Caja", description = "Jornadas de caja y movimientos monetarios reales (M20)")
public class CajaController {

	private final CajaService cajaService;
	private final MovimientoCajaService movimientoService;
	private final BillingApiActor apiActor;

	public CajaController(
			CajaService cajaService,
			MovimientoCajaService movimientoService,
			BillingApiActor apiActor) {

		this.cajaService = cajaService;
		this.movimientoService = movimientoService;
		this.apiActor = apiActor;
	}

	// =================================================================================
	// Jornadas
	// =================================================================================

	@PostMapping("/jornadas")
	@Operation(
			summary = "Abrir la caja",
			description = """
					Abre el turno de caja de la sede con el saldo que se conto al empezar \
					(RF-M20-001).

					**A lo sumo una jornada abierta por sede.** Dos cajas abiertas sobre el mismo \
					cajon fisico hacen que ningun arqueo se pueda atribuir; lo hace cumplir un \
					unique de la base, y el 409 lleva el id de la que ya existe.

					**La fecha de negocio no se pide: la deriva el servidor** con la zona IANA de \
					la sede. Abrir una jornada "para ayer" es un ajuste contable disfrazado de \
					operacion.

					Varias jornadas por dia si son legitimas: manana y tarde, con responsables \
					distintos, arquean dos veces.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Caja abierta"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `caja:operate` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`caja-ya-abierta`: la sede ya tiene una jornada abierta",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<JornadaCajaResponse> abrir(
			@PathVariable long consultorioId,
			@RequestBody @Valid AbrirCajaRequest request) {

		JornadaCajaView vista = cajaService.abrir(
				apiActor.current(), consultorioId, request.saldoInicial(), request.moneda());

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId
						+ "/caja/jornadas/" + vista.id()))
				.body(JornadaCajaResponse.de(vista));
	}

	@PostMapping("/jornadas/{jornadaId}/cierre")
	@Operation(
			operationId = "cerrarCaja",
			summary = "Cerrar la caja",
			description = """
					Arquea y cierra el turno (RF-M20-006).

					**La diferencia la calcula el servidor** como `declarado - teorico`. El cuerpo \
					declara lo que se **conto**, que es un hecho del mundo fisico, nunca la \
					diferencia.

					**Una diferencia no rechaza el cierre y no se ajusta.** Rechazarlo dejaria al \
					centro sin poder cerrar el dia en que realmente falta plata, que es el dia en \
					que el registro importa; ajustarla con un movimiento que iguale borraria el \
					hecho. Lo unico que se exige es un motivo (RN-M20-004), y sin el la respuesta \
					es 400 `caja-diferencia-sin-motivo`.

					**`saldoTeoricoEsperado` es el control optimista del cierre.** Si entraron \
					movimientos entre que el operador empezo a contar y confirmo —por ejemplo un \
					cobro en efectivo en la otra computadora— la respuesta es 409 \
					`caja-saldo-cambio` con el teorico actual, en vez de registrar un faltante que \
					nunca existio y obligar a justificarlo por escrito.

					**Un cierre no se revierte y una caja cerrada no se reabre.** Un error se \
					compensa con movimientos en la jornada que este abierta hoy.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Caja cerrada, con su arqueo"),
			@ApiResponse(
					responseCode = "400",
					description = "`caja-diferencia-sin-motivo`: el arqueo no cuadra y no explica por que",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `caja:operate` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La jornada o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`caja-cerrada` (ya la cerraron) o `caja-saldo-cambio` (entraron "
							+ "movimientos mientras se contaba)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<JornadaCajaResponse> cerrar(
			@PathVariable long consultorioId,
			@PathVariable long jornadaId,
			@RequestBody @Valid CerrarCajaRequest request) {

		return ResponseEntity.ok(JornadaCajaResponse.de(cajaService.cerrar(
				apiActor.current(), consultorioId, jornadaId,
				request.saldoTeoricoEsperado(), request.saldoDeclarado(),
				request.motivoDiferencia())));
	}

	@GetMapping("/jornadas/{jornadaId}")
	@Operation(
			summary = "Ver una jornada",
			description = "Devuelve el saldo teorico (RF-M20-005) y el desglose por medio de pago. "
					+ "**El desglose no es adorno**: sin el, un arqueo de 60.000 sobre un dia en "
					+ "que se facturaron 120.000 parece un faltante gigante, cuando la mitad entro "
					+ "por tarjeta y nunca estuvo en el cajon.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Jornada con sus totales"),
			@ApiResponse(
					responseCode = "404",
					description = "La jornada o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<JornadaCajaResponse> verJornada(
			@PathVariable long consultorioId,
			@PathVariable long jornadaId) {

		return ResponseEntity.ok(JornadaCajaResponse.de(
				cajaService.ver(apiActor.current(), consultorioId, jornadaId)));
	}

	@GetMapping("/jornadas")
	@Operation(
			summary = "Jornadas de la sede",
			description = "Los cierres historicos, de la mas reciente a la mas vieja (RF-M20-007). "
					+ "Se consultan; **no se modifican por ningun camino**. Filtrar por `estado = "
					+ "ABIERTA` es como se encuentra la jornada en curso.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Jornadas"),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<JornadaCajaResponse>> jornadas(
			@PathVariable long consultorioId,
			@RequestParam(required = false) String estado,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
			@RequestParam(defaultValue = "50") int limite,
			@RequestParam(defaultValue = "0") int desplazamiento) {

		return ResponseEntity.ok(
				cajaService.historico(
								apiActor.current(), consultorioId, estado, desde, hasta,
								limite, desplazamiento)
						.stream()
						.map(JornadaCajaResponse::de)
						.toList());
	}

	// =================================================================================
	// Movimientos
	// =================================================================================

	@PostMapping("/movimientos")
	@Operation(
			summary = "Registrar un ingreso o un egreso",
			description = """
					Carga a mano un movimiento que no nace de un cobro (RF-M20-002, RF-M20-003).

					**Exige caja abierta**, a diferencia del cobro: un movimiento manual es un acto \
					de operacion de caja y no tiene sentido fuera de una jornada.

					**Un egreso no puede dejar la caja en negativo** — no por una regla configurable \
					sino porque un cajon no puede tener menos de cero pesos. Lo decide una condicion \
					del motor, asi que dos egresos concurrentes no pueden colarse los dos: el \
					segundo recibe 409 `caja-saldo-insuficiente`.

					**Solo `EFECTIVO` afecta el arqueo.** Los demas medios se registran y se \
					totalizan igual, para que la operatoria del dia este completa.

					**Manda `idempotencyKey`**: sin ella, un doble click carga el movimiento dos \
					veces y el arqueo no cierra.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Movimiento registrado"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `caja:operate` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`caja-no-abierta`, `caja-saldo-insuficiente`, `caja-moneda-distinta` "
							+ "o clave de idempotencia reusada con otro pedido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MovimientoCajaResponse> registrarMovimiento(
			@PathVariable long consultorioId,
			@RequestBody @Valid RegistrarMovimientoRequest request) {

		MovimientoCajaResponse cuerpo = MovimientoCajaResponse.de(
				movimientoService.registrarManual(
						apiActor.current(), consultorioId, request.aDominio()));

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId
						+ "/caja/movimientos/" + cuerpo.id()))
				.body(cuerpo);
	}

	@PostMapping("/movimientos/{movimientoId}/reversion")
	@Operation(
			summary = "Revertir un movimiento",
			description = """
					Compensa un movimiento con otro de signo opuesto, con motivo obligatorio \
					(RN-M20-003, RF-M24-006).

					**No borra ni edita nada.** El ledger es append-only: el movimiento original \
					queda donde estaba, diciendo que ocurrio, y la reversion lo apunta con \
					`movimientoOrigenId`.

					**La compensacion cae en la jornada abierta HOY, no en la del original.** La \
					jornada original ya fue arqueada, y si el error afecto el conteo su diferencia \
					ya lo registro: reescribirla haria que su saldo declarado dejara de coincidir \
					con lo que efectivamente se conto, destruyendo la unica evidencia de que hubo \
					un desvio. Y ademas, fisicamente, la plata se mueve hoy.

					**Un movimiento se revierte una sola vez, y una reversion no se revierte.** \
					Para deshacer una reversion se asienta un movimiento nuevo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Reversion asentada"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `caja:operate` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El movimiento o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`movimiento-no-reversible` (ya revertido, o es una reversion), "
							+ "`caja-no-abierta` o `caja-saldo-insuficiente`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MovimientoCajaResponse> revertirMovimiento(
			@PathVariable long consultorioId,
			@PathVariable long movimientoId,
			@RequestBody @Valid RevertirMovimientoRequest request) {

		MovimientoCajaResponse cuerpo = MovimientoCajaResponse.de(
				movimientoService.revertir(
						apiActor.current(), consultorioId, movimientoId, request.motivo()));

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId
						+ "/caja/movimientos/" + cuerpo.id()))
				.body(cuerpo);
	}

	@GetMapping("/movimientos")
	@Operation(
			summary = "Consultar movimientos",
			description = "Lista y filtra la operatoria (RF-M20-004). **Filtrar por `fechaNegocio` "
					+ "y no solo por jornada es lo que hace visibles los movimientos sin jornada** "
					+ "—los que no son en efectivo y llegaron sin caja abierta—, que de otro modo "
					+ "no aparecerian en ninguna vista y la operatoria del dia quedaria incompleta.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Movimientos"),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<MovimientoCajaResponse>> movimientos(
			@PathVariable long consultorioId,
			@RequestParam(required = false) Long jornadaId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaNegocio,
			@RequestParam(required = false) String tipo,
			@RequestParam(defaultValue = "50") int limite,
			@RequestParam(defaultValue = "0") int desplazamiento) {

		return ResponseEntity.ok(
				movimientoService.buscar(
								apiActor.current(), consultorioId, jornadaId, fechaNegocio, tipo,
								limite, desplazamiento)
						.stream()
						.map(MovimientoCajaResponse::de)
						.toList());
	}
}
