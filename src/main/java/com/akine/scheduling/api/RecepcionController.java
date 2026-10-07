package com.akine.scheduling.api;

import com.akine.scheduling.api.dto.AnularRecepcionRequest;
import com.akine.scheduling.api.dto.AtenderComoParticularRequest;
import com.akine.scheduling.api.dto.EventoDeRecepcionResponse;
import com.akine.scheduling.api.dto.RecepcionResponse;
import com.akine.scheduling.api.dto.TransicionDeRecepcionRequest;
import com.akine.scheduling.api.dto.ValidarRecepcionRequest;
import com.akine.scheduling.application.CicloDeRecepcionService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * La recepcion de un turno: llegada, validacion administrativa, Particular, espera y llamado (M13,
 * AKINE E-4, DP-16).
 *
 * <p>Cuelga del turno porque la recepcion es de la RESERVA: no hay recepcion sin turno. Es un
 * recurso aparte —y no mas sub-recursos de {@code TurnoController}— porque es otra maquina de
 * estados (DP-05) con su propia version.
 *
 * <p>Todas las transiciones son POST a un sub-recurso con la version leida en el cuerpo, como las
 * del turno. <b>Ningun cuerpo trae una hora</b>: la pone el servidor.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/turnos/{turnoId}/recepcion",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Recepcion", description = "Llegada, validacion administrativa, espera y llamado (M13, DP-16)")
public class RecepcionController {

	private final CicloDeRecepcionService recepcion;
	private final SchedulingApiActor apiActor;

	public RecepcionController(CicloDeRecepcionService recepcion, SchedulingApiActor apiActor) {
		this.recepcion = recepcion;
		this.apiActor = apiActor;
	}

	@PostMapping
	@Operation(
			operationId = "registrarLlegadaRecepcion",
			summary = "Registrar la llegada del paciente (check-in)",
			description = """
					Abre la recepcion del turno en `LLEGO` (RF-M13-002). La hora la pone el \
					servidor: **no se envia ningun cuerpo**.

					**Es idempotente**: con una recepcion abierta devuelve 200 con esa misma, sin \
					mover la hora ni registrar otro evento. Dos check-in simultaneos dan los dos la \
					misma recepcion.

					No cambia el estado del turno (DP-16): el turno es la reserva y sigue en \
					`RESERVADO` o `CONFIRMADO`. Si avanza su `version`: una cancelacion que leyo el \
					turno antes de la llegada se rechaza en vez de dejar a alguien en la sala con \
					el turno cancelado.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Recepcion abierta"),
			@ApiResponse(responseCode = "200", description = "Ya habia una recepcion abierta: se devuelve esa"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El turno esta cancelado o ausente (`turno-transicion-no-permitida`), "
							+ "o lo cancelaron mientras se registraba la llegada",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<RecepcionResponse> registrarLlegada(
			@PathVariable long consultorioId,
			@PathVariable long turnoId) {

		var resultado = recepcion.registrarLlegada(apiActor.current(), consultorioId, turnoId);
		RecepcionResponse cuerpo = RecepcionResponse.de(resultado.recepcion());
		if (!resultado.creada()) {
			return ResponseEntity.ok(cuerpo);
		}
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/turnos/" + turnoId
						+ "/recepcion"))
				.body(cuerpo);
	}

	@GetMapping
	@Operation(
			operationId = "verRecepcion",
			summary = "Ver la recepcion del turno",
			description = """
					La recepcion vigente del turno: abierta, o `CERRADA` si el turno se cancelo con \
					la persona presente. Una anulada no es vigente. Exige `turno:read`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La recepcion"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:read` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen o son de otro tenant, o nadie "
							+ "registro la llegada",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<RecepcionResponse> ver(
			@PathVariable long consultorioId,
			@PathVariable long turnoId) {

		return ResponseEntity.ok(RecepcionResponse.de(
				recepcion.ver(apiActor.current(), consultorioId, turnoId)));
	}

	@GetMapping("/historial")
	@Operation(
			operationId = "historialRecepcion",
			summary = "Historial de la recepcion del turno",
			description = """
					Todas las transiciones de todas las recepciones del turno —incluidas las de un \
					check-in anulado—, de la mas vieja a la mas nueva, con actor, hora, estado \
					anterior, estado nuevo y motivo (DP-05). Exige `turno:read`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Historial"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:read` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<EventoDeRecepcionResponse>> historial(
			@PathVariable long consultorioId,
			@PathVariable long turnoId) {

		return ResponseEntity.ok(recepcion.historial(apiActor.current(), consultorioId, turnoId)
				.stream().map(EventoDeRecepcionResponse::de).toList());
	}

	@PostMapping("/validacion")
	@Operation(
			operationId = "validarRecepcion",
			summary = "Validar cobertura y documentacion",
			description = """
					RF-M13-003 y RF-M13-004. **El servidor calcula el resultado**: busca la \
					practica principal de la oferta, la cobertura aplicable de la persona ese dia y \
					la elegibilidad administrativa del convenio vigente.

					- Elegible: `VALIDADA`, modalidad `COBERTURA`, con cobertura y convenio.
					- Cualquier otra cosa: `OBSERVADA`, con el detalle en `observacion`.

					**Una observacion no es un error y no bloquea** (RN-M13-003): responde 200. La \
					recepcion puede pasar a espera igual, o continuar como Particular. Se puede \
					revalidar una observada (el paciente trajo la orden).

					No consume ninguna autorizacion (RN-M17-001) ni genera ninguna deuda.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Recepcion VALIDADA u OBSERVADA"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "No hay recepcion abierta o su estado no admite validar "
							+ "(`recepcion-transicion-no-permitida`), o la version quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<RecepcionResponse> validar(
			@PathVariable long consultorioId,
			@PathVariable long turnoId,
			@RequestBody @Valid ValidarRecepcionRequest request) {

		return ResponseEntity.ok(RecepcionResponse.de(recepcion.validar(
				apiActor.current(), consultorioId, turnoId,
				request.coberturaId(), request.expectedVersion())));
	}

	@PostMapping("/particular")
	@Operation(
			operationId = "atenderComoParticular",
			summary = "Continuar como Particular",
			description = """
					RF-M13-005. Decision explicita del operador, **con motivo obligatorio**, cuando \
					la persona no tiene cobertura aplicable o la validacion quedo observada. Deja la \
					recepcion `VALIDADA` con modalidad `PARTICULAR`.

					**No modifica la cobertura maestra del paciente** (RN-M13-004): es un dato de \
					esta recepcion. Desde `LLEGO` u `OBSERVADA`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Recepcion validada como Particular"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "No hay recepcion abierta o su estado no lo admite "
							+ "(`recepcion-transicion-no-permitida`), o la version quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<RecepcionResponse> atenderComoParticular(
			@PathVariable long consultorioId,
			@PathVariable long turnoId,
			@RequestBody @Valid AtenderComoParticularRequest request) {

		return ResponseEntity.ok(RecepcionResponse.de(recepcion.atenderComoParticular(
				apiActor.current(), consultorioId, turnoId,
				request.motivo(), request.expectedVersion())));
	}

	@PostMapping("/espera")
	@Operation(
			operationId = "pasarAEsperaRecepcion",
			summary = "Pasar a la sala de espera",
			description = """
					Desde `VALIDADA` u `OBSERVADA` (la observacion advierte, no bloquea). Desde \
					`LLEGO` no: falta resolver como se atiende.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "En espera"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Estado que no lo admite (`recepcion-transicion-no-permitida`), o "
							+ "la version quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<RecepcionResponse> pasarAEspera(
			@PathVariable long consultorioId,
			@PathVariable long turnoId,
			@RequestBody @Valid TransicionDeRecepcionRequest request) {

		return ResponseEntity.ok(RecepcionResponse.de(recepcion.pasarAEspera(
				apiActor.current(), consultorioId, turnoId, request.expectedVersion())));
	}

	@PostMapping("/llamado")
	@Operation(
			operationId = "llamarRecepcion",
			summary = "Llamar al paciente",
			description = """
					Desde `EN_ESPERA`. **Llamar no abre la Sesion** y abrir la Sesion no depende de \
					haber llamado: son dos actos de dos personas (DP-05). `LLAMADA` no significa \
					atendido.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Llamado"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Estado que no lo admite (`recepcion-transicion-no-permitida`), o "
							+ "la version quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<RecepcionResponse> llamar(
			@PathVariable long consultorioId,
			@PathVariable long turnoId,
			@RequestBody @Valid TransicionDeRecepcionRequest request) {

		return ResponseEntity.ok(RecepcionResponse.de(recepcion.llamar(
				apiActor.current(), consultorioId, turnoId, request.expectedVersion())));
	}

	@PostMapping("/anulacion")
	@Operation(
			operationId = "anularRecepcion",
			summary = "Anular un check-in hecho por error",
			description = """
					Deja la recepcion `ANULADA`: la llegada no vale. La fila y sus eventos quedan \
					como historia, y un check-in posterior abre una recepcion nueva.

					**No es idempotente**: sin recepcion abierta responde 409.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Recepcion anulada"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "No hay recepcion abierta (`recepcion-transicion-no-permitida`), o "
							+ "la version quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<RecepcionResponse> anular(
			@PathVariable long consultorioId,
			@PathVariable long turnoId,
			@RequestBody @Valid AnularRecepcionRequest request) {

		return ResponseEntity.ok(RecepcionResponse.de(recepcion.anular(
				apiActor.current(), consultorioId, turnoId,
				request.motivo(), request.expectedVersion())));
	}
}
