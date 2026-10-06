package com.akine.notification.api;

import com.akine.notification.application.ReintentoDeNotificacionService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operacion administrativa sobre el outbox de notificaciones (M26).
 *
 * <p><b>Responde 204 sin cuerpo</b>, y por eso el {@code produces} declara {@code application/json}
 * ademas de {@code application/problem+json}: el cliente generado manda
 * {@code Accept: application/problem+json} y sin el primero la negociacion corta con 406 antes de
 * entrar al metodo.
 */
@RestController
@RequestMapping(
		path = "/api/v1/organizations/{orgId}/notifications",
		produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@Tag(name = "Notificaciones", description = "Reintento administrativo de notificaciones del tenant")
public class NotificationController {

	private final ReintentoDeNotificacionService reintentos;
	private final NotificationApiActor apiActor;

	public NotificationController(
			ReintentoDeNotificacionService reintentos, NotificationApiActor apiActor) {
		this.reintentos = reintentos;
		this.apiActor = apiActor;
	}

	@PostMapping("/{notificationId}/retry")
	@Operation(
			operationId = "retryNotification",
			summary = "Reintentar una notificacion fallida",
			description = """
					Devuelve a la cola una notificacion **FALLIDA** o **AGOTADA** y reinicia su \
					contador de intentos (RF-M26-005). No reejecuta ningun negocio: lo unico que se \
					repite es el envio.

					Requiere `colaborador:manage`. Solo alcanza notificaciones de la organizacion \
					del contexto; las de identidad (activacion, recuperacion) no tienen tenant y \
					responden 404.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Notificacion devuelta a la cola"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, sin contexto de trabajo activo, o sin `colaborador:manage`",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La notificacion no existe, o no es de la organizacion del contexto",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`conflict`: la notificacion no esta FALLIDA ni AGOTADA; el "
							+ "estado actual viaja en la propiedad `estado`",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> retry(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,
			@Parameter(description = "Identificador de la notificacion", example = "42")
			@PathVariable long notificationId) {

		reintentos.reintentar(apiActor.current(), orgId, notificationId);
		return ResponseEntity.noContent().build();
	}
}
