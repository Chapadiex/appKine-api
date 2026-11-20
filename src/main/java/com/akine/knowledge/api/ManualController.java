package com.akine.knowledge.api;

import com.akine.knowledge.api.dto.FichaDelManualResponse;
import com.akine.knowledge.api.dto.IndiceDelManualResponse;
import com.akine.knowledge.api.dto.PasajeDelManualResponse;
import com.akine.knowledge.application.OperatingActor;
import com.akine.knowledge.application.manual.ManualService;
import com.akine.knowledge.domain.manual.FichaDelManual;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El manual de usuario contextual (docs/diseno/AKINE-manual-de-usuario.md). Lectura con cualquier
 * sesion con organizacion y sede en contexto; cada persona ve las fichas de lo que puede usar.
 */
@RestController
@Validated
@RequestMapping(
		path = "/api/v1/manual",
		produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@Tag(name = "Manual", description = "Manual de usuario por pantalla")
public class ManualController {

	static final String P403 = "Sin organizacion y sede en contexto";

	private final ManualService manual;
	private final KnowledgeApiActor apiActor;

	public ManualController(ManualService manual, KnowledgeApiActor apiActor) {
		this.manual = manual;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(operationId = "verIndiceDelManual", summary = "Indice del manual",
			description = "Las fichas que la persona puede ver en la sede activa, por grupo del menu, con los "
					+ "titulos de sus tareas.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Indice, posiblemente vacio"),
			@ApiResponse(responseCode = "403", description = P403,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public IndiceDelManualResponse indice() {
		return IndiceDelManualResponse.de(manual.indice(apiActor.current()));
	}

	@GetMapping("/fichas/{id}")
	@Operation(operationId = "verFichaDelManual", summary = "Ficha del manual",
			description = "Una ficha completa. 404 si no existe o la persona no la puede ver.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La ficha"),
			@ApiResponse(responseCode = "403", description = P403,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "`not-found`: no existe o no es visible",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public FichaDelManualResponse ficha(@PathVariable String id) {
		OperatingActor actor = apiActor.current();
		return respuesta(actor, manual.ficha(actor, id));
	}

	@GetMapping("/contexto")
	@Operation(operationId = "verFichaDeLaRuta", summary = "Ficha de una pantalla",
			description = "La ficha de la ruta del frontend que la persona esta mirando; los segmentos con "
					+ "parametros y la query se resuelven. 204 si la pantalla no tiene ficha visible.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La ficha de la pantalla"),
			@ApiResponse(responseCode = "204", description = "La pantalla no tiene ficha"),
			@ApiResponse(responseCode = "400", description = "Ruta en blanco o de mas de 300 caracteres",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = P403,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<FichaDelManualResponse> deLaRuta(
			@Parameter(description = "Ruta del frontend, por ejemplo /caja o /pacientes/12/resumen")
			@RequestParam @NotBlank(message = "La ruta es obligatoria")
			@Size(max = 300, message = "La ruta admite hasta 300 caracteres") String ruta) {
		OperatingActor actor = apiActor.current();
		return manual.deLaRuta(actor, ruta)
				.map(ficha -> ResponseEntity.ok(respuesta(actor, ficha)))
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@GetMapping("/busqueda")
	@Operation(operationId = "buscarEnElManual", summary = "Buscar en el manual",
			description = "Hasta 10 pasajes de las fichas visibles, del mas al menos parecido. Con `ruta` se "
					+ "prefieren los de la pantalla actual.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pasajes, posiblemente ninguno"),
			@ApiResponse(responseCode = "400", description = "Consulta de menos de 2 o mas de 200 caracteres",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = P403,
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public List<PasajeDelManualResponse> buscar(
			@RequestParam @NotBlank(message = "La consulta es obligatoria")
			@Size(min = 2, max = 200, message = "La consulta admite de 2 a 200 caracteres") String q,
			@RequestParam(required = false) @Size(max = 300, message = "La ruta admite hasta 300 caracteres")
			String ruta) {
		return manual.buscar(apiActor.current(), q.strip(), ruta).stream().map(PasajeDelManualResponse::de).toList();
	}

	private FichaDelManualResponse respuesta(OperatingActor actor, FichaDelManual ficha) {
		Map<String, FichaDelManual> visibles = manual.indice(actor).stream()
				.collect(Collectors.toMap(FichaDelManual::id, Function.identity()));
		return FichaDelManualResponse.de(ficha, visibles);
	}
}
