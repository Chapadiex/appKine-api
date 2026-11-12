package com.akine.knowledge.api;

import com.akine.knowledge.api.dto.BibliotecaBusquedaRequest;
import com.akine.knowledge.api.dto.BibliotecaBusquedaResponse;
import com.akine.knowledge.application.BusquedaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Busqueda en la biblioteca de la organizacion (M30), para que el administrador pruebe que sus
 * libros responden. Requiere {@code biblioteca:manage}; apagada, 409 {@code biblioteca-deshabilitada}.
 */
@RestController
@RequestMapping(
		path = "/api/v1/organizations/{orgId}/biblioteca/busquedas",
		produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@Tag(name = "Biblioteca", description = "Libros PDF de la organizacion")
public class BusquedaController {

	private final BusquedaService busquedas;
	private final KnowledgeApiActor apiActor;

	public BusquedaController(BusquedaService busquedas, KnowledgeApiActor apiActor) {
		this.busquedas = busquedas;
		this.apiActor = apiActor;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "buscarEnBiblioteca", summary = "Buscar pasajes en la biblioteca",
			description = "Devuelve los `k` pasajes mas parecidos (1 a 20, por defecto 5) de libros "
					+ "activos y listos. `camino` dice si se busco por similitud (VECTOR) o por "
					+ "palabras (PALABRAS, si el modelo no esta disponible o todavia no hay vectores "
					+ "del modelo vigente); `alcanza` es verdadero "
					+ "con al menos 2 pasajes sobre el `corte`. Consulta en blanco o de mas de 600 "
					+ "caracteres: 400.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pasajes encontrados, posiblemente ninguno"),
			@ApiResponse(responseCode = "400", description = "Consulta en blanco o de mas de 600 caracteres, "
					+ "o `k` fuera de 1 a 20",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = LibroController.P403,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = LibroController.P404_ORG,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = LibroController.P409_DESHABILITADA,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public BibliotecaBusquedaResponse buscar(
			@PathVariable long orgId, @RequestBody BibliotecaBusquedaRequest request) {
		int k = request.k() == null ? BusquedaService.K_POR_DEFECTO : request.k();
		return BibliotecaBusquedaResponse.de(
				busquedas.buscar(apiActor.current(), orgId, request.consulta(), k));
	}
}
