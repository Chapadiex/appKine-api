package com.akine.organization.api;

import com.akine.organization.api.dto.GrantPlatformRoleRequest;
import com.akine.organization.api.dto.PlatformRoleResponse;
import com.akine.organization.application.PlatformRoleService;
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
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Roles de administracion de plataforma (D-2).
 *
 * <h2>El primero no sale de aca</h2>
 * Otorgar exige ya ser administrador de plataforma, asi que arrancar desde cero por HTTP es
 * imposible por construccion. El primero lo pone el seed de migracion. Eso no es una limitacion:
 * es lo que impide que cualquiera se autoproclame administrador de la plataforma entera.
 *
 * <h2>Por que el alta entra por id de cuenta y no por email</h2>
 * {@code cuenta} es propiedad de {@code identity} y {@code organization} no compila contra ese
 * modulo —ArchUnit rechaza la flecha—. A diferencia del alta de colaborador, aca no hay un
 * {@code spi} que permita mover el endpoint del lado de {@code identity}:
 * {@code PlatformRoleService} vive en {@code organization.application}, que es privado del
 * modulo. Se declara como deuda en el reporte de la etapa; entretanto el id es la entrada, y
 * para el permiso mas alto del sistema escribirlo exacto es la friccion correcta.
 *
 * <h2>Revocar no tiene invariante de "ultimo"</h2>
 * Deliberado. El invariante de ultimo administrador es del tenant, donde quedarse sin ninguno
 * deja a un cliente encerrado afuera de su propio centro. En la plataforma el rescate existe
 * —el seed, reproducible— y un invariante aca impediria revocar de urgencia una cuenta
 * comprometida, que es el caso en el que revocar mas importa.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/roles", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Plataforma",
		description = "Administracion de la plataforma: acceso de soporte y roles globales")
public class PlatformRoleController {

	private final PlatformRoleService platformRoleService;
	private final TenantContextHolder tenantContextHolder;

	public PlatformRoleController(
			PlatformRoleService platformRoleService,
			TenantContextHolder tenantContextHolder) {
		this.platformRoleService = platformRoleService;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping
	@Operation(
			operationId = "listPlatformRoles",
			summary = "Roles de plataforma vigentes",
			description = """
					Reservado a la administracion de plataforma. Devuelve los vigentes; los \
					revocados quedan en la fila para la auditoria, no en este listado.

					No hay paginado: la cantidad de administradores de plataforma se cuenta con \
					los dedos de una mano, y si algun dia no fuera asi, eso seria el problema.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Roles de plataforma vigentes",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = PlatformRoleResponse.class)))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada, o la cuenta no administra la "
							+ "plataforma. Nunca 401",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<PlatformRoleResponse>> list() {
		List<PlatformRoleResponse> roles = platformRoleService.list(actorAccountId()).stream()
				.map(PlatformRoleResponse::from)
				.toList();

		return ResponseEntity.ok(roles);
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "grantPlatformRole",
			summary = "Otorgar el rol de plataforma a una cuenta",
			description = """
					Reservado a la administracion de plataforma. Motivo obligatorio: es el permiso \
					mas alto del sistema.

					La existencia de la cuenta NO se verifica: cuenta es propiedad del modulo de \
					identidad y este modulo no puede consultarla. Un accountId inexistente falla \
					contra la clave foranea y se responde como una solicitud invalida.

					Otorgarlo dos veces responde 409: dos administradores operando a la vez tienen \
					que enterarse de que el otro llego primero.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Rol de plataforma otorgado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlatformRoleResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo o la cuenta destino",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, o la cuenta no administra la plataforma",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Esa cuenta ya tiene el rol de plataforma vigente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PlatformRoleResponse> grant(
			@Valid @RequestBody GrantPlatformRoleRequest request) {

		PlatformRoleResponse otorgado;
		try {
			otorgado = PlatformRoleResponse.from(platformRoleService.grant(
					actorAccountId(), request.accountId(), request.reason()));
		} catch (IllegalStateException yaLoTiene) {
			// El servicio traduce la clave duplicada a IllegalStateException, que sin este
			// puente cae en la red de contencion del advice global y devuelve 500 a un
			// conflicto perfectamente esperable. Ver PlatformRoleAlreadyGrantedException.
			throw new PlatformRoleAlreadyGrantedException(yaLoTiene);
		}

		return ResponseEntity.status(201).body(otorgado);
	}

	@DeleteMapping("/{platformRoleId}")
	@Operation(
			operationId = "revokePlatformRole",
			summary = "Revocar un rol de plataforma",
			description = """
					Reservado a la administracion de plataforma. Baja LOGICA: la fila queda con su \
					autor y su motivo.

					No impide quedarse sin ningun administrador de plataforma, y es deliberado: \
					un invariante aca impediria revocar de urgencia una cuenta comprometida, que \
					es el caso en el que revocar mas importa. El rescate es el seed de migracion.

					El motivo va como parametro y no en el cuerpo porque un DELETE con cuerpo no \
					esta garantizado de punta a punta por proxies y clientes HTTP.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Rol de plataforma revocado"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, o la cuenta no administra la plataforma",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El rol no existe o ya estaba revocado",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> revoke(
			@Parameter(description = "Identificador del otorgamiento", example = "2")
			@PathVariable long platformRoleId,

			@Parameter(description = "Motivo declarado de la revocacion. Queda en la auditoria")
			@RequestParam(required = false) String reason) {

		platformRoleService.revoke(actorAccountId(), platformRoleId, reason);
		return ResponseEntity.noContent().build();
	}

	/** Cuenta que opera. El rol de plataforma lo revalida el servicio contra la base. */
	private long actorAccountId() {
		return ApiActor.current(tenantContextHolder).accountId();
	}
}
