package com.akine.organization.api;

import com.akine.organization.api.dto.PlanChangeRequest;
import com.akine.organization.api.dto.PlanChangeResponse;
import com.akine.organization.api.dto.SubscriptionResponse;
import com.akine.organization.api.dto.SubscriptionTransitionPageResponse;
import com.akine.organization.api.dto.SubscriptionTransitionRequest;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.SubscriptionService;
import com.akine.platform.spi.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Suscripcion del tenant: estado, historico y cambio de plan.
 *
 * <h2>Por que las transiciones son un POST a una subcoleccion y no un PUT del estado</h2>
 * Una transicion es un hecho que ocurre —con motivo, actor e instante— y queda registrado en un
 * historico append-only. Modelarla como {@code PUT {status}} la haria parecer la edicion de un
 * campo, invitaria a reintentos que "corrigen" el estado sin dejar rastro, y no tendria donde
 * poner el motivo. {@code POST /transitions} dice lo que realmente pasa: se agrega un hecho.
 *
 * <h2>Estas rutas siguen disponibles con la suscripcion suspendida</h2>
 * Son justamente las que hacen falta para SALIR de la suspension. Si el filtro de contexto las
 * bloqueara como cualquier otra mutacion, suspender una organizacion la dejaria imposible de
 * reactivar y la suspension seria terminal de hecho, que es lo contrario de lo que dicen las
 * reglas: suspender bloquea, jamas destruye.
 *
 * <p>Capa {@code api}: solo HTTP. La maquina de estados y las reglas de plan viven en el
 * dominio y en {@code application}.
 */
@RestController
@RequestMapping(
		path = "/api/v1/organizations/{orgId}/subscription",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Suscripciones", description = "Plan contratado, estado de la suscripcion e historico")
public class SubscriptionController {

	private final SubscriptionService subscriptionService;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public SubscriptionController(
			SubscriptionService subscriptionService,
			AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.subscriptionService = subscriptionService;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping
	@Operation(
			operationId = "getSubscription",
			summary = "Suscripcion vigente del tenant",
			description = """
					Devuelve el plan contratado, el estado de la suscripcion, el consumo actual de \
					cada limite y los estados a los que se puede transicionar desde el actual.

					Los allowedTargets existen para que el frontend no replique la maquina de \
					estados: pinta las acciones que el backend dice que existen. Una copia de las \
					reglas en el cliente obligaria a desplegar los dos lados a la vez para no \
					ofrecer botones que fallan.

					Requiere administrar esa organizacion. Una organizacion inexistente o de otro \
					tenant se responden igual con 404.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Suscripcion vigente con su consumo de limites",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = SubscriptionResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "El actor esta en el tenant correcto pero no lo administra",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La organizacion no existe, esta dada de baja, o es de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SubscriptionResponse> find(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId) {

		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requireOrgAdmin(
				actor.accountId(), orgId, actor.contextOrganizationId(), actor.platformAdmin());

		return ResponseEntity.ok(SubscriptionResponse.from(subscriptionService.find(orgId)));
	}

	@PostMapping(path = "/transitions", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createSubscriptionTransition",
			summary = "Aplicar una transicion de estado a la suscripcion",
			description = """
					Registra el cambio de estado y lo agrega al historico. Reservado a la \
					administracion de plataforma: suspender o cancelar un centro es una decision \
					comercial, no una operacion que el propio centro se autoaplique.

					Se valida todo antes de escribir nada. Una transicion rechazada no deja la \
					suscripcion movida, ni una fila de historico, ni un evento de auditoria que \
					sugiera que algo paso.

					Enviar expectedStatus hace que la operacion se rechace con 409 si el estado \
					real ya no es el que el actor creia, en vez de aplicarse sobre una premisa \
					falsa. El motivo es obligatorio al suspender y al cancelar; omitirlo ahi es \
					400.

					Un salto que la maquina de estados no admite —incluido repetir el estado \
					actual— es 409 invalid-subscription-transition.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Transicion aplicada y registrada en el historico",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = SubscriptionResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el estado destino, o falta el motivo donde es obligatorio",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "La operacion esta reservada a la administracion de plataforma",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La organizacion no existe, esta dada de baja, o es de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "invalid-subscription-transition si la maquina de estados no "
							+ "admite el salto; conflict si expectedStatus no coincide con el real",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SubscriptionResponse> transition(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Valid @RequestBody SubscriptionTransitionRequest request) {

		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());

		return ResponseEntity.ok(SubscriptionResponse.from(subscriptionService.transition(
				orgId,
				request.toStatus(),
				request.expectedStatus(),
				request.reason(),
				actor.accountId())));
	}

	@GetMapping("/transitions")
	@Operation(
			operationId = "listSubscriptionTransitions",
			summary = "Historico de la suscripcion",
			description = "Listado paginado de los cambios de estado y de plan, del hecho mas "
					+ "reciente al mas viejo. Es append-only: responde 'que le paso a este centro y "
					+ "cuando' meses despues, y por eso ninguna fila se edita ni se borra. La "
					+ "primera fila de toda suscripcion tiene fromStatus ausente y significa el "
					+ "alta. Requiere administrar esa organizacion. El tamano de pagina se acota a "
					+ ApiPaging.TAMANO_MAXIMO + ": pedir mas devuelve ese maximo, no un error.")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Pagina del historico, del hecho mas reciente al mas viejo",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(
									implementation = SubscriptionTransitionPageResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "El actor esta en el tenant correcto pero no lo administra",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La organizacion no existe, esta dada de baja, o es de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SubscriptionTransitionPageResponse> history(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Parameter(description = "Numero de pagina, base cero", example = "0")
			@RequestParam(defaultValue = ApiPaging.PAGINA_POR_DEFECTO) int page,

			@Parameter(description = "Elementos por pagina, acotado a 100", example = "20")
			@RequestParam(defaultValue = ApiPaging.TAMANO_POR_DEFECTO) int size) {

		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requireOrgAdmin(
				actor.accountId(), orgId, actor.contextOrganizationId(), actor.platformAdmin());

		return ResponseEntity.ok(SubscriptionTransitionPageResponse.from(
				subscriptionService.history(
						orgId, PageRequest.of(ApiPaging.pagina(page), ApiPaging.tamano(size)))));
	}

	@PostMapping(path = "/plan-changes", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "changeSubscriptionPlan",
			summary = "Cambiar el plan contratado",
			description = """
					Contrata otro plan y lo registra en el historico. Reservado a la administracion \
					de plataforma.

					Un downgrade se acepta aunque el uso actual ya supere los limites del plan \
					nuevo, y no toca un solo dato: no borra, no desactiva y no marca nada como \
					invalido. Lo que existe sigue operativo y consultable. Lo unico que cambia es \
					el futuro: la proxima alta que exceda el limite nuevo se rechaza. Los limites \
					ya excedidos vuelven en warnings para que el cliente pueda ordenarse antes de \
					chocarse, no como un rechazo.

					Reintentar un cambio ya aplicado responde applied=false y no genera una segunda \
					fila de historico ni un segundo evento: un reintento no puede producir un \
					segundo efecto.

					Requiere la suscripcion ACTIVA (409 subscription-suspended si no lo esta) y la \
					version vigente (409 conflict si quedo vieja).""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Plan cambiado, o ya vigente (applied=false). warnings trae los "
							+ "limites que el uso actual ya excede: son avisos, no rechazos",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlanChangeResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el codigo de plan o la version esperada",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "La operacion esta reservada a la administracion de plataforma",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La organizacion no es accesible, o el plan no es contratable",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "subscription-suspended si la suscripcion no esta ACTIVA; "
							+ "conflict si la version enviada quedo vieja",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PlanChangeResponse> changePlan(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Valid @RequestBody PlanChangeRequest request) {

		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());

		return ResponseEntity.ok(PlanChangeResponse.from(subscriptionService.changePlan(
				orgId, request.planCode(), request.expectedVersion(), actor.accountId())));
	}
}
