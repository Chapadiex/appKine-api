package com.akine.organization.api;

import com.akine.organization.api.dto.PermisosOtorgablesResponse;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.MembershipService;
import com.akine.platform.spi.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lo que la matriz de permisos admite por rol, publicado para la pantalla de colaboradores.
 *
 * <p>Vive aparte de {@link MembershipController} porque la pregunta es sobre un ROL y no sobre
 * un vinculo: la pantalla la hace antes de elegir a quien otorgar, y el recurso
 * {@code /memberships/{id}} no tiene donde colgarla. La regla es la de
 * {@code RolePermissions.otorgableComoGrant}, la misma que aplica el alta del grant y el
 * evaluador: no hay una segunda lista que mantener.
 */
@RestController
@RequestMapping(
		path = "/api/v1/organizations/{orgId}/roles",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Colaboradores",
		description = "Vinculos de las cuentas con la organizacion, sus roles y sus permisos")
public class RolPermisosController {

	private final MembershipService membershipService;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public RolPermisosController(
			MembershipService membershipService,
			AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.membershipService = membershipService;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping("/{roleCode}/permisos-otorgables")
	@Operation(
			operationId = "listPermisosOtorgablesDelRol",
			summary = "Permisos que se le pueden otorgar como adicionales a un rol",
			description = """
					Requiere colaborador:manage, el mismo permiso que otorgar. Devuelve los \
					codigos que la matriz admite como permiso adicional para ese rol, calculados \
					con la misma regla que valida el alta del grant: lo que no esta en la lista se \
					rechaza con 400 permiso-no-otorgable-al-rol.

					Incluye los otorgables que el rol ya tiene por base (paciente:manage del \
					CONSULTORIO_ADMIN, por ejemplo): la regla los admite porque son redundantes e \
					inocuos y rechazarlos romperia un grant previo a un cambio de rol.

					La organizacion de la ruta tiene que ser la del contexto activo: distinta es \
					404, sin contexto es 403. PLATFORM_ADMIN no es un rol de membership y responde \
					400, igual que un rol que no existe.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Permisos otorgables al rol. Lista vacia si no admite ninguno",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PermisosOtorgablesResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "El rol no existe o no es un rol de membership",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, sin contexto activo, o sin colaborador:manage",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La organizacion no existe o no es la del contexto del actor",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PermisosOtorgablesResponse> permisosOtorgables(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,

			@Parameter(description = "Rol de membership", example = "CONSULTORIO_ADMIN")
			@PathVariable String roleCode) {

		ApiActor actor = ApiActor.current(tenantContextHolder);
		var operando = authorizationGuard.actorSobre(
				actor.accountId(), actor.platformAdmin(), actor.contextOrganizationId(), orgId);

		return ResponseEntity.ok(new PermisosOtorgablesResponse(
				roleCode, membershipService.permisosOtorgables(operando, orgId, roleCode)));
	}
}
