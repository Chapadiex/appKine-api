package com.akine.organization.api;

import com.akine.organization.api.dto.CobroDeSuscripcionResponse;
import com.akine.organization.api.dto.RegistrarCobroRequest;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.CobroDeSuscripcionService;
import com.akine.platform.spi.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Cobro mensual de la suscripcion de un centro (19/11/2026, docs/diseno/AKINE-cobro-mensual.md).
 *
 * <p>Lo registra a mano la administracion de plataforma. Como el resto de {@code /platform/**},
 * responde 403 y no 404 a quien no es de plataforma.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/organizations/{orgId}/cobros", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Plataforma",
		description = "Administracion de la plataforma: acceso de soporte y roles globales")
public class CobroDeSuscripcionController {

	private final CobroDeSuscripcionService cobros;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public CobroDeSuscripcionController(
			CobroDeSuscripcionService cobros,
			AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.cobros = cobros;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "registrarCobroDeSuscripcion",
			summary = "Registrar un cobro de la suscripcion de un centro",
			description = """
					Extiende el periodo pagado del centro en la cantidad de meses (1 a 12). El periodo \
					arranca en el vencimiento anterior si todavia no llego (pagar antes conserva el \
					ciclo) o ahora si ya paso o es el primer cobro, y suma meses calendario en la zona \
					del centro. El centro vuelve a operar normalmente en el acto.

					Sobre una suscripcion en PRUEBA, el cobro la pasa a ACTIVA. Sobre una SUSPENDIDA o \
					CANCELADA guardada responde 409 subscription-suspended: primero se reactiva por la \
					transicion de siempre. Reservado a la administracion de plataforma.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Cobro registrado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CobroDeSuscripcionResponse.class))),
			@ApiResponse(responseCode = "400", description = "Meses fuera de 1 a 12 o nota de mas de 200 caracteres",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Reservado a la administracion de plataforma",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La organizacion no existe o esta dada de baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La suscripcion esta suspendida o cancelada, o la "
					+ "modifico otra operacion al mismo tiempo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CobroDeSuscripcionResponse> registrar(
			@Parameter(description = "Identificador de la organizacion", example = "1") @PathVariable long orgId,
			@Valid @RequestBody RegistrarCobroRequest request) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());
		return ResponseEntity.status(HttpStatus.CREATED).body(CobroDeSuscripcionResponse.from(
				cobros.registrar(orgId, request.meses(), request.nota(), actor.accountId())));
	}

	@GetMapping
	@Operation(
			operationId = "listCobrosDeSuscripcion",
			summary = "Historial de cobros de la suscripcion de un centro",
			description = """
					Todos los cobros registrados al centro, el mas nuevo primero. El vencimiento \
					vigente es el periodoHasta del primero, y tambien viaja como pagadoHasta en el \
					listado de centros y en la contratacion. Reservado a la administracion de \
					plataforma.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Cobros, el mas nuevo primero. Vacio si no tiene",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(schema = @Schema(implementation = CobroDeSuscripcionResponse.class)))),
			@ApiResponse(responseCode = "403", description = "Reservado a la administracion de plataforma",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La organizacion no existe o esta dada de baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<CobroDeSuscripcionResponse>> historial(
			@Parameter(description = "Identificador de la organizacion", example = "1") @PathVariable long orgId) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());
		return ResponseEntity.ok(cobros.historial(orgId).stream().map(CobroDeSuscripcionResponse::from).toList());
	}
}
