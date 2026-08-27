package com.akine.identity.api;

import com.akine.identity.api.dto.AcceptInvitacionRequest;
import com.akine.identity.api.dto.AcceptedInvitacionResponse;
import com.akine.identity.api.dto.DeclineInvitacionRequest;
import com.akine.identity.api.dto.InvitacionPreviewResponse;
import com.akine.identity.api.dto.InvitacionTokenRequest;
import com.akine.identity.application.ColaboradorInvitacionService;
import com.akine.identity.application.InvitacionAceptacionCommand;
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

/**
 * Lado del invitado de la invitacion a colaborar (RF-M05-002, AKINE-02.03).
 *
 * <h2>Las tres operaciones son publicas, y la autoridad es el token</h2>
 *
 * <p>Quien acepta o rechaza <b>no tiene sesion</b> —y muchas veces ni cuenta—, asi que no hay
 * nada que exigirle: no pertenece al tenant. Lo que lo autoriza es el token del enlace, que
 * prueba que llega al buzon al que la invitacion se emitio. Es la misma forma que ya tienen
 * {@code POST /auth/activate} y {@code POST /auth/password-reset/confirm}.
 *
 * <p>Por eso viven bajo {@code /api/v1/auth}, junto al resto de lo publico, y no bajo
 * {@code /organizations/{orgId}}: pedir el {@code orgId} obligaria al invitado a conocer un id
 * del tenant al que todavia no pertenece, y ponerlo en la URL invitaria a probar otros.
 *
 * <h2>Por que todo es POST, incluso consultar</h2>
 *
 * <p>El token viaja en el cuerpo. Un token en la query string queda en los logs de acceso, en el
 * historial del navegador, en el {@code Referer} de cualquier recurso externo que la pantalla
 * cargue y en el de los proxies intermedios. Un {@code GET} seria mas idiomatico y filtraria la
 * credencial en cuatro lugares.
 *
 * <h2>Que se responde cuando el token no resuelve</h2>
 *
 * <p>404 uniforme (ADR-0018) para token inventado, invitacion de otro tenant e invitacion ya
 * resuelta. La unica excepcion es el token <b>vencido</b>, que responde 409: quien lo presenta
 * ya demostro que es el destinatario, asi que no hay nada que enumerar, y decirle "vencio" en
 * vez de "no existe" es la diferencia entre pedir un reenvio y reportar que el sistema esta roto.
 */
// Tag propio y no el mismo que el del administrador: son dos publicos distintos —quien invita
// y quien fue invitado— y ademas el contrato no admite dos declaraciones del mismo tag con
// descripciones distintas (el generador del cliente lo rechaza como spec invalida).
@Tag(
		name = "Invitaciones recibidas",
		description = "Consulta, aceptacion y rechazo de una invitacion, con el token como unica "
				+ "autoridad (M05)")
@RestController
@RequestMapping(path = "/api/v1/auth/invitations", produces = MediaType.APPLICATION_JSON_VALUE)
public class InvitacionPublicaController {

	private final ColaboradorInvitacionService invitacionService;

	public InvitacionPublicaController(ColaboradorInvitacionService invitacionService) {
		this.invitacionService = invitacionService;
	}

	@PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "previewInvitacion",
			summary = "Ver que dice una invitacion, sin consumirla",
			description = """
					Publico: la autoridad es el token, no una sesion.

					NO CONSUME LA INVITACION. Un enlace que se gasta al mirarlo deja al invitado \
					sin poder aceptar apenas recargue la pantalla, o apenas su cliente de correo \
					lo pre-visite para armar una vista previa.

					requiereRegistro le dice a la pantalla si pedir nombre y contrasena. Es lo \
					unico que este endpoint revela sobre la existencia de una cuenta, y solo se \
					lo revela a quien ya probo que llega a ese buzon.

					La respuesta no trae ningun id: quien presenta el token no pertenece a la \
					organizacion, y cada id que se le entregue es una pieza mas para adivinar el \
					resto.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Datos de la invitacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = InvitacionPreviewResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el token",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El token no resuelve, o la invitacion ya fue resuelta. Los "
							+ "casos responden igual",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El enlace vencio (invitacion-vencida). Se distingue del 404 a "
							+ "proposito: la salida es pedir un reenvio",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<InvitacionPreviewResponse> preview(
			@Valid @RequestBody InvitacionTokenRequest request) {

		return ResponseEntity.ok(
				InvitacionPreviewResponse.de(invitacionService.consultar(request.token())));
	}

	@PostMapping(path = "/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "acceptInvitacion",
			summary = "Aceptar una invitacion y quedar vinculado",
			description = """
					Publico: la autoridad es el token.

					Si el invitado no tiene cuenta, la crea EN ESTE MISMO ACTO y ya ACTIVA, sin \
					un segundo correo de activacion: el token que acaba de presentar ya probo que \
					llega a esa direccion, y verificarlo dos veces solo agrega el paso donde la \
					mitad de la gente abandona. Para eso hacen falta nombre y contrasena, que \
					pasa por la misma politica que el registro self-service.

					Si ya tiene cuenta, nombre y contrasena se IGNORAN. Reescribirlos seria una \
					via para tomarle la cuenta a otro con solo invitarlo.

					NO DEVUELVE SESION. Quien acepta con una cuenta que ya tenia probo que llega \
					al buzon, no que la cuenta sea suya: una casilla abierta en una maquina \
					compartida alcanza para lo primero y no para lo segundo. La pantalla \
					siguiente es el login.

					Aceptar consume cupo del plan. Si el tenant llego al tope, la respuesta es \
					409 y la invitacion queda pendiente.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Vinculo creado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AcceptedInvitacionResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el token, o faltan nombre y contrasena cuando hay que "
							+ "crear la cuenta, o la contrasena no cumple la politica",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El token no resuelve o la invitacion ya fue resuelta",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El enlace vencio (invitacion-vencida), la persona ya fue "
							+ "vinculada por otro camino (colaborador-ya-vinculado), o el tenant "
							+ "llego al tope de miembros de su plan",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AcceptedInvitacionResponse> accept(
			@Valid @RequestBody AcceptInvitacionRequest request) {

		return ResponseEntity.ok(AcceptedInvitacionResponse.de(
				invitacionService.aceptar(new InvitacionAceptacionCommand(
						request.token(), request.nombre(), request.apellido(), request.password()))));
	}

	// Produces explicito por lo mismo que en AccountRegistrationController.activate: sin cuerpo
	// en el 204 el contrato solo declara problem+json, el cliente generado lo manda en Accept y
	// el produces de clase lo rechazaba con 406.
	@PostMapping(path = "/decline", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE })
	@Operation(
			operationId = "declineInvitacion",
			summary = "Rechazar una invitacion recibida",
			description = """
					Publico: la autoridad es el token.

					Consume la invitacion —queda RECHAZADA y el enlace deja de servir— y NO CREA \
					NINGUNA CUENTA: quien no quiere entrar no tiene por que quedar registrado en \
					AKINE.

					El motivo es opcional. Rechazar una oferta de trabajo no exige explicarse. La \
					cancelacion del administrador, en cambio, si exige motivo.

					El rechazo no bloquea nada: se puede volver a invitar a esa persona mas \
					adelante.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Invitacion rechazada"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el token",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El token no resuelve o la invitacion ya fue resuelta",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El enlace vencio (invitacion-vencida)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> decline(@Valid @RequestBody DeclineInvitacionRequest request) {
		invitacionService.rechazar(request.token(), request.reason());
		return ResponseEntity.noContent().build();
	}
}
