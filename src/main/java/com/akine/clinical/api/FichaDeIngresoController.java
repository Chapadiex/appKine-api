package com.akine.clinical.api;

import com.akine.clinical.api.dto.FichaDeIngresoRequest;
import com.akine.clinical.api.dto.FichaDeIngresoResponse;
import com.akine.clinical.application.FichaDeIngresoService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La ficha de ingreso desde la historia clinica, para el profesional. Lo que guarda queda revisado
 * por el; lo declarado se revisa aca. Mismo direccionamiento por persona que
 * {@link HistoriaClinicaController}.
 */
@RestController
@RequestMapping(path = "/api/v1/historias-clinicas/por-persona/{personaId}/ficha")
@Tag(name = "Historia clinica",
		description = "Apertura, resumen y antecedentes de la Historia Clinica de una persona (M09)")
public class FichaDeIngresoController {

	private static final String PERSONA_DOC = "Persona titular de la historia";

	private final FichaDeIngresoService service;
	private final ClinicalApiActor apiActor;

	public FichaDeIngresoController(FichaDeIngresoService service, ClinicalApiActor apiActor) {
		this.service = service;
		this.apiActor = apiActor;
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "verFichaDeIngreso", summary = "Ver la ficha de ingreso",
			description = "204 si todavia no se cargo. Exige hc:read mas relacion asistencial o motivo.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La ficha",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = FichaDeIngresoResponse.class))),
			@ApiResponse(responseCode = "204", description = "Sin ficha todavia"),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:read o sin motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La persona es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<FichaDeIngresoResponse> ver(
			@Parameter(description = PERSONA_DOC, example = "12") @PathVariable long personaId,
			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false) String justificacion) {
		return service.ver(apiActor.current(), personaId, justificacion)
				.map(v -> ResponseEntity.ok(FichaDeIngresoResponse.from(v)))
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "registrarFichaDeIngreso", summary = "Guardar la ficha de ingreso (profesional)",
			description = """
					Reemplaza la ficha entera y exige la version leida (null si no existe); otra \
					version es 409 concurrent-modification. Queda revisada por quien la guarda. La \
					persona tiene que tener historia. Exige hc:write.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Ficha guardada",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = FichaDeIngresoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Valores fuera de rango u ocupacion inexistente",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write o sin motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La persona no tiene historia o es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Version vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public FichaDeIngresoResponse registrar(
			@Parameter(description = PERSONA_DOC, example = "12") @PathVariable long personaId,
			@Valid @RequestBody FichaDeIngresoRequest request,
			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false) String justificacion) {
		return FichaDeIngresoResponse.from(service.registrar(
				apiActor.current(), personaId, request.datos(), request.version(), justificacion));
	}

	@PutMapping(path = "/revision", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "revisarFichaDeIngreso", summary = "Marcar revisada la ficha de ingreso",
			description = "Idempotente: una ya revisada se devuelve como esta. Exige hc:write.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Ficha revisada",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = FichaDeIngresoResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write o sin motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "Sin historia o sin ficha",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public FichaDeIngresoResponse revisar(
			@Parameter(description = PERSONA_DOC, example = "12") @PathVariable long personaId,
			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false) String justificacion) {
		return FichaDeIngresoResponse.from(service.revisar(apiActor.current(), personaId, justificacion));
	}
}
