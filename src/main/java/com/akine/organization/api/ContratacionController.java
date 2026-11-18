package com.akine.organization.api;

import com.akine.organization.api.dto.ContratacionResponse;
import com.akine.organization.api.dto.UpdateContratacionRequest;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.ContratacionService;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;
import com.akine.platform.spi.tenant.TenantContextHolder;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Contratacion de un centro (18/11/2026, docs/diseno/AKINE-landing-y-ofertas.md): el centro la
 * lee para saber que mostrar; la plataforma la lee y la cambia.
 */
@RestController
@Tag(name = "Contratacion", description = "Plan, servicios, adicionales y prueba gratis de un centro")
public class ContratacionController {

	private final ContratacionService contratacionService;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public ContratacionController(
			ContratacionService contratacionService,
			AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.contratacionService = contratacionService;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping(path = "/api/v1/organizations/{orgId}/contratacion", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "getContratacionDeOrganizacion",
			summary = "Lo que el centro tiene contratado",
			description = "Plan, servicios, adicionales y estado de la prueba gratis. Requiere ser miembro "
					+ "vigente. La pantalla lo usa para mostrar solo lo contratado; quien decide es el "
					+ "servidor, que responde 409 feature-not-available a una operacion de un servicio no "
					+ "contratado.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Contratacion del centro",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ContratacionResponse.class))),
			@ApiResponse(responseCode = "403", description = "No hay sesion autenticada o falta el contexto",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, esta dada de baja, o es de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ContratacionResponse> ver(
			@Parameter(description = "Identificador de la organizacion", example = "1") @PathVariable long orgId) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requireMember(
				actor.accountId(), orgId, actor.contextOrganizationId(), actor.platformAdmin());
		return ResponseEntity.ok(ContratacionResponse.from(contratacionService.ver(orgId)));
	}

	@GetMapping(path = "/api/v1/platform/organizations/{orgId}/contratacion",
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "getContratacionDeCentroEnPlataforma",
			summary = "Contratacion de un centro, para la plataforma",
			description = "La misma lectura que ve el centro, reservada a la administracion de plataforma.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Contratacion del centro",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ContratacionResponse.class))),
			@ApiResponse(responseCode = "403", description = "Reservado a la administracion de plataforma",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El centro no existe",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ContratacionResponse> verEnPlataforma(
			@Parameter(description = "Identificador de la organizacion", example = "1") @PathVariable long orgId) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());
		return ResponseEntity.ok(ContratacionResponse.from(contratacionService.ver(orgId)));
	}

	@PutMapping(path = "/api/v1/platform/organizations/{orgId}/contratacion",
			consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateContratacionDeCentro",
			summary = "Cambiar la contratacion de un centro",
			description = """
					Reemplazo completo: plan ofrecido, servicios (al menos uno) y adicionales. Con \
					contratar=true una suscripcion en PRUEBA pasa a ACTIVA. Bajar de plan no toca lo que \
					ya existe: solo rechaza las altas que excedan el tope nuevo. Reservado a la \
					administracion de plataforma. Version vieja: 409 concurrent-modification.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Contratacion actualizada",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ContratacionResponse.class))),
			@ApiResponse(responseCode = "400", description = "Sin servicios, o un campo fuera de vocabulario",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Reservado a la administracion de plataforma",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El centro o el plan no existen, o el plan no se ofrece",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La version quedo vieja, o la suscripcion esta "
					+ "suspendida o cancelada",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ContratacionResponse> cambiar(
			@Parameter(description = "Identificador de la organizacion", example = "1") @PathVariable long orgId,
			@Valid @RequestBody UpdateContratacionRequest request) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());
		return ResponseEntity.ok(ContratacionResponse.from(contratacionService.cambiar(
				orgId, request.planCode(), servicios(request.servicios()), adicionales(request.adicionales()),
				request.contratar(), request.version(), actor.accountId())));
	}

	static Set<Servicio> servicios(List<Servicio> lista) {
		return lista == null || lista.isEmpty() ? EnumSet.noneOf(Servicio.class) : EnumSet.copyOf(lista);
	}

	static Set<Adicional> adicionales(List<Adicional> lista) {
		return lista == null || lista.isEmpty() ? EnumSet.noneOf(Adicional.class) : EnumSet.copyOf(lista);
	}
}
