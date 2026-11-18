package com.akine.organization.api;

import com.akine.organization.api.dto.DescartarSolicitudRequest;
import com.akine.organization.api.dto.SolicitudDePruebaPageResponse;
import com.akine.organization.api.dto.SolicitudDePruebaRequest;
import com.akine.organization.api.dto.SolicitudDePruebaResponse;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.SolicitudDePruebaService;
import com.akine.organization.domain.SolicitudDePrueba;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pedidos de prueba gratis (18/11/2026): la landing los manda sin sesion y la plataforma los
 * revisa en su bandeja. La aprobacion es el alta del centro ({@code POST /platform/organizations}).
 */
@RestController
@Tag(name = "Pedidos de prueba", description = "Prueba gratis de 30 dias pedida desde la landing")
public class SolicitudDePruebaController {

	private final SolicitudDePruebaService service;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public SolicitudDePruebaController(SolicitudDePruebaService service,
			AuthorizationGuard authorizationGuard, TenantContextHolder tenantContextHolder) {
		this.service = service;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@PostMapping(path = "/api/v1/public/solicitudes-de-prueba",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "pedirPruebaGratis",
			summary = "Pedir la prueba gratis de 30 dias",
			description = """
					Publico, sin sesion, limitado por IP. Responde siempre 202 con el pedido valido, \
					aunque el correo ya haya pedido antes: no revela nada. Idempotente por \
					Idempotency-Key. La plataforma lo ve en su bandeja y, si lo aprueba, crea el centro \
					e invita a ese correo como duenio.""")
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "Pedido recibido"),
			@ApiResponse(responseCode = "400", description = "Falta un dato, un dato invalido o ningun servicio elegido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "429", description = "Demasiados pedidos desde esta direccion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> pedir(
			@Parameter(description = "Clave unica del intento, para reintentar sin duplicar")
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody SolicitudDePruebaRequest request) {
		if (!request.kinesiologia() && !request.actividades()) {
			throw new IllegalArgumentException("Elegí al menos un servicio");
		}
		service.recibir(new SolicitudDePruebaService.Pedido(
				request.nombre(), request.apellido(), request.email(), request.telefono(),
				request.nombreCentro(), request.ciudad(), request.kinesiologia(), request.actividades(),
				request.sedes(), request.interesaIa(), request.comentario(), request.sitioWeb()),
				idempotencyKey);
		return ResponseEntity.status(HttpStatus.ACCEPTED).build();
	}

	@GetMapping(path = "/api/v1/platform/solicitudes-de-prueba", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "listarSolicitudesDePrueba",
			summary = "Bandeja de pedidos de prueba",
			description = "Los mas nuevos primero, filtrables por estado y por texto (centro, correo, "
					+ "nombre). Trae ademas el total de pendientes. Reservado a la administracion de "
					+ "plataforma. El tamano de pagina se acota a " + ApiPaging.TAMANO_MAXIMO + ".")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pagina de pedidos",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = SolicitudDePruebaPageResponse.class))),
			@ApiResponse(responseCode = "403", description = "Reservado a la administracion de plataforma",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SolicitudDePruebaPageResponse> listar(
			@Parameter(description = "Estado; sin el, todos", example = "PENDIENTE")
			@RequestParam(required = false) SolicitudDePrueba.Estado estado,
			@Parameter(description = "Texto a buscar", example = "movimiento")
			@RequestParam(required = false) String q,
			@Parameter(description = "Pagina, base cero", example = "0")
			@RequestParam(defaultValue = ApiPaging.PAGINA_POR_DEFECTO) int page,
			@Parameter(description = "Elementos por pagina", example = "10")
			@RequestParam(defaultValue = ApiPaging.TAMANO_POR_DEFECTO) int size) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());
		Page<SolicitudDePrueba> pagina = service.listar(estado, q,
				PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, ApiPaging.TAMANO_MAXIMO)));
		return ResponseEntity.ok(new SolicitudDePruebaPageResponse(
				pagina.getContent().stream().map(SolicitudDePruebaResponse::from).toList(),
				pagina.getNumber(), pagina.getSize(), pagina.getTotalElements(), pagina.getTotalPages(),
				service.pendientes()));
	}

	@GetMapping(path = "/api/v1/platform/solicitudes-de-prueba/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "verSolicitudDePrueba", summary = "Un pedido de prueba",
			description = "Reservado a la administracion de plataforma.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El pedido",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = SolicitudDePruebaResponse.class))),
			@ApiResponse(responseCode = "403", description = "Reservado a la administracion de plataforma",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SolicitudDePruebaResponse> ver(
			@Parameter(description = "Identificador del pedido", example = "1") @PathVariable long id) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());
		return ResponseEntity.ok(SolicitudDePruebaResponse.from(service.ver(id)));
	}

	@PostMapping(path = "/api/v1/platform/solicitudes-de-prueba/{id}/descartar",
			consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "descartarSolicitudDePrueba", summary = "Descartar un pedido de prueba",
			description = "Con motivo. No se borra: queda DESCARTADA. Reservado a la administracion de "
					+ "plataforma. Version vieja o pedido ya resuelto: 409 concurrent-modification.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pedido descartado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = SolicitudDePruebaResponse.class))),
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
	public ResponseEntity<SolicitudDePruebaResponse> descartar(
			@Parameter(description = "Identificador del pedido", example = "1") @PathVariable long id,
			@Valid @RequestBody DescartarSolicitudRequest request) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());
		return ResponseEntity.ok(SolicitudDePruebaResponse.from(
				service.descartar(id, request.motivo(), request.version(), actor.accountId())));
	}
}
