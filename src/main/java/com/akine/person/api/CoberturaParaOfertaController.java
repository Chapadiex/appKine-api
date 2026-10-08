package com.akine.person.api;

import com.akine.person.api.dto.CoberturaParaOfertaResponse;
import com.akine.person.application.CoberturaParaOfertaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Cobertura aplicable por Oferta de Servicio (B-3, RF-M08-006) y condicion particular sugerida
 * (RF-M08-007). Consulta preliminar: no persiste ni consume nada.
 */
@RestController
@RequestMapping(
		path = "/api/v1/personas/{personaId}/cobertura-aplicable",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Coberturas del paciente",
		description = "Coberturas particulares y financiadas de un paciente (M08)")
public class CoberturaParaOfertaController {

	private final CoberturaParaOfertaService servicio;
	private final PersonApiActor apiActor;

	public CoberturaParaOfertaController(CoberturaParaOfertaService servicio, PersonApiActor apiActor) {
		this.servicio = servicio;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "resolverCoberturaAplicablePorOferta",
			summary = "Que cubre la obra social de la persona para esta oferta",
			description = """
					RF-M08-006 y RF-M08-007. Para cada cobertura FINANCIADA vigente de la persona \
					dice si aplica a la oferta, con que practica y con que arancel; si no aplica, \
					por que. Y sugiere la condicion: COBERTURA si alguna aplica, PARTICULAR si no, \
					con el precio particular que rige ese dia.

					LA OFERTA MANDA ANTES QUE LA COBERTURA (RN-M08-005): si la oferta no admite \
					obra social, ninguna cobertura aplica y el paciente paga particular aunque \
					tenga obra social (CA-M08-006-06, Pilates). Tener la cobertura no alcanza.

					CON VARIAS PRACTICAS en la oferta (A-9), se prueban en el orden de DP-11 —la \
					principal primero— y la cobertura aplica con la primera que resuelve. El \
					detalle por practica explica las demas. El arancel especifico de la oferta \
					(RF-M16-008) manda sobre el general de la practica: arancel.ofertaId lo dice.

					NO DECIDE NADA. Atender como particular aunque haya cobertura es decision del \
					operador en la recepcion (RF-M13-005) y no toca la cobertura del paciente \
					(CA-M08-007-06). No persiste, no consume y es idempotente.

					Exige consultorio en el contexto: la oferta y el convenio son de la sede. Se \
					autoriza por pertenencia, como la lista de coberturas. El numero de afiliado no \
					viaja.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200",
					description = "Coberturas que aplican y que no, y la condicion sugerida",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CoberturaParaOfertaResponse.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo, sin consultorio en el contexto, o sin paciente:read",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La persona o la oferta no existen, o son de otra organizacion o "
							+ "de otra sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CoberturaParaOfertaResponse> resolver(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Oferta de la sede del contexto", example = "34")
			@RequestParam long ofertaId,

			@Parameter(description = "Dia de la atencion. Si se omite, hoy", example = "2026-11-03")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		return ResponseEntity.ok(CoberturaParaOfertaResponse.de(
				servicio.resolver(apiActor.current(), personaId, ofertaId, fecha)));
	}
}
