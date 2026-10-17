package com.akine.activity.api;

import com.akine.activity.api.dto.AtencionDeParticipanteResponse;
import com.akine.activity.api.dto.AtencionGrupalResponse;
import com.akine.activity.api.dto.IniciarAtencionDeParticipanteRequest;
import com.akine.activity.api.dto.IniciarAtencionGrupalRequest;
import com.akine.activity.api.dto.ResultadoDeAtencionGrupalResponse;
import com.akine.activity.application.AtencionDeParticipanteView;
import com.akine.activity.application.AtencionDeParticipantesService;
import com.akine.activity.application.PedidoDeAtencionDeParticipante;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Atencion clinica de los participantes de una clase (AKINE-08.05, RF-M14-011..013).
 *
 * <p>Tres operaciones y ninguna mas: abrir la atencion de uno, abrir la de varios y ver el panel.
 * <b>Todo lo demas es la Sesion</b>: borrador, evaluacion, tratamientos, mediciones, cierre y
 * enmienda van por {@code /consultorios/{c}/sesiones/{sesionId}}, sin una sola ruta paralela. Por
 * eso la atencion de cada participante es independiente: es su Sesion.
 *
 * <p>{@code X-Justificacion-Acceso} es la cabecera de 04.01, con la misma semantica que en la
 * derivacion: la decide la politica clinica, y si faltaba el rechazo es 403
 * {@code justificacion-de-acceso-requerida}. En el lote viaja una vez para todos los items: es el
 * mismo profesional atendiendo la misma clase.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/clases/{claseId}",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(
		name = "Atencion clinica grupal",
		description = "La Sesion de cada participante de una clase clinica (M14, M28)")
public class AtencionGrupalController {

	private static final String DESCRIPCION_JUSTIFICACION =
			"Motivo declarado del acceso clinico (DP-03). Lo exige la politica clinica cuando no "
					+ "hay relacion asistencial; si hacia falta y no vino, 403 "
					+ "`justificacion-de-acceso-requerida`.";

	private final AtencionDeParticipantesService service;
	private final ActivityApiActor apiActor;

	public AtencionGrupalController(AtencionDeParticipantesService service, ActivityApiActor apiActor) {
		this.service = service;
		this.apiActor = apiActor;
	}

	@PostMapping("/inscripciones/{inscripcionId}/atencion-clinica")
	@Operation(
			operationId = "iniciarAtencionDeParticipante",
			summary = "Abrir la atencion clinica de un participante",
			description = """
					Abre la **Sesion** del participante desde su derivacion clinica vigente \
					(RF-M14-012). Es una Sesion como cualquier otra, sin turno y con el Caso de la \
					derivacion, que no se puede cambiar; se numera dentro del Caso **al cerrar**.

					Orden de los gates: la oferta de la clase genera registro clinico \
					(`oferta-no-clinica`); la persona estuvo (`participacion-sin-asistencia`); \
					`sesion:register` en la sede; `hc:write` con relacion asistencial o \
					justificacion; una derivacion vigente (`participacion-sin-derivacion`, o \
					`derivacion-ambigua` si hay varias y no se eligio); el Caso sigue activo \
					(`caso-clinico-cerrado`).

					**Idempotente por derivacion**: repetir devuelve 200 con la Sesion existente y \
					`yaExistia = true`. Quien la abre es su profesional: escribir en ella es solo \
					suyo (409 `sesion-ajena` para cualquier otro).""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Atencion abierta"),
			@ApiResponse(responseCode = "200", description = "Ya estaba abierta: se devuelve la existente"),
			@ApiResponse(
					responseCode = "400",
					description = "Datos invalidos",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `inscripcion:read` o `sesion:register` en la sede, sin "
							+ "`hc:write`, sin justificacion cuando hacia falta, o sin contexto",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la clase, la inscripcion, la oferta o el Caso no existen "
							+ "en este alcance",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Oferta no clinica, participante ausente o sin asistencia, sin "
							+ "derivacion vigente, derivacion ambigua o Caso cerrado",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AtencionDeParticipanteResponse> iniciar(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@PathVariable long inscripcionId,
			@RequestBody(required = false) @Valid IniciarAtencionDeParticipanteRequest request,
			@Parameter(description = DESCRIPCION_JUSTIFICACION)
			@RequestHeader(name = DerivacionController.JUSTIFICACION, required = false)
			String justificacion) {

		AtencionDeParticipanteView vista = service.iniciar(apiActor.current(), consultorioId, claseId,
				inscripcionId, request == null ? null : request.derivacionId(), justificacion);

		AtencionDeParticipanteResponse cuerpo = AtencionDeParticipanteResponse.de(vista);
		// 200 cuando ya existia: un 201 afirmaria que se creo algo que no se creo.
		if (vista.atencion().yaExistia()) {
			return ResponseEntity.ok(cuerpo);
		}
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/sesiones/"
						+ vista.atencion().sesionId()))
				.body(cuerpo);
	}

	@PostMapping("/atencion-clinica/lote")
	@Operation(
			operationId = "iniciarAtencionGrupal",
			summary = "Abrir la atencion clinica de varios participantes",
			description = """
					Lo mismo que la operacion individual, para varios participantes. **Cada item \
					corre en su propia transaccion**: un fallo individual no revierte ni oculta a \
					los demas, y trae el mismo `problemType` que la operacion individual.

					**Responde 200 aunque haya fallos parciales.** Lo que vale para todo el lote \
					—sede, clase, permiso de ver la clase y que la oferta sea clinica— se valida \
					una vez y sale como error del pedido.

					**Abre, no cierra**: cada cierre lleva la evolucion de su paciente y va por la \
					Sesion (RF-M14-013, no hay evolucion global de la clase).""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Resultado por participante"),
			@ApiResponse(
					responseCode = "400",
					description = "Lote vacio o por encima del tope",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `inscripcion:read` en la sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la clase o la oferta no existen en este alcance",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La oferta de la clase no genera registro clinico",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResultadoDeAtencionGrupalResponse iniciarLote(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@RequestBody @Valid IniciarAtencionGrupalRequest request,
			@Parameter(description = DESCRIPCION_JUSTIFICACION)
			@RequestHeader(name = DerivacionController.JUSTIFICACION, required = false)
			String justificacion) {

		List<PedidoDeAtencionDeParticipante> pedidos = request.items().stream()
				.map(item -> new PedidoDeAtencionDeParticipante(item.inscripcionId(), item.derivacionId()))
				.toList();
		return ResultadoDeAtencionGrupalResponse.de(service.iniciarLote(
				apiActor.current(), consultorioId, claseId, pedidos, justificacion));
	}

	@GetMapping("/atencion-clinica")
	@Operation(
			operationId = "verAtencionGrupal",
			summary = "Panel de atencion clinica de la clase",
			description = """
					Cada participante con asistencia registrada, si estuvo, y sus atenciones \
					clinicas con estado y numeros (RF-M14-011). **Sin contenido clinico**: cada \
					ficha se abre por su Sesion. Exige `sesion:register` y queda auditada, porque \
					decir quien esta en tratamiento ya es informacion clinica. En una clase no \
					clinica (`clinica = false`) no hay atenciones y no se consulta nada clinico.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El panel"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `inscripcion:read` o `sesion:register` en la sede, o sin "
							+ "contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la clase o la oferta no existen en este alcance",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public AtencionGrupalResponse panel(
			@PathVariable long consultorioId,
			@PathVariable long claseId) {

		return AtencionGrupalResponse.de(service.panel(apiActor.current(), consultorioId, claseId));
	}
}
