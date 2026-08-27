package com.akine.organization.api;

import com.akine.organization.api.dto.GrantSupportAccessRequest;
import com.akine.organization.api.dto.SupportAccessResponse;
import com.akine.organization.application.SupportAccessService;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Acceso de soporte de la administracion de plataforma sobre un tenant.
 *
 * <h2>Por que estas rutas responden 403 y no 404</h2>
 * Al reves que casi todo el resto de la API. Las rutas {@code /platform/**} son publicas en el
 * contrato: su existencia no es secreta, y esconderlas de quien ya sabe que estan solo agregaria
 * confusion. Lo que si es secreto es el contenido, y eso lo protege el 403.
 *
 * <h2>Quien decide, y por que no es este controller</h2>
 * {@link SupportAccessService} revalida el rol de plataforma <b>contra la base</b> en cada
 * operacion, sin creerle al booleano que llega del principal: esta es la operacion que abre la
 * puerta a los datos de salud de un tenant, asi que la comprobacion tiene que ser de primera
 * mano.
 *
 * <p><b>El evento {@code SUPPORT_ACCESS_USED} no se escribe aca.</b> Lo registra el servicio que
 * ampara cada operacion concreta bajo el acceso, no el que lo concede. Duplicarlo desde este
 * controller llenaria la auditoria del cliente de eventos que no corresponden a ninguna lectura
 * real.
 */
@RestController
@RequestMapping(path = "/api/v1/platform", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Plataforma",
		description = "Administracion de la plataforma: acceso de soporte y roles globales")
public class PlatformSupportAccessController {

	private final SupportAccessService supportAccessService;
	private final TenantContextHolder tenantContextHolder;

	public PlatformSupportAccessController(
			SupportAccessService supportAccessService,
			TenantContextHolder tenantContextHolder) {
		this.supportAccessService = supportAccessService;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping("/organizations/{orgId}/support-access")
	@Operation(
			operationId = "listSupportAccess",
			summary = "Accesos de soporte vigentes sobre una organizacion",
			description = """
					Reservado a la administracion de plataforma. Devuelve los accesos vigentes; \
					los cerrados quedan en la fila para la auditoria.

					La misma informacion le llega al tenant por SU propia auditoria, que es donde \
					tiene sentido para el: un cliente tiene derecho a ver quien entro a sus datos \
					y por que, sin pedirselo a nadie.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Accesos de soporte vigentes. Lista vacia si no hay ninguno",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = SupportAccessResponse.class)))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada, o la cuenta no administra la "
							+ "plataforma. Nunca 401 desde un endpoint de negocio",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<SupportAccessResponse>> list(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId) {

		List<SupportAccessResponse> accesos =
				supportAccessService.list(actorAccountId(), orgId).stream()
						.map(SupportAccessResponse::from)
						.toList();

		return ResponseEntity.ok(accesos);
	}

	@PostMapping(
			path = "/organizations/{orgId}/support-access",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "grantSupportAccess",
			summary = "Conceder acceso de soporte sobre una organizacion",
			description = """
					Reservado a la administracion de plataforma. Motivo obligatorio: sin motivo no \
					hay soporte, hay acceso a datos de salud ajenos.

					La duracion se pide en minutos y no puede exceder los 240 (cuatro horas), que \
					es tambien el default. El tope no es un valor sugerido: dejar que quien pide \
					el acceso elija su propia duracion convertiria 'acotado en tiempo' en \
					'acotado si el que entra quiere'.

					El otorgamiento queda en la auditoria DEL TENANT, no solo en la de plataforma. \
					Cada operacion que despues se ampare en este acceso se audita por separado \
					como SUPPORT_ACCESS_USED.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Acceso de soporte concedido",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = SupportAccessResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo, o la duracion es invalida o excede el maximo",
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
					responseCode = "404",
					description = "La organizacion no existe o esta dada de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SupportAccessResponse> grant(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,

			@Valid @RequestBody GrantSupportAccessRequest request) {

		Duration duracion = request.durationMinutes() == null
				? null
				: Duration.ofMinutes(request.durationMinutes());

		SupportAccessResponse concedido = SupportAccessResponse.from(
				supportAccessService.grant(actorAccountId(), orgId, request.reason(), duracion));

		return ResponseEntity.status(201).body(concedido);
	}

	// Produces explicito por lo mismo que en AccountRegistrationController.activate: sin cuerpo
	// en el 204 el contrato solo declara problem+json, el cliente generado lo manda en Accept y
	// el produces de clase lo rechazaba con 406.
	@DeleteMapping(path = "/support-access/{supportAccessId}",
			produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE })
	@Operation(
			operationId = "revokeSupportAccess",
			summary = "Cerrar un acceso de soporte antes de su vencimiento",
			description = """
					Reservado a la administracion de plataforma. No borra: cierra. Que alguien \
					haya entrado a un tenant y por que tiene que seguir siendo legible despues.

					Un acceso inexistente o ya cerrado responde 404: para quien pregunta, los dos \
					casos son el mismo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Acceso de soporte cerrado"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, o la cuenta no administra la plataforma",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El acceso no existe o ya estaba cerrado",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> revoke(
			@Parameter(description = "Identificador del acceso de soporte", example = "5")
			@PathVariable long supportAccessId) {

		supportAccessService.revoke(actorAccountId(), supportAccessId);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Cuenta que opera.
	 *
	 * <p>Solo se propaga el id: el servicio revalida el rol de plataforma contra la base y no
	 * acepta que se lo afirmen desde aca.
	 */
	private long actorAccountId() {
		return ApiActor.current(tenantContextHolder).accountId();
	}
}
