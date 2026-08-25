package com.akine.identity.api;

import com.akine.identity.api.dto.CancelInvitacionRequest;
import com.akine.identity.api.dto.CreateInvitacionRequest;
import com.akine.identity.api.dto.InvitacionResponse;
import com.akine.identity.application.ColaboradorInvitacionService;
import com.akine.identity.application.DirectMembershipService;
import com.akine.identity.application.InvitacionAltaCommand;
import com.akine.identity.domain.EstadoInvitacion;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Lado del administrador de la invitacion a colaborar (M05, AKINE-02.03).
 *
 * <h2>Por que este controller vive en {@code identity} y no en {@code organization}</h2>
 *
 * <p>Por lo mismo que {@link MembershipProvisioningController}: la invitacion arranca con un
 * <b>email</b>, que es dato de {@code identity}, y en el caso mas comun termina creando una
 * cuenta. {@code organization} no puede compilar contra {@code identity} —ArchUnit rechaza la
 * flecha— asi que el flujo tiene que orquestarse de este lado. La membership la crea
 * {@code organization} por su SPI.
 *
 * <p>La ruta, en cambio, si empieza por {@code /organizations/{orgId}}, porque el recurso es del
 * tenant. El {@code orgId} de la URL es <b>decorativo</b>: el que manda es el del contexto
 * revalidado, igual que en el resto de la API. Se pide igual para que la URL sea legible y
 * cacheable por tenant.
 *
 * <p>El lado del invitado —consultar, aceptar, rechazar— vive en
 * {@link InvitacionPublicaController} y es publico: quien acepta no tiene sesion, y muchas veces
 * ni cuenta.
 */
@Tag(
		name = "Invitaciones",
		description = "Emision, listado, reenvio y cancelacion de invitaciones a colaborar (M05)")
@RestController
@RequestMapping(
		path = "/api/v1/organizations/{orgId}/colaborador-invitaciones",
		produces = MediaType.APPLICATION_JSON_VALUE)
public class ColaboradorInvitacionController {

	private final ColaboradorInvitacionService invitacionService;
	private final TenantContextHolder tenantContextHolder;

	public ColaboradorInvitacionController(
			ColaboradorInvitacionService invitacionService,
			TenantContextHolder tenantContextHolder) {
		this.invitacionService = invitacionService;
		this.tenantContextHolder = tenantContextHolder;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createColaboradorInvitacion",
			summary = "Invitar a alguien a colaborar",
			description = """
					Requiere colaborador:manage sobre el alcance pedido. La organizacion sale del \
					contexto de trabajo activo.

					A diferencia del alta directa, la persona invitada PUEDE NO TENER CUENTA: la \
					crea al aceptar, en el mismo acto, sin un segundo correo de activacion. El \
					vinculo nace cuando acepta, no ahora.

					Emitir no consume cupo del plan; aceptar si. La contracara declarada es que \
					cinco invitaciones pendientes con un solo lugar libre significan que cuatro \
					reciben 409 al aceptar: entra el que llega primero.

					Solo puede haber UNA invitacion pendiente por persona y alcance. Si el enlace \
					vencio o no llego, el camino es reenviar, no emitir otra.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Invitacion emitida y correo encolado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = InvitacionResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el email o el rol, o el rol no es un valor legal",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, sin contexto de trabajo activo "
							+ "(missing-tenant-context), o sin colaborador:manage sobre el "
							+ "alcance pedido. Nunca 401",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede pedida no es de la organizacion del contexto",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ya hay una invitacion pendiente igual "
							+ "(invitacion-pendiente-duplicada), o la persona ya trabaja en la "
							+ "organizacion (colaborador-ya-vinculado)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<InvitacionResponse> create(
			@PathVariable long orgId,
			@Valid @RequestBody CreateInvitacionRequest request) {

		InvitacionResponse respuesta = InvitacionResponse.de(invitacionService.invitar(
				actor(),
				new InvitacionAltaCommand(
						request.email(), request.roleCode(), request.consultorioId())));

		URI ubicacion = URI.create("/api/v1/organizations/" + orgId
				+ "/colaborador-invitaciones/" + respuesta.id());

		return ResponseEntity.created(ubicacion).body(respuesta);
	}

	@GetMapping
	@Operation(
			operationId = "listColaboradorInvitaciones",
			summary = "Listar las invitaciones de la organizacion",
			description = """
					Requiere colaborador:manage, y no colaborador:read: lo que se ve aca son \
					direcciones de correo de personas que todavia no aceptaron nada. No es la \
					lista de colaboradores, es la lista de a quien se le escribio.

					Sin paginar a proposito: una organizacion tiene decenas de invitaciones en \
					toda su vida, no miles, y el filtro por estado es lo que hace util al listado.

					Una invitacion vencida figura PENDIENTE con vencida=true. Expirar no es una \
					decision de nadie y por eso no se materializa como estado: se deriva del \
					reloj en cada lectura.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Invitaciones de la organizacion, de la mas nueva a la mas vieja",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @io.swagger.v3.oas.annotations.media.ArraySchema(
									schema = @Schema(implementation = InvitacionResponse.class)))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, sin contexto activo o sin colaborador:manage",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<InvitacionResponse>> list(
			@PathVariable long orgId,
			@Parameter(description = "Filtra por estado. Ausente devuelve todas")
			@RequestParam(required = false) EstadoInvitacion estado) {

		return ResponseEntity.ok(invitacionService.listar(actor(), estado).stream()
				.map(InvitacionResponse::de)
				.toList());
	}

	@PostMapping(path = "/{invitacionId}/resend")
	@Operation(
			operationId = "resendColaboradorInvitacion",
			summary = "Reenviar una invitacion pendiente",
			description = """
					Emite un token nuevo y vuelve a mandar el correo. EL TOKEN ANTERIOR DEJA DE \
					SERVIR en el mismo acto: dos enlaces validos para la misma invitacion dejan \
					al invitado eligiendo cual usar.

					No crea una invitacion nueva. Conserva el id, la autoria y la fecha original, \
					que es lo que hace que el listado siga diciendo desde cuando se espera \
					respuesta.

					La respuesta NO trae el token: va al correo y a ningun lado mas.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Token rotado y correo encolado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = InvitacionResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, sin contexto activo o sin colaborador:manage",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La invitacion no existe o es de otro tenant. Los dos responden "
							+ "igual",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La invitacion ya fue aceptada, rechazada o cancelada "
							+ "(invitacion-ya-resuelta)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<InvitacionResponse> resend(
			@PathVariable long orgId, @PathVariable long invitacionId) {

		return ResponseEntity.ok(
				InvitacionResponse.de(invitacionService.reenviar(actor(), invitacionId)));
	}

	@PostMapping(path = "/{invitacionId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "cancelColaboradorInvitacion",
			summary = "Retirar una invitacion pendiente",
			description = """
					El motivo es obligatorio: cancelar es una decision del administrador sobre \
					alguien a quien ya le escribio, y tiene que responder por que seis meses \
					despues. El rechazo del invitado, en cambio, no exige motivo.

					La cancelacion es terminal y libera el email: se puede volver a invitar a esa \
					persona mas adelante.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Invitacion cancelada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = InvitacionResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, sin contexto activo o sin colaborador:manage",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La invitacion no existe o es de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La invitacion ya fue resuelta (invitacion-ya-resuelta)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<InvitacionResponse> cancel(
			@PathVariable long orgId,
			@PathVariable long invitacionId,
			@Valid @RequestBody CancelInvitacionRequest request) {

		return ResponseEntity.ok(InvitacionResponse.de(
				invitacionService.cancelar(actor(), invitacionId, request.reason())));
	}

	/**
	 * El actor del request.
	 *
	 * <p>La organizacion sale del contexto revalidado y <b>no</b> del {@code orgId} de la URL.
	 * Ese path variable existe para que la ruta sea legible; usarlo para decidir el tenant seria
	 * dejar que el cliente elija sobre que organizacion opera.
	 */
	private DirectMembershipService.Actor actor() {
		IdentityApiActor actor = IdentityApiActor.current(tenantContextHolder);
		return new DirectMembershipService.Actor(
				actor.accountId(), actor.contextOrganizationId(), actor.platformAdmin());
	}
}
