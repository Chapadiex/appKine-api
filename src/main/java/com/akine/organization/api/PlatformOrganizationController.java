package com.akine.organization.api;

import com.akine.organization.api.dto.PlatformOrganizationPageResponse;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.OrganizationService;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.listado.OrdenDeListado;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El listado de centros de la administracion de plataforma (RF-M01-003, RNF-M01-004,
 * RNF-M01-005).
 *
 * <p>La consola de plataforma opera la suscripcion de un centro —leer el contrato, cambiar el
 * plan, suspender— y hasta esta ruta no tenia de donde elegirlo: obligaba a tipear el id, que es
 * justo el identificador tecnico que RNF-M01-005 pide no usar como dato principal.
 *
 * <p><b>Mismo guard que {@code getPlatformSubscription}</b> y por el mismo motivo: lo que viaja
 * es lo que la plataforma misma escribe —nombre y slug del alta, estado y plan de la
 * suscripcion— y nada que sea dato del centro. Por eso no exige acceso de soporte, y por eso
 * tampoco audita la lectura: la lectura del contrato de un centro no la audita, y este listado
 * expone menos que ella.
 *
 * <p>Como el resto de {@code /platform/**}, responde 403 a quien no es de plataforma.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/organizations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Plataforma",
		description = "Administracion de la plataforma: acceso de soporte y roles globales")
public class PlatformOrganizationController {

	private final OrganizationService organizationService;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public PlatformOrganizationController(
			OrganizationService organizationService,
			AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.organizationService = organizationService;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping
	@Operation(
			operationId = "listPlatformOrganizations",
			summary = "Listado de centros, para la administracion de plataforma",
			description = "Listado paginado de las organizaciones vigentes con el estado y el plan "
					+ "de su suscripcion, ordenado por nombre. Es el insumo para elegir el centro "
					+ "cuya suscripcion se opera. Las dadas de baja no aparecen. No trae miembros, "
					+ "sedes, consumo de limites ni datos de pacientes. Reservado a la "
					+ "administracion de plataforma, igual que la lectura y el cambio del plan. El "
					+ "tamano de pagina se acota a " + ApiPaging.TAMANO_MAXIMO + ": pedir mas "
					+ "devuelve ese maximo, no un error. DP-45: sort ordena por nombre, slug o "
					+ "creadaEn; sin el, por nombre.")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Pagina de centros, ordenada por nombre",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlatformOrganizationPageResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "El estado pedido no es un estado de suscripcion, o sort fuera del "
							+ "enum",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "La operacion esta reservada a la administracion de plataforma",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PlatformOrganizationPageResponse> list(
			@Parameter(description = "Texto a buscar en el nombre o el slug del centro",
					example = "kinesio")
			@RequestParam(required = false) String q,

			@Parameter(description = "Estado de la suscripcion. Sin el, todos los estados",
					example = "ACTIVA")
			@RequestParam(required = false) SubscriptionStatus status,

			@Parameter(description = "Numero de pagina, base cero", example = "0")
			@RequestParam(defaultValue = ApiPaging.PAGINA_POR_DEFECTO) int page,

			@Parameter(description = "Elementos por pagina, acotado a 100", example = "20")
			@RequestParam(defaultValue = ApiPaging.TAMANO_POR_DEFECTO) int size,

			@Parameter(description = OrdenDeListado.DESCRIPCION + "nombre ascendente.",
					schema = @Schema(allowableValues = {"nombre,asc", "nombre,desc", "slug,asc",
							"slug,desc", "creadaEn,asc", "creadaEn,desc"}))
			@RequestParam(name = OrdenDeListado.PARAMETRO, required = false) String sort) {

		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());

		// DP-45: el orden viaja en el Pageable. Sin sort, el de siempre: nombre y id.
		Sort orden = OrdenDeListado.leer(sort, ORDEN_DE_CENTROS.keySet())
				.map(pedido -> pedido.aSort(ORDEN_DE_CENTROS.get(pedido.campo()), "id"))
				.orElse(Sort.by(Sort.Direction.ASC, "name").and(Sort.by(Sort.Direction.ASC, "id")));
		return ResponseEntity.ok(PlatformOrganizationPageResponse.from(
				organizationService.listForPlatform(q, status,
						PageRequest.of(ApiPaging.pagina(page), ApiPaging.tamano(size), orden))));
	}

	/** DP-45: campo del contrato → propiedad de la organizacion. */
	private static final java.util.Map<String, String> ORDEN_DE_CENTROS = java.util.Map.of(
			"nombre", "name",
			"slug", "slug",
			"creadaEn", "createdAt");
}
