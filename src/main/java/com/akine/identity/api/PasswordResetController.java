package com.akine.identity.api;

import com.akine.identity.api.dto.AcceptedResponse;
import com.akine.identity.api.dto.PasswordResetConfirmRequest;
import com.akine.identity.api.dto.PasswordResetRequest;
import com.akine.identity.application.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recuperacion de contrasena en dos pasos (RF-M02-003).
 *
 * <h2>Pedir siempre responde lo mismo</h2>
 *
 * <p>{@code POST /password-reset} devuelve <b>202 con el mismo cuerpo</b> exista la cuenta, no
 * exista, o exista pero este bloqueada o desactivada (ADR-0018). Solo el caso "existe y puede
 * autenticarse" emite el token y encola el correo. El servicio ni siquiera devuelve un booleano
 * que esta capa pudiera usar para variar la respuesta: la firma es {@code void} justamente para
 * que la uniformidad no dependa de que quien escriba el controller se acuerde.
 *
 * <h2>Confirmar corta todas las sesiones</h2>
 *
 * <p>El paso de confirmacion revoca todos los refresh vivos de la cuenta en la misma
 * transaccion en que fija la contrasena nueva. Es el punto del sistema donde se asume que la
 * credencial anterior pudo estar comprometida: dejar viva una sesion abierta con la contrasena
 * vieja haria inutil el cambio. La respuesta borra ademas la cookie de este navegador, que
 * quedo apuntando a una familia ya revocada.
 */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Recuperacion", description = "Restablecimiento de contrasena por enlace de un solo uso")
public class PasswordResetController {

	/** Texto unico del acuse. Constante para que no pueda variar segun lo que ocurrio. */
	private static final String ACUSE =
			"Si el email corresponde a una cuenta, vas a recibir un correo con las instrucciones.";

	private final PasswordResetService passwordResetService;

	public PasswordResetController(PasswordResetService passwordResetService) {
		this.passwordResetService = passwordResetService;
	}

	@PostMapping(path = "/password-reset", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "requestPasswordReset",
			summary = "Pedir un enlace para restablecer la contrasena",
			description = """
					Emite un token de un solo uso, valido treinta minutos, y encola el correo con \
					el enlace. Emitir uno nuevo invalida los anteriores: pedirlo tres veces deja \
					UN enlace vivo, no tres.

					RESPUESTA UNIFORME (ADR-0018): 202 con el mismo cuerpo y el mismo tiempo de \
					respuesta exista o no la cuenta, y este o no habilitada. Solo el caso "existe \
					y puede autenticarse" emite algo.

					Consecuencia incomoda y deliberada: quien tenga la cuenta bloqueada va a \
					recibir este 202, no va a recibir ningun correo, y no hay forma de que la \
					aplicacion se lo diga. Se entera por el canal administrativo. Si se \
					"arreglara" respondiendo distinto, el endpoint pasaria a confirmar que \
					direcciones tienen cuenta.

					El enlace apunta a una pantalla del frontend que lee el token de la query y \
					lo reenvia por POST a /auth/password-reset/confirm.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "202",
					description = "Solicitud aceptada. Mismo cuerpo exista o no la cuenta",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AcceptedResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el email",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "429",
					description = "Demasiados intentos desde esta IP",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AcceptedResponse> request(
			@Valid @RequestBody PasswordResetRequest request) {

		passwordResetService.solicitar(request.email());
		return ResponseEntity.accepted().body(AcceptedResponse.de(ACUSE));
	}

	@PostMapping(path = "/password-reset/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "confirmPasswordReset",
			summary = "Fijar la contrasena nueva con el token del correo",
			description = """
					Consume el token, fija la contrasena nueva y REVOCA TODAS las sesiones vivas \
					de la cuenta en la misma transaccion. La respuesta borra ademas la cookie de \
					refresh de este navegador, que quedo apuntando a una familia revocada.

					Confirmar NO abre sesion: despues de esto el frontend manda al login. Es \
					deliberado —la persona acaba de demostrar que controla el correo, no que \
					recuerda la contrasena que acaba de elegir— y hace que el flujo termine \
					siempre en el mismo lugar.

					Token inexistente, usado, invalidado, vencido o de una cuenta que se bloqueo \
					entre el pedido y la confirmacion: los cinco casos responden el mismo 400 \
					invalid-token.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "204",
					description = "Contrasena actualizada y sesiones revocadas"),
			@ApiResponse(
					responseCode = "400",
					description = "Token invalido, usado o vencido; o contrasena que no cumple "
							+ "la politica",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> confirm(
			@Valid @RequestBody PasswordResetConfirmRequest request) {

		passwordResetService.confirmar(request.token(), request.password());

		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, RefreshCookies.borrar())
				.build();
	}
}
