package com.akine.organization.api;

import com.akine.organization.api.dto.PlatformSubscriptionResponse;
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
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La suscripcion de un centro leida por la administracion de plataforma.
 *
 * <h2>Por que una ruta aparte y no el GET de la suscripcion</h2>
 *
 * <p>{@code GET /organizations/{orgId}/subscription} va detras de {@code tenant:read}, que para el
 * {@code PLATFORM_ADMIN} es de alcance soporte: es la lectura de los datos del centro y tiene que
 * dejar {@code SUPPORT_ACCESS_USED}. El cambio de plan y las transiciones, en cambio, son
 * {@code tenant:manage} global, y el cambio de plan exige {@code expectedVersion}. Sin esta ruta la
 * plataforma podia cambiar el plan pero no tenia de donde sacar la version sin abrir un acceso de
 * soporte (pre-QA del 14/10, PLA-06).
 *
 * <p>Abrir aquel GET sin soporte habria deshecho la correccion que lo puso detras del evaluador.
 * Esta ruta devuelve solo el contrato —lo que la plataforma misma escribe— y deja afuera el
 * consumo de limites, que es dato del centro. Exige el mismo rol que el cambio de plan y nada mas.
 *
 * <p>Como el resto de {@code /platform/**}, responde 403 y no 404 a quien no es de plataforma: la
 * ruta es publica en el contrato y el 403 no depende de la organizacion pedida.
 */
@RestController
@RequestMapping(
		path = "/api/v1/platform/organizations/{orgId}/subscription",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Plataforma",
		description = "Administracion de la plataforma: acceso de soporte y roles globales")
public class PlatformSubscriptionController {

	private final SubscriptionService subscriptionService;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public PlatformSubscriptionController(
			SubscriptionService subscriptionService,
			AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.subscriptionService = subscriptionService;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping
	@Operation(
			operationId = "getPlatformSubscription",
			summary = "Suscripcion de un centro, para la administracion de plataforma",
			description = """
					Devuelve el plan contratado, el estado, la version y las transiciones \
					posibles de la suscripcion del centro. La version es la expectedVersion que \
					pide el cambio de plan.

					No trae el consumo de los limites: eso es dato del centro y sigue en \
					GET /organizations/{orgId}/subscription, que para la plataforma exige un \
					acceso de soporte vigente.

					Reservado a la administracion de plataforma, igual que el cambio de plan y \
					las transiciones. Una organizacion inexistente o dada de baja es 404.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Plan, estado, version y transiciones posibles",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlatformSubscriptionResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "La operacion esta reservada a la administracion de plataforma",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La organizacion no existe o esta dada de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PlatformSubscriptionResponse> find(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId) {

		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());

		return ResponseEntity.ok(
				PlatformSubscriptionResponse.from(subscriptionService.findContract(orgId)));
	}
}
