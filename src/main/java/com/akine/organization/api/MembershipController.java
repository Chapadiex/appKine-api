package com.akine.organization.api;

import com.akine.organization.api.dto.AssignGrantRequest;
import com.akine.organization.api.dto.ChangeMembershipRequest;
import com.akine.organization.api.dto.MembershipGrantResponse;
import com.akine.organization.api.dto.MembershipPageResponse;
import com.akine.organization.api.dto.MembershipReasonRequest;
import com.akine.organization.api.dto.MembershipResponse;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.MembershipService;
import com.akine.organization.application.OperatingActor;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Colaboradores de la organizacion: lectura, cambio de rol, estado y permisos adicionales.
 *
 * <h2>Quien decide</h2>
 * {@link MembershipService}, siempre. Este controller no evalua ni una regla: traduce el request
 * al {@link OperatingActor} que el servicio espera y devuelve DTO. Los tres datos del actor
 * salen de donde tienen que salir —cuenta y rol de plataforma del principal ya revalidado, sede
 * del contexto que {@code TenantContextFilter} verifico contra la base— y <b>ninguno de un
 * parametro del cliente</b>: un parametro dice que contexto se pide, no cual es legitimo
 * (RN-M01-003).
 *
 * <h2>Codigos, y por que no son intercambiables</h2>
 * <ul>
 *   <li><b>404</b> para todo lo que es de otro tenant o no existe. Un 403 confirmaria que ese
 *       id existe, y alcanzaria recorrer numeros para enumerar a los colaboradores de otros
 *       centros.</li>
 *   <li><b>403</b> cuando el actor esta dentro de su alcance y le falta el permiso: sobre un
 *       recurso que ya sabe que existe, el 404 no protege nada y ademas le miente.</li>
 *   <li><b>409</b> para los invariantes de estado —ultimo administrador, self-revoke, vinculo
 *       ya revocado, permiso adicional duplicado, modificacion concurrente—. El actor tiene el
 *       permiso; lo que no admite la operacion es el estado en que dejaria al tenant.</li>
 * </ul>
 *
 * <p>El alta de un colaborador NO esta aca: se hace por email y {@code cuenta} es propiedad de
 * {@code identity}, asi que su endpoint vive en la capa {@code api} de ese modulo y entra por
 * {@code organization.spi.MembershipProvisioning}. La flecha {@code organization -> identity}
 * esta prohibida sin excepciones.
 */
@RestController
@RequestMapping(
		path = "/api/v1/organizations/{orgId}/memberships",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Colaboradores",
		description = "Vinculos de las cuentas con la organizacion, sus roles y sus permisos")
public class MembershipController {

	private final MembershipService membershipService;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public MembershipController(
			MembershipService membershipService,
			AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.membershipService = membershipService;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	@GetMapping
	@Operation(
			operationId = "listMemberships",
			summary = "Colaboradores de la organizacion",
			description = """
					Requiere colaborador:read. Devuelve TAMBIEN los vinculos suspendidos y \
					revocados: quien administra tiene que poder ver quien estuvo, quien lo \
					desvinculo y por que. Para eso existe la baja logica. El cliente distingue por \
					el campo estado, no por ausencia.

					size se acota a 100 por request. Pedir 500 devuelve 100, no un error.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Pagina de colaboradores",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = MembershipPageResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada, no hay contexto de trabajo activo, "
							+ "o falta colaborador:read. Nunca 401",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La organizacion no existe o no es la del contexto del actor",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MembershipPageResponse> list(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,

			@Parameter(description = "Numero de pagina, base cero", example = "0")
			@RequestParam(defaultValue = ApiPaging.PAGINA_POR_DEFECTO) int page,

			@Parameter(description = "Elementos por pagina. Se acota a 100", example = "20")
			@RequestParam(defaultValue = ApiPaging.TAMANO_POR_DEFECTO) int size) {

		PageRequest pagina = PageRequest.of(
				ApiPaging.pagina(page), ApiPaging.tamano(size), Sort.by(Sort.Direction.ASC, "id"));

		return ResponseEntity.ok(MembershipPageResponse.from(
				membershipService.list(actor(), orgId, pagina)));
	}

	@GetMapping("/{membershipId}")
	@Operation(
			operationId = "getMembership",
			summary = "Un colaborador de la organizacion",
			description = """
					Requiere colaborador:read. El vinculo se busca por (id, organizacion) y nunca \
					por id pelado: uno de otro tenant responde 404 igual que uno inexistente.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "El vinculo pedido",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = MembershipResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, sin contexto activo, o sin colaborador:read",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El vinculo no existe o es de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MembershipResponse> find(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,

			@Parameter(description = "Identificador del vinculo", example = "42")
			@PathVariable long membershipId) {

		return ResponseEntity.ok(MembershipResponse.from(
				membershipService.find(actor(), orgId, membershipId)));
	}

	@GetMapping("/{membershipId}/grants")
	@Operation(
			operationId = "listMembershipGrants",
			summary = "Permisos adicionales vigentes de un colaborador",
			description = """
					Requiere colaborador:read. Devuelve solo los vigentes: los dados de baja \
					quedan en la fila para la auditoria, no para esta pantalla.

					Es lo que alimenta la lista desde la que se revoca un permiso adicional. Sin \
					este endpoint el cliente no tendria de donde sacar los codigos a dar de baja.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Permisos adicionales vigentes. Lista vacia si no tiene ninguno",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = MembershipGrantResponse.class)))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, sin contexto activo, o sin colaborador:read",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El vinculo no existe o es de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<MembershipGrantResponse>> grants(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,

			@Parameter(description = "Identificador del vinculo", example = "42")
			@PathVariable long membershipId) {

		List<MembershipGrantResponse> grants =
				membershipService.grants(actor(), orgId, membershipId).stream()
						.map(MembershipGrantResponse::from)
						.toList();

		return ResponseEntity.ok(grants);
	}

	// =================================================================================
	// Mutaciones de rol y de estado
	// =================================================================================

	@PatchMapping(path = "/{membershipId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "changeMembership",
			summary = "Cambiar el rol y/o el alcance de un colaborador",
			description = """
					Requiere colaborador:manage y motivo obligatorio. Los dos cambios se auditan \
					por separado: mover a alguien de sede y cambiar lo que puede hacer son dos \
					decisiones distintas.

					changeScope existe porque null es un valor legitimo de consultorioId \
					(significa alcance de toda la organizacion): sin la bandera, 'no tocar la \
					sede' y 'ampliarla a la organizacion' serian el mismo request.

					Solo opera sobre vinculos ACTIVA. Sobre uno suspendido o revocado responde \
					409 membership-not-active, no 404: el administrador ve esa fila en su propio \
					listado y decirle 'no existe' no le explicaria nada.

					Bajar de rol al ultimo administrador se rechaza con 409 igual que revocarlo: \
					el tenant quedaria sin nadie que pueda repararlo.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Vinculo actualizado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = MembershipResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo, o el rol no existe en el catalogo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, sin contexto activo, sin colaborador:manage, o el "
							+ "vinculo es el del fundador y el actor no es el fundador",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El vinculo o la sede destino no existen, o son de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El vinculo no esta ACTIVA (membership-not-active), dejaria al "
							+ "tenant sin administradores (last-admin-required), el actor se "
							+ "quitaria su ultimo rol administrativo (self-revoke-not-allowed), o "
							+ "otro usuario modifico la fila (concurrent-modification)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MembershipResponse> change(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,

			@Parameter(description = "Identificador del vinculo", example = "42")
			@PathVariable long membershipId,

			@Valid @RequestBody ChangeMembershipRequest request) {

		return ResponseEntity.ok(MembershipResponse.from(membershipService.changeRole(
				actor(),
				orgId,
				membershipId,
				request.roleCode(),
				request.changeScope(),
				request.consultorioId(),
				request.reason())));
	}

	@PostMapping(path = "/{membershipId}/suspend", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "suspendMembership",
			summary = "Suspender temporalmente un vinculo",
			description = """
					Requiere colaborador:manage y motivo obligatorio. Suspender saca a la persona \
					igual que revocar mientras dura, asi que pasa por los mismos invariantes: no \
					puede dejar al tenant sin administradores ni ser el ultimo rol administrativo \
					del propio actor. Tratarlo como mas suave seria dejar la misma puerta abierta \
					con otro nombre.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Vinculo suspendido",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = MembershipResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, sin contexto activo, sin colaborador:manage, o es "
							+ "el vinculo del fundador",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El vinculo no existe o es de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El vinculo no esta ACTIVA, o la suspension dejaria al tenant "
							+ "sin administradores",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MembershipResponse> suspend(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,

			@Parameter(description = "Identificador del vinculo", example = "42")
			@PathVariable long membershipId,

			@Valid @RequestBody MembershipReasonRequest request) {

		return ResponseEntity.ok(MembershipResponse.from(
				membershipService.suspend(actor(), orgId, membershipId, request.reason())));
	}

	@PostMapping(path = "/{membershipId}/reactivate", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "reactivateMembership",
			summary = "Devolver a ACTIVA un vinculo suspendido",
			description = """
					Requiere colaborador:manage. Solo aplica sobre vinculos SUSPENDIDA: uno \
					revocado es terminal y responde 409. Devolverle el acceso a alguien es tan \
					auditable como quitarselo, asi que el motivo se exige igual.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Vinculo reactivado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = MembershipResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo",
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
					description = "El vinculo no existe o es de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El vinculo no estaba SUSPENDIDA",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MembershipResponse> reactivate(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,

			@Parameter(description = "Identificador del vinculo", example = "42")
			@PathVariable long membershipId,

			@Valid @RequestBody MembershipReasonRequest request) {

		return ResponseEntity.ok(MembershipResponse.from(
				membershipService.reactivate(actor(), orgId, membershipId, request.reason())));
	}

	@PostMapping(path = "/{membershipId}/revoke", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "revokeMembership",
			summary = "Revocar el vinculo de un colaborador",
			description = """
					Requiere colaborador:manage y motivo obligatorio. Estado TERMINAL y sin \
					borrado (RN-M05-003): la fila queda con quien revoco y por que, porque los \
					historicos que la referencian tienen que seguir siendo legibles.

					Es la operacion con los tres invariantes a la vez: ultimo administrador, \
					self-revoke y proteccion del fundador. Los dos primeros responden 409; el \
					tercero 403, porque es una restriccion sobre QUIEN puede hacerlo y no sobre \
					el estado en que quedaria el tenant.

					No es un DELETE porque no borra nada, y exige cuerpo porque el motivo es \
					obligatorio.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Vinculo revocado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = MembershipResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, sin contexto activo, sin colaborador:manage, o es "
							+ "el vinculo del fundador y el actor no es el fundador",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El vinculo no existe o es de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El vinculo ya estaba revocado, dejaria al tenant sin "
							+ "administradores, o el actor se quitaria su ultimo rol administrativo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MembershipResponse> revoke(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,

			@Parameter(description = "Identificador del vinculo", example = "42")
			@PathVariable long membershipId,

			@Valid @RequestBody MembershipReasonRequest request) {

		return ResponseEntity.ok(MembershipResponse.from(
				membershipService.revoke(actor(), orgId, membershipId, request.reason())));
	}

	// =================================================================================
	// Permisos adicionales
	// =================================================================================

	@PostMapping(path = "/{membershipId}/grants", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "assignMembershipGrant",
			summary = "Otorgar un permiso adicional a un colaborador",
			description = """
					Requiere colaborador:manage y motivo obligatorio. El catalogo de codigos \
					otorgables es cerrado: un permiso que existe pero no es otorgable en esta fase \
					se rechaza con 400, no se escribe.

					Otorgar dos veces el mismo permiso responde 409 grant-already-active, y esa \
					decision la toma la clave unica de la base y no un chequeo previo: dos \
					administradores otorgando a la vez leerian los dos que no existe.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Permiso adicional otorgado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = MembershipGrantResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo, o el codigo no existe o no es otorgable",
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
					description = "El vinculo no existe o es de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ese permiso adicional ya esta vigente sobre el vinculo, o el "
							+ "vinculo no esta ACTIVA",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MembershipGrantResponse> assignGrant(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,

			@Parameter(description = "Identificador del vinculo", example = "42")
			@PathVariable long membershipId,

			@Valid @RequestBody AssignGrantRequest request) {

		MembershipGrantResponse otorgado = MembershipGrantResponse.from(
				membershipService.assignGrant(
						actor(),
						orgId,
						membershipId,
						request.permissionCode(),
						request.reason(),
						request.validUntil()));

		return ResponseEntity.status(201).body(otorgado);
	}

	@DeleteMapping("/{membershipId}/grants/{permissionCode}")
	@Operation(
			operationId = "revokeMembershipGrant",
			summary = "Dar de baja un permiso adicional",
			description = """
					Requiere colaborador:manage. Baja LOGICA: la fila queda con su autor y su \
					motivo.

					Es idempotente hacia el mismo resultado: si el permiso ya no esta vigente \
					responde 204 igual. Un 409 obligaria al cliente a distinguir dos situaciones \
					que para el usuario son la misma: no lo tenia, y se lo acaban de quitar.

					El motivo va como parametro y no en el cuerpo porque un DELETE con cuerpo no \
					esta garantizado de punta a punta por proxies y clientes HTTP.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Permiso adicional dado de baja, o "
					+ "ya no estaba vigente"),
			@ApiResponse(
					responseCode = "400",
					description = "El codigo de permiso no existe en el catalogo",
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
					description = "El vinculo no existe o es de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> revokeGrant(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,

			@Parameter(description = "Identificador del vinculo", example = "42")
			@PathVariable long membershipId,

			@Parameter(description = "Codigo del permiso a dar de baja",
					example = "auditoria:read-clinica")
			@PathVariable String permissionCode,

			@Parameter(description = "Motivo declarado de la baja. Queda en la auditoria")
			@RequestParam(required = false) String reason) {

		membershipService.revokeGrant(actor(), orgId, membershipId, permissionCode, reason);
		return ResponseEntity.noContent().build();
	}

	// =================================================================================
	// Actor
	// =================================================================================

	/**
	 * Traduce el request al actor que espera {@code application}.
	 *
	 * <p>La sede sale del contexto ya revalidado contra la base y jamas de un parametro: sin
	 * ella, el evaluador no puede decidir un permiso de alcance CONSULTORIO y un
	 * {@code CONSULTORIO_ADMIN} quedaria sin ninguno.
	 */
	private OperatingActor actor() {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		return new OperatingActor(
				actor.accountId(), actor.platformAdmin(), authorizationGuard.consultorioDelContexto());
	}
}
