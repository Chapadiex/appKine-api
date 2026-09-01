package com.akine.billing.api;

import com.akine.billing.api.dto.AnularObligacionRequest;
import com.akine.billing.api.dto.ObligacionResponse;
import com.akine.billing.application.ObligacionService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Consulta y anulacion de deuda (M18).
 *
 * <p><b>No hay endpoint para crear una obligacion.</b> La deuda nace del cierre de una atencion y
 * la deriva el modulo economico reaccionando a ese cierre: una deuda sin prestacion que la respalde
 * es un cargo que nadie puede justificar, y el snapshot que la explica solo existe en ese momento.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/obligaciones",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Obligaciones", description = "Deuda derivada de prestaciones. No es el cobro ni la caja (M18)")
public class ObligacionController {

	private final ObligacionService obligacionService;
	private final BillingApiActor apiActor;

	public ObligacionController(ObligacionService obligacionService, BillingApiActor apiActor) {
		this.obligacionService = obligacionService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			summary = "Cuenta corriente de un paciente",
			description = """
					La deuda de un paciente en **toda la organizacion**, de la mas reciente a la \
					mas vieja.

					El alcance es la organizacion y no la sede, aunque el permiso se evalue con la \
					sede del contexto: la deuda de un paciente es una sola aunque se haya generado \
					en dos sedes del mismo centro, y mostrarla partida obligaria al administrativo \
					a sumar de memoria.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Cuenta corriente"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `cobro:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<ObligacionResponse>> obligacionesDeLaPersona(
			@PathVariable long consultorioId,
			@RequestParam long personaId) {

		return ResponseEntity.ok(
				obligacionService.deLaPersona(apiActor.current(), consultorioId, personaId).stream()
						.map(ObligacionResponse::de)
						.toList());
	}

	@GetMapping("/{obligacionId}")
	@Operation(summary = "Ver una obligacion")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Obligacion"),
			@ApiResponse(
					responseCode = "404",
					description = "La obligacion o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ObligacionResponse> verObligacion(
			@PathVariable long consultorioId,
			@PathVariable long obligacionId) {

		return ResponseEntity.ok(ObligacionResponse.de(
				obligacionService.ver(apiActor.current(), consultorioId, obligacionId)));
	}

	@DeleteMapping("/{obligacionId}")
	@Operation(
			summary = "Anular una obligacion",
			description = """
					Anula la deuda con **motivo obligatorio**. No la borra: una deuda que \
					desaparece de la base es una cuenta corriente que no cuadra y que nadie puede \
					auditar despues.

					**Una obligacion con cobros imputados no se anula.** Anular lo que ya se cobro \
					dejaria plata en la caja sin ninguna deuda que la justifique, y el arqueo del \
					dia no cerraria. Lo que corresponde en ese caso es una devolucion, que es M19 \
					y tiene su propio registro.

					Devuelve la obligacion anulada y no un 204, porque el cuerpo trae el estado y \
					la version nueva que la pantalla necesita para seguir operando.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Obligacion anulada"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La obligacion o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ya estaba anulada, tiene cobros imputados, o la version quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ObligacionResponse> anular(
			@PathVariable long consultorioId,
			@PathVariable long obligacionId,
			@RequestBody @Valid AnularObligacionRequest request) {

		return ResponseEntity.ok(ObligacionResponse.de(obligacionService.anular(
				apiActor.current(), consultorioId, obligacionId,
				request.motivo(), request.version())));
	}
}
