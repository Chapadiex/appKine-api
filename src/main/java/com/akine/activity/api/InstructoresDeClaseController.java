package com.akine.activity.api;

import com.akine.activity.api.dto.HonorariosDtos.InstructoresDeClaseResponse;
import com.akine.activity.api.dto.HonorariosDtos.ReemplazarInstructoresRequest;
import com.akine.activity.application.InstructoresDeClaseService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los instructores de una clase: el principal y los adicionales (AKINE-08.09b, DP-38).
 *
 * <p>Existen por el reparto de honorarios por clase. El adicional <b>no ocupa agenda</b>.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/clases/{claseId}/instructores",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(
		name = "Clases",
		description = "Eventos grupales unicos de agenda. Una clase NO es N turnos (M28)")
public class InstructoresDeClaseController {

	private final InstructoresDeClaseService instructores;
	private final ActivityApiActor apiActor;

	public InstructoresDeClaseController(InstructoresDeClaseService instructores, ActivityApiActor apiActor) {
		this.instructores = instructores;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "getInstructoresDeClase",
			summary = "Ver los instructores de una clase",
			description = """
					DP-38. El principal (el profesional de la clase) y los adicionales vigentes, en el \
					**orden estable** del reparto de honorarios: el principal y despues los adicionales \
					por membership. Exige `clase:read`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Instructores de la clase"),
			@ApiResponse(responseCode = "403", description = "Sin `clase:read` en la sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La clase o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<InstructoresDeClaseResponse> ver(
			@PathVariable long consultorioId,
			@PathVariable long claseId) {

		return ResponseEntity.ok(InstructoresDeClaseResponse.de(
				instructores.ver(apiActor.current(), consultorioId, claseId)));
	}

	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "reemplazarInstructoresDeClase",
			summary = "Reemplazar los instructores adicionales de una clase",
			description = """
					DP-38. Reemplaza la lista **completa** de instructores adicionales; vacia los quita. \
					Exige `clase:manage`. Cada uno con vinculo vigente en la sede y habilitado para la \
					oferta (sin habilitaciones declaradas, cualquiera), sin repetir y sin el principal: \
					si no, 400. Quitar no borra: queda la historia.

					**El adicional no ocupa agenda**: la exclusion de turnos y clases sigue mirando \
					solo al principal.

					Una clase cancelada no cambia instructores (409 `clase-transicion-no-permitida`), \
					y una con honorarios ya liquidados tampoco (409 `clase-con-honorarios-liquidados`): \
					cambiaria el reparto de una liquidacion cerrada. Se anula el egreso, que la libera.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Instructores actualizados"),
			@ApiResponse(responseCode = "400", description = "Instructor repetido, el principal, ajeno, "
					+ "no vigente o no habilitado",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin `clase:manage` en la sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La clase o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Clase cancelada o con honorarios liquidados",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<InstructoresDeClaseResponse> reemplazar(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@RequestBody @Valid ReemplazarInstructoresRequest request) {

		return ResponseEntity.ok(InstructoresDeClaseResponse.de(
				instructores.reemplazar(apiActor.current(), consultorioId, claseId, request.adicionales())));
	}
}
