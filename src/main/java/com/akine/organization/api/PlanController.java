package com.akine.organization.api;

import com.akine.organization.api.dto.PlanResponse;
import com.akine.organization.application.PlanCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Catalogo comercial de planes.
 *
 * <p>Es catalogo global de la plataforma, no dato de un tenant: los planes no llevan
 * {@code organization_id} y este endpoint no depende del contexto de trabajo. Por eso tampoco
 * consulta el guard de autorizacion: no hay recurso ajeno que proteger, y quien puede llegar
 * hasta aca lo decide la cadena de seguridad.
 *
 * <p>Capa {@code api}: solo HTTP. La decision de que plan es contratable vive en
 * {@code application}.
 */
@RestController
@RequestMapping(path = "/api/v1/plans", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Planes", description = "Catalogo comercial: que incluye cada plan")
public class PlanController {

	private final PlanCatalogService planCatalogService;

	public PlanController(PlanCatalogService planCatalogService) {
		this.planCatalogService = planCatalogService;
	}

	@GetMapping
	@Operation(
			operationId = "listPlans",
			summary = "Catalogo de planes contratables",
			description = "Devuelve los planes vigentes con sus limites cuantitativos y las "
					+ "funcionalidades que habilitan. Es la fuente de la pantalla de contratacion "
					+ "y de upgrade: el frontend no mantiene su propia copia del catalogo, porque "
					+ "una copia desactualizada ofreceria planes que el backend ya no acepta. "
					+ "Los planes se referencian siempre por su codigo, nunca por id.")
	@ApiResponse(
			responseCode = "200",
			description = "Catalogo vigente. Lista vacia si no hay planes contratables",
			content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE,
					array = @ArraySchema(schema = @Schema(implementation = PlanResponse.class))))
	public ResponseEntity<List<PlanResponse>> catalog() {
		List<PlanResponse> planes = planCatalogService.catalog().stream()
				.map(PlanResponse::from)
				.toList();
		return ResponseEntity.ok(planes);
	}
}
