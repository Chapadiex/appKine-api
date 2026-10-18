package com.akine.activity.api;

import com.akine.activity.api.dto.HonorariosDtos.BorradorDeHonorariosResponse;
import com.akine.activity.api.dto.HonorariosDtos.ConfirmarLiquidacionRequest;
import com.akine.activity.api.dto.HonorariosDtos.LiquidacionDeHonorariosResponse;
import com.akine.activity.application.HonorariosDeClasesService;
import com.akine.activity.application.HonorariosViews.ResultadoDeConfirmacion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

/**
 * Honorarios por clase: borrador por periodo y colaborador, confirmacion como egreso y lectura de
 * la liquidacion (AKINE-08.09b, DP-38, RF-M22-006, RF-M22-007).
 *
 * <p>Todas exigen {@code caja:operate} en la sede: es el permiso del egreso de M22, y el
 * {@code PROFESIONAL} no lo tiene —no se liquida a si mismo—.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/honorarios",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Egresos", description = "Egresos, liquidaciones y pagos a profesionales (M22)")
public class HonorariosController {

	private final HonorariosDeClasesService honorarios;
	private final ActivityApiActor apiActor;

	public HonorariosController(HonorariosDeClasesService honorarios, ActivityApiActor apiActor) {
		this.honorarios = honorarios;
		this.apiActor = apiActor;
	}

	@GetMapping("/borrador")
	@Operation(
			operationId = "calcularBorradorDeHonorarios",
			summary = "Calcular el borrador de honorarios por clase de un colaborador",
			description = """
					DP-38 (RF-M22-006). **Se calcula al pedirlo y no se guarda.** Entran las clases \
					`REALIZADA` de la sede con inicio en el periodo (dias locales de la sede) donde \
					el colaborador es el principal o un instructor adicional.

					Por clase: **tarifa vigente el dia de la clase x presentes** (`PRESENTE` + \
					`PRESENTE_TARDE`), repartida **en partes iguales** entre los instructores; el \
					centavo sobrante va al primero del orden estable (el principal, despues los \
					adicionales por membership).

					`excluidas` dice por que una clase dictada no entra: `YA_LIQUIDADA` (con el \
					egreso que la tiene), `SIN_HONORARIO`, `SIN_PRESENTES` o `SIN_IMPORTE`. Periodo de \
					hasta 62 dias. Monedas distintas en el periodo: 400.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Borrador calculado"),
			@ApiResponse(responseCode = "400", description = "Periodo invertido, de mas de 62 dias, o con "
					+ "tarifas en monedas distintas",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin `caja:operate` en la sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<BorradorDeHonorariosResponse> borrador(
			@PathVariable long consultorioId,

			@Parameter(description = "Membership del colaborador", example = "31", required = true)
			@RequestParam long colaboradorMembershipId,

			@Parameter(description = "Primer dia del periodo, inclusive", example = "2026-10-01", required = true)
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,

			@Parameter(description = "Ultimo dia del periodo, inclusive", example = "2026-10-31", required = true)
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,

			@Parameter(description = "Acota a una oferta: una liquidacion es de una sola (DP-39)", example = "34")
			@RequestParam(required = false) Long ofertaId) {

		return ResponseEntity.ok(BorradorDeHonorariosResponse.de(honorarios.borrador(
				apiActor.current(), consultorioId, colaboradorMembershipId, desde, hasta, ofertaId)));
	}

	@PostMapping(path = "/liquidaciones", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "confirmarLiquidacionDeHonorarios",
			summary = "Confirmar el borrador de honorarios como egreso",
			description = """
					DP-38 (RF-M22-006/007). Recalcula el borrador **bajo el lock de sus clases** y, si \
					es el que se miro (`claseIds` e `importeTotal`), crea el **egreso** de M22 en \
					`BORRADOR` —`COLABORADOR`, `HONORARIOS_PROFESIONALES`, con el periodo— y la \
					liquidacion con el **snapshot por clase**. No mueve la caja: el egreso sigue su \
					ciclo (confirmar con comprobante, pagar).

					Si el borrador cambio —otra liquidacion, una asistencia corregida, una tarifa \
					nueva— **409 `borrador-de-honorarios-desactualizado`** con `importeTotalActual` y \
					`claseIdsActuales`. Sin clases liquidables, **409 \
					`liquidacion-de-honorarios-vacia`**.

					**Idempotente** por `idempotencyKey`: el reintento con el mismo pedido devuelve la \
					misma liquidacion (200); con otro pedido, 409. Una clase liquidada no entra en \
					otro borrador del mismo colaborador hasta que se anule su egreso.

					**Una liquidacion es de UNA oferta** y su egreso queda imputado a ella (`ofertaId`, \
					DP-39), asi que los honorarios entran solos en la rentabilidad por servicio. Si el \
					borrador mezcla ofertas, 400: se pide y se confirma por oferta con `ofertaId`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Liquidacion confirmada y egreso creado"),
			@ApiResponse(responseCode = "200", description = "Reintento con la misma clave: la misma liquidacion"),
			@ApiResponse(responseCode = "400", description = "Cuerpo invalido o periodo invalido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin `caja:operate` en la sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "`borrador-de-honorarios-desactualizado`, "
					+ "`liquidacion-de-honorarios-vacia`, `beneficiario-no-vinculado`, "
					+ "`egreso-comprobante-duplicado` o clave de idempotencia reusada con otro pedido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<LiquidacionDeHonorariosResponse> confirmar(
			@PathVariable long consultorioId,
			@RequestBody @Valid ConfirmarLiquidacionRequest request) {

		ResultadoDeConfirmacion resultado =
				honorarios.confirmar(apiActor.current(), consultorioId, request.aDominio());
		LiquidacionDeHonorariosResponse cuerpo = LiquidacionDeHonorariosResponse.de(resultado.liquidacion());
		return resultado.creada()
				? ResponseEntity
						.created(URI.create("/api/v1/consultorios/" + consultorioId
								+ "/honorarios/liquidaciones/" + cuerpo.id()))
						.body(cuerpo)
				: ResponseEntity.ok(cuerpo);
	}

	@GetMapping("/liquidaciones/{liquidacionId}")
	@Operation(
			operationId = "verLiquidacionDeHonorarios",
			summary = "Ver una liquidacion de honorarios por clase",
			description = """
					DP-38 (RF-M22-007, CA-M22-007-06). La liquidacion con su **snapshot por clase**: \
					oferta, presentes, tarifa, instructores y la parte del colaborador, tal como se \
					confirmaron. `estado` `LIBERADA` si se anulo su egreso. El egreso la referencia \
					en `liquidacionHonorariosId`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La liquidacion"),
			@ApiResponse(responseCode = "403", description = "Sin `caja:operate` en la sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La liquidacion o la sede no existen, o son "
					+ "de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<LiquidacionDeHonorariosResponse> ver(
			@PathVariable long consultorioId,
			@PathVariable long liquidacionId) {

		return ResponseEntity.ok(LiquidacionDeHonorariosResponse.de(
				honorarios.ver(apiActor.current(), consultorioId, liquidacionId)));
	}
}
