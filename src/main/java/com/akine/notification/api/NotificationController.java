package com.akine.notification.api;

import com.akine.notification.api.dto.NotificationPageResponse;
import com.akine.notification.application.ConsultaDeNotificacionesService;
import com.akine.notification.application.ReintentoDeNotificacionService;
import com.akine.notification.domain.OutboxStatus;
import com.akine.platform.spi.listado.OrdenDeListado;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
@Tag(name = "Notificaciones", description = "Listado y reintento administrativo de notificaciones del tenant")
public class NotificationController {

	private static final int TAMANO_MAXIMO = 100;

	private final ReintentoDeNotificacionService reintentos;
	private final ConsultaDeNotificacionesService consultas;
	private final NotificationApiActor apiActor;

	public NotificationController(
			ReintentoDeNotificacionService reintentos,
			ConsultaDeNotificacionesService consultas,
			NotificationApiActor apiActor) {
		this.reintentos = reintentos;
		this.consultas = consultas;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listNotifications",
			summary = "Listar las notificaciones de la organizacion",
			description = """
					Lo que alimenta a retryNotification: sin esto no habia forma de saber que \
					reintentar. Sin `estado` lista las **FALLIDA** y **AGOTADA**, que son las que \
					admiten reintento; se puede pedir cualquier combinacion de estados \
					(`estado=FALLIDA,AGOTADA` o `estado=FALLIDA&estado=AGOTADA`).

					Requiere `colaborador:manage`, el mismo permiso que el reintento. Solo alcanza \
					notificaciones de la organizacion del contexto. Las que nacen sin tenant \
					(recuperacion de contraseña, activaciones fuera del alta de la organizacion) \
					no aparecen; la activacion del fundador en el alta si, porque es de la \
					organizacion.

					**Sin datos clinicos ni contenido:** el destinatario va enmascarado, el cuerpo \
					no sale nunca y el ultimo error es un resumen ya sanitizado. De la mas nueva a \
					la mas vieja. `size` se acota a 100 por request.

					DP-45: `q` coincide con el tipo de notificacion que contiene el texto (sin \
					distinguir mayusculas ni acentos) o con el destinatario EXACTO: como el \
					listado lo enmascara, un "contiene" sobre la casilla real dejaria \
					reconstruirla preguntando letra por letra. `sort` ordena por creadaEn, \
					estado, tipo o intentos.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Pagina de notificaciones",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = NotificationPageResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Un `estado` que no es un valor legal, o `sort` fuera del enum",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion, sin contexto de trabajo activo, o sin `colaborador:manage`",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La organizacion de la ruta no es la del contexto",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<NotificationPageResponse> list(
			@Parameter(description = "Identificador de la organizacion", example = "7")
			@PathVariable long orgId,

			@Parameter(description = "Estados a incluir. Ausente: FALLIDA y AGOTADA")
			@RequestParam(required = false) List<OutboxStatus> estado,

			@Parameter(description = "Numero de pagina, base cero", example = "0")
			@RequestParam(defaultValue = "0") int page,

			@Parameter(description = "Elementos por pagina. Se acota a 100", example = "20")
			@RequestParam(defaultValue = "20") int size,

			@Parameter(description = "Tipo de notificacion o destinatario exacto",
					example = "INVITACION")
			@RequestParam(required = false) String q,

			@Parameter(description = OrdenDeListado.DESCRIPCION + "creadaEn descendente.",
					schema = @Schema(allowableValues = {"creadaEn,asc", "creadaEn,desc",
							"estado,asc", "estado,desc", "tipo,asc", "tipo,desc", "intentos,asc",
							"intentos,desc"}))
			@RequestParam(name = OrdenDeListado.PARAMETRO, required = false) String sort) {

		var orden = OrdenDeListado.leer(
				sort, ConsultaDeNotificacionesService.ORDEN_DE_NOTIFICACIONES.keySet());
		return ResponseEntity.ok(NotificationPageResponse.de(consultas.listar(
				apiActor.current(), orgId, estado, q, orden,
				Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO))));
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
