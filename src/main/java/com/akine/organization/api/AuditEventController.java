package com.akine.organization.api;

import com.akine.organization.api.dto.AuditEventPageResponse;
import com.akine.organization.application.AuditQueryService;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.OperatingActor;
import com.akine.platform.spi.audit.AuditEventSummary;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.listado.OrdenDeListado;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Consulta del registro de auditoria del tenant (RF-M24-002/003/004).
 *
 * <h2>Un endpoint con tres consultas, no tres endpoints</h2>
 * Los tres requerimientos —historial de una entidad, actividad de un actor y ventana temporal—
 * son la misma pantalla con un filtro distinto. Publicar tres rutas obligaria al cliente a
 * elegir la ruta antes de saber que filtro va a usar el usuario, y a rearmar la URL cada vez que
 * la cambie. El filtro se elige por los parametros presentes y el servidor despacha.
 *
 * <p><b>Los filtros son excluyentes y eso se valida aca.</b> Combinarlos daria una consulta que
 * el servicio no sabe responder, y adivinar cual gana produciria resultados que el usuario no
 * pidio: es un 400 explicito.
 *
 * <h2>Alcance</h2>
 * Requiere {@code auditoria:read}. Quien lo tiene con alcance de sede lee su sede y solo su
 * sede; el recorte lo decide el evaluador, no este controller: el alcance del permiso y el
 * filtro de la consulta son el mismo dato, y recalcularlo aca abriria la puerta a que las dos
 * versiones divergieran.
 *
 * <p>Los limites de la consulta —90 dias de ventana y 100 filas por pagina— los fija
 * {@link AuditQueryService} y estan declarados en la documentacion de cada parametro: sin tope,
 * un solo request puede pedir el historico entero de un tenant y llevarse la memoria del proceso.
 */
@RestController
@RequestMapping(
		path = "/api/v1/organizations/{orgId}/audit-events",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Auditoria", description = "Consulta del registro de hechos de la organizacion")
public class AuditEventController {

	/** DP-45: campo del contrato → propiedad de la entidad de auditoria. */
	private static final Map<String, String> ORDEN_DE_HECHOS = Map.of(
			"ocurridoEn", "occurredAt",
			"tipoEvento", "eventType",
			"tipoEntidad", "entityType");

	private final AuditQueryService auditQueryService;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public AuditEventController(
			AuditQueryService auditQueryService,
			AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.auditQueryService = auditQueryService;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping
	@Operation(
			operationId = "listAuditEvents",
			summary = "Hechos registrados en la auditoria de la organizacion",
			description = """
					Requiere auditoria:read. Hay que elegir EXACTAMENTE UNO de los tres filtros; \
					combinarlos o no mandar ninguno responde 400.

					1. entityType + entityId: historial de una entidad (RF-M24-002).
					2. actorAccountId: actividad de una persona dentro del tenant (RF-M24-003).
					3. from + to: ventana temporal (RF-M24-004). El rango no puede exceder los 90 \
					dias, y to tiene que ser posterior a from.

					size se recorta a 100 por request: un cliente que pide diez mil filas esta mal \
					configurado, no atacando, y devolverle cien le sirve mas que un error.

					Quien tiene el permiso con alcance de sede ve solo su sede. Ese recorte lo \
					decide el evaluador de permisos, no un parametro.

					DP-45: q refina cualquiera de los tres filtros. Busca "contiene" en el tipo \
					de evento y en el tipo de entidad, sin distinguir mayusculas ni acentos, y \
					si es un numero tambien coincide con el id de la entidad o con el de la \
					cuenta actora. No busca en el nombre del actor (vive en la identidad, no en \
					la auditoria) ni en el motivo o los detalles: sin auditoria:read-clinica \
					esos campos se redactan, y buscar en ellos dejaria adivinar lo redactado. \
					sort ordena por ocurridoEn, tipoEvento o tipoEntidad; sin el, del hecho mas \
					nuevo al mas viejo.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Pagina de hechos que cumplen el filtro",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AuditEventPageResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "No se eligio exactamente un filtro, el rango esta invertido, "
							+ "excede los 90 dias, o sort no es uno de los valores del enum",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, sin contexto de trabajo activo, o sin auditoria:read",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La organizacion no existe o no es la del contexto del actor",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AuditEventPageResponse> list(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,

			@Parameter(description = "Tipo de entidad. Va junto con entityId",
					example = "MEMBERSHIP")
			@RequestParam(required = false) String entityType,

			@Parameter(description = "Identificador de la entidad. Va junto con entityType",
					example = "42")
			@RequestParam(required = false) Long entityId,

			@Parameter(description = "Cuenta cuya actividad se consulta", example = "18")
			@RequestParam(required = false) Long actorAccountId,

			@Parameter(description = "Inicio de la ventana. Va junto con to",
					example = "2026-08-01T00:00:00Z")
			@RequestParam(required = false) Instant from,

			@Parameter(description = "Fin de la ventana. Va junto con from. Como maximo 90 dias "
					+ "despues de from", example = "2026-08-23T00:00:00Z")
			@RequestParam(required = false) Instant to,

			@Parameter(description = "Numero de pagina, base cero", example = "0")
			@RequestParam(defaultValue = ApiPaging.PAGINA_POR_DEFECTO) int page,

			@Parameter(description = "Elementos por pagina. Se recorta a 100", example = "20")
			@RequestParam(defaultValue = ApiPaging.TAMANO_POR_DEFECTO) int size,

			@Parameter(description = "Texto que refina el filtro elegido: tipo de evento, tipo "
					+ "de entidad, o un id", example = "MEMBERSHIP")
			@RequestParam(required = false) String q,

			@Parameter(description = OrdenDeListado.DESCRIPCION + "ocurridoEn descendente.",
					schema = @Schema(allowableValues = {"ocurridoEn,asc", "ocurridoEn,desc",
							"tipoEvento,asc", "tipoEvento,desc", "tipoEntidad,asc",
							"tipoEntidad,desc"}))
			@RequestParam(name = OrdenDeListado.PARAMETRO, required = false) String sort) {

		// Primero el aislamiento: una organizacion ajena es 404 aunque los filtros sean invalidos.
		OperatingActor actor = actor(orgId);

		boolean porEntidad = entityType != null && entityId != null;
		boolean porActor = actorAccountId != null;
		boolean porPeriodo = from != null && to != null;

		exigirUnSoloFiltro(porEntidad, porActor, porPeriodo);

		Sort orden = OrdenDeListado.leer(sort, ORDEN_DE_HECHOS.keySet())
				.map(pedido -> pedido.aSort(ORDEN_DE_HECHOS.get(pedido.campo()), "id"))
				.orElse(Sort.by(Sort.Direction.DESC, "occurredAt"));
		PageRequest pagina = PageRequest.of(ApiPaging.pagina(page), ApiPaging.tamano(size), orden);

		Page<AuditEventSummary> hechos;
		if (porEntidad) {
			hechos = auditQueryService.porEntidad(actor, orgId, entityType, entityId, q, pagina);
		} else if (porActor) {
			hechos = auditQueryService.porActor(actor, orgId, actorAccountId, q, pagina);
		} else {
			hechos = auditQueryService.porPeriodo(actor, orgId, from, to, q, pagina);
		}

		return ResponseEntity.ok(AuditEventPageResponse.from(hechos));
	}

	/**
	 * Exige exactamente un filtro.
	 *
	 * <p>Se lanza {@code IllegalArgumentException} y no un problema armado a mano porque el
	 * advice global ya la traduce a 400 {@code validation-error} con este mismo mensaje. Duplicar
	 * la traduccion aca dejaria dos textos que pueden divergir.
	 */
	private static void exigirUnSoloFiltro(
			boolean porEntidad, boolean porActor, boolean porPeriodo) {

		int elegidos = (porEntidad ? 1 : 0) + (porActor ? 1 : 0) + (porPeriodo ? 1 : 0);
		if (elegidos != 1) {
			throw new IllegalArgumentException(
					"La consulta de auditoria exige exactamente un filtro: entityType junto con "
							+ "entityId, o actorAccountId, o from junto con to");
		}
	}

	private OperatingActor actor(long orgId) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		return authorizationGuard.actorSobre(
				actor.accountId(), actor.platformAdmin(), actor.contextOrganizationId(), orgId);
	}
}
