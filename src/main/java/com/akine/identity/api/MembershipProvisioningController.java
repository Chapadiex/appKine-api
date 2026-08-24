package com.akine.identity.api;

import com.akine.identity.api.dto.CreateMembershipRequest;
import com.akine.identity.api.dto.CreatedMembershipResponse;
import com.akine.identity.application.DirectMembershipService;
import com.akine.platform.spi.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Alta directa de un colaborador por email.
 *
 * <h2>Por que este endpoint vive en {@code identity} y no en {@code organization}</h2>
 *
 * <p>Lo exige el javadoc de {@code organization.spi.MembershipProvisioning}: el administrador
 * tipea un <b>email</b>, y {@code cuenta} es propiedad de {@code identity}.
 * {@code organization} no compila contra este modulo —ArchUnit rechaza la flecha—, asi que un
 * controller de aquel lado no podria resolver la direccion, y recibir el {@code accountId}
 * desde el cliente seria dejar que el cliente elija a quien vincular por id. El resto de la
 * gestion de colaboradores —listar, cambiar rol, suspender, revocar, permisos adicionales— si
 * vive en {@code organization.api}, bajo {@code /api/v1/organizations/&#123;orgId&#125;/memberships}.
 *
 * <h2>Por que la ruta no lleva el {@code orgId}</h2>
 *
 * <p>Porque el tenant no es del cliente. Sale del contexto que {@code TenantContextFilter}
 * revalido contra la base; ponerlo en la ruta sugeriria que se elige, y obligaria a rechazar
 * como cross-tenant justo el valor que el cliente acaba de escribir. Ademas el rate limit de
 * abajo necesita un prefijo exacto: {@code RateLimitFilter} compara cadenas y no patrones, a
 * proposito —un matcher de patrones en un filtro de seguridad es donde aparecen los bypass—.
 *
 * <h2>El 404 del email desconocido es una decision, no un descuido</h2>
 *
 * <p><b>Decision del usuario, 24/08/2026.</b> Este endpoint recibe un email y responde distinto
 * segun exista la cuenta: eso lo convierte en un <b>oraculo de enumeracion autenticado</b>. Un
 * {@code ORG_ADMIN} comprometido puede enumerar la base de usuarios del SaaS entero probando
 * direcciones. Es exactamente el riesgo por el que {@code AccountAdminController} documenta que
 * no existe ninguna busqueda por email.
 *
 * <p>Se acepta igual, porque la alternativa es peor: una respuesta uniforme dejaria al
 * administrador sin saber si el alta ocurrio, sobre la <b>unica</b> via que esta etapa tiene
 * para crear colaboradores. Un 201 mentiroso sobre un email mal tipeado termina en una persona
 * que nunca aparece en la lista y en un administrador que no entiende por que.
 *
 * <p>La contrapartida se paga con dos mitigaciones que <b>no son opcionales</b>:
 *
 * <ol>
 *   <li><b>Rate limit propio de la ruta</b>, en {@code RateLimitFilter}: diez intentos por
 *       minuto y por IP. No es el cupo del login (treinta) ni el del alta publica (cinco). No es
 *       un login fallido —no hay contrasena que tipear mal, ni Argon2id que agotar— ni un alta
 *       anonima —quien llega aca ya se autentico y ya tiene {@code colaborador:manage}—: es una
 *       accion administrativa deliberada, que una persona hace de a una y leyendo lo que
 *       escribe. Diez por minuto le sobran hasta para incorporar un equipo entero de una
 *       sentada, y le ponen techo a la automatizacion: 14.400 direcciones por dia y por IP,
 *       cada una con su fila de auditoria. Sin limite, la misma lista se barre en segundos.</li>
 *   <li><b>Auditoria de cada intento, incluidos los fallidos.</b> El intento sobre un email sin
 *       cuenta escribe {@code MEMBERSHIP_ALTA_RECHAZADA} en la auditoria DEL TENANT, con el
 *       actor y la direccion tipeada, y se confirma <b>antes</b> de responder el 404 —en su
 *       propia transaccion, porque el rollback del error se llevaria puesto el rastro—. Un
 *       barrido queda como una racha del mismo actor, visible desde la consulta por actor
 *       (RF-M24-003) sin que nadie tenga que sospechar antes. Esa auditoria no construye ningun
 *       padron: por construccion solo registra direcciones que <b>no</b> tienen cuenta.</li>
 * </ol>
 *
 * <p>Quien lea esto en seis meses: el 404 es negociable, las dos mitigaciones no. Si alguna vez
 * se retira una, hay que volver a discutir el codigo de respuesta.
 */
@RestController
@RequestMapping(path = "/api/v1/memberships", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Colaboradores",
		description = "Vinculos de las cuentas con la organizacion, sus roles y sus permisos")
public class MembershipProvisioningController {

	private final DirectMembershipService directMembershipService;
	private final TenantContextHolder tenantContextHolder;

	public MembershipProvisioningController(
			DirectMembershipService directMembershipService,
			TenantContextHolder tenantContextHolder) {
		this.directMembershipService = directMembershipService;
		this.tenantContextHolder = tenantContextHolder;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createDirectMembership",
			summary = "Vincular una cuenta existente con la organizacion",
			description = """
					Requiere colaborador:manage sobre el alcance pedido. La organizacion sale del \
					contexto de trabajo activo y NO se manda en el cuerpo.

					No crea cuentas y no manda correos: vincula a alguien que ya se registro. Es \
					una desviacion declarada de RF-M05-001/002 —el flujo de invitacion con \
					aceptacion queda para una etapa posterior—, no el flujo definitivo.

					Si el email no tiene cuenta la respuesta es 404. Esa respuesta distingue \
					emails registrados de los que no, asi que la ruta tiene rate limit propio —10 \
					por minuto y por IP— y CADA intento fallido queda auditado en el tenant como \
					MEMBERSHIP_ALTA_RECHAZADA, con el actor y la direccion. Un barrido de la base \
					no pasa desapercibido.

					El motivo es obligatorio: es lo que responde, seis meses despues, por que esa \
					persona tiene acceso a los datos del centro.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Vinculo creado. Location apunta al recurso en el modulo de "
							+ "organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CreatedMembershipResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el email, el rol o el motivo, o el rol no es un valor "
							+ "legal",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada, no hay contexto de trabajo activo "
							+ "(missing-tenant-context), o falta colaborador:manage sobre el "
							+ "alcance pedido. Nunca 401",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "Ninguna cuenta tiene ese email, o la sede pedida no es de la "
							+ "organizacion del contexto. Los dos casos responden igual",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Esa cuenta ya tiene un vinculo con ese alcance, vigente o "
							+ "historico",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "429",
					description = "Se alcanzo el limite de intentos de la ruta. Retry-After dice "
							+ "en cuantos segundos reintentar",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CreatedMembershipResponse> create(
			@Valid @RequestBody CreateMembershipRequest request) {

		IdentityApiActor actor = IdentityApiActor.current(tenantContextHolder);

		long membershipId = directMembershipService.vincular(
				new DirectMembershipService.Actor(
						actor.accountId(), actor.contextOrganizationId(), actor.platformAdmin()),
				request.email(),
				request.consultorioId(),
				request.roleCode(),
				request.reason());

		// Location apunta al modulo que es dueño del recurso. Que el alta entre por aca es una
		// consecuencia de quien posee el email, no de donde vive la membership.
		URI ubicacion = URI.create("/api/v1/organizations/" + actor.contextOrganizationId()
				+ "/memberships/" + membershipId);

		return ResponseEntity.created(ubicacion)
				.body(new CreatedMembershipResponse(membershipId));
	}
}
