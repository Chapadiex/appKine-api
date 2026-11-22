package com.akine.contracting.api;

import com.akine.contracting.api.dto.ConvenioResponse;
import com.akine.contracting.application.ConvenioService;
import com.akine.contracting.domain.exception.ConvenioNotAccessibleException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El convenio del plan (V37): lo que paga la obra social se carga en el plan, para todas las sedes.
 *
 * <p>Es un convenio sin sede. Sus aranceles se administran con las mismas operaciones de siempre
 * ({@code /consultorios/{consultorioId}/convenios/{convenioId}/aranceles}), desde cualquier sede de
 * la organizacion: el convenio del plan responde bajo todas.
 */
@RestController
@RequestMapping(
		path = "/api/v1/financiadores/{financiadorId}/planes/{planId}/convenio",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Convenios y aranceles",
		description = "Convenios de la sede con planes de financiadores, y sus aranceles (M16)")
public class ConvenioDelPlanController {

	private final ConvenioService convenioService;
	private final ContractingApiActor apiActor;

	public ConvenioDelPlanController(ConvenioService convenioService, ContractingApiActor apiActor) {
		this.convenioService = convenioService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "getConvenioDelPlan",
			summary = "Ver el convenio del plan",
			description = """
					El convenio sin sede del plan, que vale para todas las sedes: sus aranceles son \
					lo que paga la obra social por cada practica. 404 si el plan todavia no tiene \
					precios cargados. Exige convenio:read.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El convenio del plan",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ConvenioResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto de trabajo activo o sin convenio:read",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "El plan no existe, no es de ese financiador o todavia no tiene precios",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ConvenioResponse> get(
			@Parameter(description = "Identificador del financiador", example = "10")
			@PathVariable long financiadorId,
			@Parameter(description = "Identificador del plan", example = "30")
			@PathVariable long planId) {

		return ResponseEntity.ok(convenioService.delPlan(apiActor.current(), financiadorId, planId)
				.map(ConvenioResponse::de)
				.orElseThrow(() -> new ConvenioNotAccessibleException(planId)));
	}

	@PostMapping
	@Operation(
			operationId = "asegurarConvenioDelPlan",
			summary = "Preparar el plan para cargarle precios",
			description = """
					Devuelve el convenio del plan y lo crea si falta. Idempotente. Nace por \
					prestacion, en la moneda del plan (pesos si no declara), con los requisitos \
					del plan y sin fecha de fin: lo que tiene fechas es cada arancel. Exige \
					convenio:manage sobre la sede del contexto.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El convenio del plan",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ConvenioResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto de trabajo activo o sin convenio:manage",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "El plan no existe o no es de ese financiador",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El financiador o el plan estan dados de baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ConvenioResponse> asegurar(
			@Parameter(description = "Identificador del financiador", example = "10")
			@PathVariable long financiadorId,
			@Parameter(description = "Identificador del plan", example = "30")
			@PathVariable long planId) {

		return ResponseEntity.ok(ConvenioResponse.de(
				convenioService.asegurarDelPlan(apiActor.current(), financiadorId, planId)));
	}
}
