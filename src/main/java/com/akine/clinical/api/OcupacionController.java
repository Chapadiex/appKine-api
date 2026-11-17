package com.akine.clinical.api;

import com.akine.clinical.api.dto.AltaDeOcupacionRequest;
import com.akine.clinical.api.dto.OcupacionResponse;
import com.akine.clinical.application.OcupacionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Catalogo de ocupaciones de la ficha de ingreso: las del producto mas las del centro. */
@RestController
@RequestMapping(path = "/api/v1/ocupaciones")
@Tag(name = "Ocupaciones", description = "Catalogo de ocupaciones de la ficha de ingreso")
public class OcupacionController {

	private final OcupacionService service;
	private final ClinicalApiActor apiActor;

	public OcupacionController(OcupacionService service, ClinicalApiActor apiActor) {
		this.service = service;
		this.apiActor = apiActor;
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "listarOcupaciones", summary = "Listar ocupaciones",
			description = "Las del producto y las que agrego el centro, por nombre. Alcanza con el contexto.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Ocupaciones visibles",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(schema = @Schema(implementation = OcupacionResponse.class)))),
			@ApiResponse(responseCode = "403", description = "Sin contexto",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public List<OcupacionResponse> listar() {
		return service.visibles(apiActor.current()).stream().map(OcupacionResponse::from).toList();
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "agregarOcupacion", summary = "Agregar una ocupacion del centro",
			description = """
					201 si se creo. Si ya existe con el mismo nombre (sin importar mayusculas, tildes \
					ni espacios), 200 con la existente. Exige hc:declarar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Ocupacion creada",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OcupacionResponse.class))),
			@ApiResponse(responseCode = "200", description = "Ya existia",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OcupacionResponse.class))),
			@ApiResponse(responseCode = "400", description = "Nombre vacio o largo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto o sin hc:declarar",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<OcupacionResponse> agregar(@Valid @RequestBody AltaDeOcupacionRequest request) {
		OcupacionService.Alta alta = service.agregar(apiActor.current(), request.nombre());
		return ResponseEntity.status(alta.creada() ? HttpStatus.CREATED : HttpStatus.OK)
				.body(OcupacionResponse.from(alta.ocupacion()));
	}
}
