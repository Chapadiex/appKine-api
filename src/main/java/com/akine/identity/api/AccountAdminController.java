package com.akine.identity.api;

import com.akine.identity.api.dto.AccountResponse;
import com.akine.identity.api.dto.AccountStateChangeRequest;
import com.akine.identity.application.AccountAdminService;
import com.akine.identity.application.AccountView;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bloqueo, desbloqueo y baja logica de cuentas (RF-M02-005).
 *
 * <h2>Sobre quien se puede operar</h2>
 *
 * <p>La cuenta es una identidad global sin {@code organization_id} (ADR-0019), asi que el
 * aislamiento no lo da una columna: lo da esta capa mas
 * {@code AccountAdminService.autorizar}, que exige que el actor administre la organizacion de
 * su contexto activo y que la cuenta objetivo <b>tenga membership vigente en esa misma
 * organizacion</b>. Si no la tiene —cuenta de otro tenant, o inexistente— la respuesta es
 * <b>404</b>, nunca 403 y nunca 200: distinguirlas dejaria enumerar las cuentas de
 * organizaciones ajenas probando ids consecutivos.
 *
 * <p>Por eso no hay {@code GET /accounts} ni ninguna busqueda por email: cualquier listado
 * cross-tenant de identidades seria exactamente el padron que ADR-0018 y ADR-0019 evitan.
 *
 * <h2>Que significa "bloquear" de verdad</h2>
 *
 * <p>La transicion revoca <b>todos los refresh vivos de la cuenta en la misma transaccion</b>:
 * no es un flag que se evalua mas adelante. Lo que sobrevive es el access token ya emitido,
 * hasta diez minutos (ADR-0017). Esa ventana esta aceptada y documentada: cuando alguien
 * reporte "lo bloquee y todavia entra", la respuesta es que no es un bug, y a los diez minutos
 * deja de entrar.
 *
 * <p>La desactivacion es <b>terminal</b> y es baja logica: la fila no se borra, porque los
 * historicos que la referencian tienen que seguir siendo legibles (RN-M02-004).
 *
 * <h2>Autorizacion interina</h2>
 *
 * <p>Hoy las tres operaciones piden lo mismo: administrar la organizacion. La matriz de
 * permisos de 01.03 las va a distinguir. La verificacion de <i>sobre quien</i> —la membership—
 * no se relaja en esa etapa: la matriz decide que puede hacer el actor, la membership sigue
 * decidiendo sobre quien.
 */
@RestController
@RequestMapping(path = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Administracion de cuentas",
		description = "Bloqueo, desbloqueo y baja logica de cuentas de la propia organizacion")
public class AccountAdminController {

	private final AccountAdminService accountAdminService;
	private final TenantContextHolder tenantContextHolder;

	public AccountAdminController(
			AccountAdminService accountAdminService,
			TenantContextHolder tenantContextHolder) {
		this.accountAdminService = accountAdminService;
		this.tenantContextHolder = tenantContextHolder;
	}

	@PostMapping(path = "/{accountId}/block", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "blockAccount",
			summary = "Bloquear una cuenta",
			description = """
					Suspende el acceso y revoca todas las sesiones vivas de la cuenta en la misma \
					transaccion. Es reversible con /unblock.

					El access token ya emitido sigue siendo valido hasta diez minutos: la \
					revocacion es real sobre el refresh, no sobre el access en vuelo (ADR-0017). \
					No es un bug.

					La persona bloqueada NO se entera por la aplicacion: su login sigue \
					respondiendo el mismo 401 que una contrasena incorrecta (ADR-0018). Se entera \
					por el canal administrativo.

					El motivo es obligatorio y queda en la auditoria. Bloquear una cuenta ya \
					bloqueada responde 409, no un 200 silencioso: dos administradores operando a \
					la vez tienen que enterarse de que el otro llego primero.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Cuenta bloqueada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AccountResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada, o el actor no administra su "
							+ "organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La cuenta no existe o no tiene membership en la organizacion "
							+ "del actor. Los dos casos responden igual",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La cuenta no admite bloquearse desde su estado actual",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AccountResponse> block(
			@Parameter(description = "Identificador de la cuenta", example = "42")
			@PathVariable long accountId,

			@Valid @RequestBody AccountStateChangeRequest request) {

		AccountView cuenta = accountAdminService.bloquear(actor(), accountId, request.reason());
		return ResponseEntity.ok(AccountResponse.from(cuenta));
	}

	@PostMapping(path = "/{accountId}/unblock", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "unblockAccount",
			summary = "Devolver el acceso a una cuenta bloqueada",
			description = """
					Vuelve la cuenta a ACTIVA. No revoca nada: llego aca sin sesiones vivas, \
					porque el bloqueo ya las corto. La persona tiene que volver a loguearse.

					El motivo se exige igual, aunque la maquina de estados no lo pida para este \
					destino: devolverle el acceso a alguien es tan auditable como quitarselo, y \
					"por que lo desbloquearon" aparece en las mismas revisiones que la otra \
					pregunta.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Cuenta desbloqueada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AccountResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada, o el actor no administra su "
							+ "organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La cuenta no existe o no es de la organizacion del actor",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La cuenta no estaba bloqueada",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AccountResponse> unblock(
			@Parameter(description = "Identificador de la cuenta", example = "42")
			@PathVariable long accountId,

			@Valid @RequestBody AccountStateChangeRequest request) {

		AccountView cuenta = accountAdminService.desbloquear(actor(), accountId, request.reason());
		return ResponseEntity.ok(AccountResponse.from(cuenta));
	}

	@PostMapping(path = "/{accountId}/deactivate", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "deactivateAccount",
			summary = "Baja logica de la cuenta",
			description = """
					Estado TERMINAL: desde la aplicacion no hay vuelta. Revoca todas las sesiones \
					vivas en la misma transaccion.

					Es baja LOGICA: la fila no se borra. Los turnos, sesiones y movimientos que \
					referencian a esta cuenta tienen que seguir siendo legibles (RN-M02-004, \
					regla maestra 10). Lo que queda es active = 0 con deleted_at.

					Como es irreversible, el motivo es lo unico que va a quedar para explicarla: \
					escribirlo con precision no es formalidad.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Cuenta desactivada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AccountResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada, o el actor no administra su "
							+ "organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La cuenta no existe o no es de la organizacion del actor",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La cuenta ya estaba desactivada",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AccountResponse> deactivate(
			@Parameter(description = "Identificador de la cuenta", example = "42")
			@PathVariable long accountId,

			@Valid @RequestBody AccountStateChangeRequest request) {

		AccountView cuenta = accountAdminService.desactivar(actor(), accountId, request.reason());
		return ResponseEntity.ok(AccountResponse.from(cuenta));
	}

	/**
	 * Traduce el request al actor que espera {@code application}.
	 *
	 * <p>La organizacion sale del {@code TenantContextHolder} —o sea del contexto que
	 * {@code TenantContextFilter} revalido contra la base— y jamas de un claim del token ni de
	 * un parametro: un claim dice que contexto se pide, no cual es legitimo (RN-M01-003).
	 */
	private AccountAdminService.Actor actor() {
		IdentityApiActor actor = IdentityApiActor.current(tenantContextHolder);
		return new AccountAdminService.Actor(
				actor.accountId(), actor.contextOrganizationId(), actor.platformAdmin());
	}
}
