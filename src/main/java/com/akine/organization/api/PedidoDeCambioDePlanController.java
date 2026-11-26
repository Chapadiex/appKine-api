package com.akine.organization.api;

import com.akine.organization.api.dto.PedidoDeCambioDePlanPageResponse;
import com.akine.organization.api.dto.PedidoDeCambioDePlanRequest;
import com.akine.organization.api.dto.PedidoDeCambioDePlanResponse;
import com.akine.organization.api.dto.RechazarPedidoDePlanRequest;
import com.akine.organization.api.dto.VersionDePedidoRequest;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.PedidoDeCambioDePlanService;
import com.akine.organization.application.PedidoDeCambioDePlanView;
import com.akine.organization.domain.PedidoDeCambioDePlan;
import com.akine.platform.spi.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pedidos de cambio de plan (25/11/2026, docs/diseno/AKINE-pedido-de-cambio-de-plan.md).
 *
 * <p>Las rutas del centro cuelgan de {@code /subscription} a proposito: el filtro de tenant deja
 * escribir ahi con la suscripcion suspendida, que es justo cuando un centro puede querer cambiar.
 */
@RestController
@Tag(name = "Pedidos de cambio de plan", description = "El centro pide otro plan; la plataforma aprueba o rechaza")
public class PedidoDeCambioDePlanController {

	private static final String RUTA_CENTRO = "/api/v1/organizations/{orgId}/subscription/pedido-de-cambio";
	private static final String RUTA_PLATAFORMA = "/api/v1/platform/pedidos-de-cambio-de-plan";

	private final PedidoDeCambioDePlanService service;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public PedidoDeCambioDePlanController(PedidoDeCambioDePlanService service,
			AuthorizationGuard authorizationGuard, TenantContextHolder tenantContextHolder) {
		this.service = service;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	// --- Centro ---------------------------------------------------------------------------------

	@GetMapping(path = RUTA_CENTRO, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "verPedidoDeCambioDePlan", summary = "Ultimo pedido de cambio de plan del centro",
			description = "En cualquier estado; 204 si el centro nunca pidio. Requiere tenant:update.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El ultimo pedido",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PedidoDeCambioDePlanResponse.class))),
			@ApiResponse(responseCode = "204", description = "El centro nunca pidio un cambio"),
			@ApiResponse(responseCode = "403", description = "El actor no es ORG_ADMIN de la organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, esta dada de baja, o es de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PedidoDeCambioDePlanResponse> ver(
			@Parameter(description = "Identificador de la organizacion", example = "1") @PathVariable long orgId) {
		exigirEditor(orgId);
		return service.ultimo(orgId)
				.map(p -> ResponseEntity.ok(PedidoDeCambioDePlanResponse.from(p)))
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PostMapping(path = RUTA_CENTRO, consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "pedirCambioDePlan", summary = "Pedir un cambio de plan",
			description = """
					El centro pide una contratacion completa (plan, servicios y adicionales) para subir o \
					bajar. Queda PENDIENTE hasta que la plataforma la apruebe o la rechace. Requiere \
					tenant:update. Igual a lo contratado: 400 pedido-sin-cambios. Ya hay uno pendiente: \
					409 pedido-de-plan-pendiente.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Pedido guardado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PedidoDeCambioDePlanResponse.class))),
			@ApiResponse(responseCode = "400", description = "Sin servicios, un campo invalido, o igual a lo contratado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "El actor no es ORG_ADMIN de la organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La organizacion o el plan no existen, o el plan no se ofrece",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Ya hay un pedido esperando respuesta",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PedidoDeCambioDePlanResponse> pedir(
			@Parameter(description = "Identificador de la organizacion", example = "1") @PathVariable long orgId,
			@Valid @RequestBody PedidoDeCambioDePlanRequest request) {
		ApiActor actor = exigirEditor(orgId);
		PedidoDeCambioDePlanView pedido = service.pedir(orgId, request.planCode(),
				ContratacionController.servicios(request.servicios()), ContratacionController.adicionales(request.adicionales()),
				request.comentario(), actor.accountId());
		return ResponseEntity.status(HttpStatus.CREATED).body(PedidoDeCambioDePlanResponse.from(pedido));
	}

	@PostMapping(path = RUTA_CENTRO + "/{id}/cancelar", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "cancelarPedidoDeCambioDePlan", summary = "Cancelar el pedido de cambio de plan",
			description = "Solo mientras esta PENDIENTE. Requiere tenant:update. Ya resuelto o version vieja: "
					+ "409 concurrent-modification.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pedido cancelado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PedidoDeCambioDePlanResponse.class))),
			@ApiResponse(responseCode = "403", description = "El actor no es ORG_ADMIN de la organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe o es de otro centro",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Ya resuelto o version vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PedidoDeCambioDePlanResponse> cancelar(
			@Parameter(description = "Identificador de la organizacion", example = "1") @PathVariable long orgId,
			@Parameter(description = "Identificador del pedido", example = "1") @PathVariable long id,
			@Valid @RequestBody VersionDePedidoRequest request) {
		ApiActor actor = exigirEditor(orgId);
		return ResponseEntity.ok(PedidoDeCambioDePlanResponse.from(
				service.cancelar(orgId, id, request.version(), actor.accountId())));
	}

	// --- Plataforma -----------------------------------------------------------------------------

	@GetMapping(path = RUTA_PLATAFORMA, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "listarPedidosDeCambioDePlan", summary = "Bandeja de pedidos de cambio de plan",
			description = "Los mas nuevos primero, filtrables por estado y por nombre del centro. Trae ademas "
					+ "el total de pendientes. Reservado a la administracion de plataforma. El tamano de "
					+ "pagina se acota a " + ApiPaging.TAMANO_MAXIMO + ".")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pagina de pedidos",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PedidoDeCambioDePlanPageResponse.class))),
			@ApiResponse(responseCode = "403", description = "Reservado a la administracion de plataforma",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PedidoDeCambioDePlanPageResponse> listar(
			@Parameter(description = "Estado; sin el, todos", example = "PENDIENTE")
			@RequestParam(required = false) PedidoDeCambioDePlan.Estado estado,
			@Parameter(description = "Texto a buscar en el nombre del centro", example = "movimiento")
			@RequestParam(required = false) String q,
			@Parameter(description = "Pagina, base cero", example = "0")
			@RequestParam(defaultValue = ApiPaging.PAGINA_POR_DEFECTO) int page,
			@Parameter(description = "Elementos por pagina", example = "10")
			@RequestParam(defaultValue = ApiPaging.TAMANO_POR_DEFECTO) int size) {
		exigirPlataforma();
		Page<PedidoDeCambioDePlanView> pagina = service.listar(estado, q,
				PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, ApiPaging.TAMANO_MAXIMO)));
		return ResponseEntity.ok(new PedidoDeCambioDePlanPageResponse(
				pagina.getContent().stream().map(PedidoDeCambioDePlanResponse::from).toList(),
				pagina.getNumber(), pagina.getSize(), pagina.getTotalElements(), pagina.getTotalPages(),
				service.pendientes()));
	}

	@PostMapping(path = RUTA_PLATAFORMA + "/{id}/aprobar", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "aprobarPedidoDeCambioDePlan", summary = "Aprobar un pedido de cambio de plan",
			description = """
					Aplica la contratacion pedida, como updateContratacionDeCentro, sin pasar una prueba a \
					ACTIVA. Bajar de plan no borra nada: solo rechaza las altas que excedan el tope nuevo. \
					Reservado a la administracion de plataforma. Ya resuelto o version vieja: 409 \
					concurrent-modification.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pedido aprobado y contratacion aplicada",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PedidoDeCambioDePlanResponse.class))),
			@ApiResponse(responseCode = "403", description = "Reservado a la administracion de plataforma",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El pedido no existe, o el plan ya no se ofrece",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Ya resuelto, version vieja, suscripcion cancelada, o "
					+ "el centro supera los topes del plan pedido (pedido-excede-limites, con excesos)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PedidoDeCambioDePlanResponse> aprobar(
			@Parameter(description = "Identificador del pedido", example = "1") @PathVariable long id,
			@Valid @RequestBody VersionDePedidoRequest request) {
		ApiActor actor = exigirPlataforma();
		return ResponseEntity.ok(PedidoDeCambioDePlanResponse.from(
				service.aprobar(id, request.version(), actor.accountId())));
	}

	@PostMapping(path = RUTA_PLATAFORMA + "/{id}/rechazar", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "rechazarPedidoDeCambioDePlan", summary = "Rechazar un pedido de cambio de plan",
			description = "Con motivo, que ve el centro. No cambia la contratacion. Reservado a la "
					+ "administracion de plataforma. Ya resuelto o version vieja: 409 concurrent-modification.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pedido rechazado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PedidoDeCambioDePlanResponse.class))),
			@ApiResponse(responseCode = "400", description = "Falta el motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Reservado a la administracion de plataforma",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Ya resuelto o version vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PedidoDeCambioDePlanResponse> rechazar(
			@Parameter(description = "Identificador del pedido", example = "1") @PathVariable long id,
			@Valid @RequestBody RechazarPedidoDePlanRequest request) {
		ApiActor actor = exigirPlataforma();
		return ResponseEntity.ok(PedidoDeCambioDePlanResponse.from(
				service.rechazar(id, request.motivo(), request.version(), actor.accountId())));
	}

	private ApiActor exigirEditor(long orgId) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requireOrgDataEditor(
				actor.accountId(), orgId, actor.contextOrganizationId(), actor.platformAdmin());
		return actor;
	}

	private ApiActor exigirPlataforma() {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());
		return actor;
	}

}
