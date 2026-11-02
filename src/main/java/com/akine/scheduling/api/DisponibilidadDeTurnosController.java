package com.akine.scheduling.api;

import com.akine.scheduling.api.dto.AgendaOperativaResponse;
import com.akine.scheduling.api.dto.BuscarDisponibilidadRequest;
import com.akine.scheduling.api.dto.ContextoDeAgendaResponse;
import com.akine.scheduling.api.dto.DisponibilidadDeTurnosResponse;
import com.akine.scheduling.api.dto.ValidacionDeHorarioResponse;
import com.akine.scheduling.api.dto.ValidacionDeHorarioResponse.RecursoLibreResponse;
import com.akine.scheduling.api.dto.ValidarHorarioRequest;
import com.akine.scheduling.application.AgendaOperativaService;
import com.akine.scheduling.application.BusquedaDeDisponibilidad;
import com.akine.scheduling.application.ContextoDeAgendaService;
import com.akine.scheduling.application.DisponibilidadDeTurnosService;
import com.akine.scheduling.application.ValidacionDeHorarioCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

/**
 * El motor de disponibilidad y lo que necesita el flujo unico de turnos (TURNO - Tecnico §38 a §40).
 *
 * <p><b>POST en la busqueda y la validacion</b> aunque no modifican nada: los criterios son varios y
 * opcionales, y la especificacion lo pide asi. Nada de esto reserva ni bloquea.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Disponibilidad", description = "Que horarios se pueden reservar, y si uno puntual se puede")
public class DisponibilidadDeTurnosController {

	private final DisponibilidadDeTurnosService disponibilidad;
	private final ContextoDeAgendaService contexto;
	private final AgendaOperativaService agendaOperativa;
	private final SchedulingApiActor apiActor;

	public DisponibilidadDeTurnosController(
			DisponibilidadDeTurnosService disponibilidad,
			ContextoDeAgendaService contexto,
			AgendaOperativaService agendaOperativa,
			SchedulingApiActor apiActor) {

		this.disponibilidad = disponibilidad;
		this.contexto = contexto;
		this.agendaOperativa = agendaOperativa;
		this.apiActor = apiActor;
	}

	@PostMapping("/disponibilidad/busqueda")
	@Operation(
			operationId = "buscarDisponibilidadDeTurnos",
			summary = "Buscar horarios disponibles",
			description = """
					Horarios que se pueden reservar **ahora** para una prestacion, del mas cercano al mas \
					lejano. Considera horario del profesional, sede, feriados, cierres y licencias, \
					habilitaciones, box, clases y turnos existentes: **lo mismo que controla la reserva**.

					Sin profesional busca entre todos los habilitados. Con `limite = 1` es el primer turno \
					disponible. **Es informativo**: la reserva revalida todo bajo el lock de la sede.

					Exige `turno:read`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Horarios disponibles"),
			@ApiResponse(responseCode = "403", description = "Sin `turno:read` en esa sede", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede o la oferta no existen, o son de otro tenant", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La oferta no puede agendar en ningun dia de la ventana", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<DisponibilidadDeTurnosResponse> buscar(
			@PathVariable long consultorioId,
			@RequestBody @Valid BuscarDisponibilidadRequest request) {

		EnumSet<DayOfWeek> dias = EnumSet.noneOf(DayOfWeek.class);
		if (request.dias() != null) {
			request.dias().forEach(dia -> dias.add(DayOfWeek.of(dia)));
		}
		return ResponseEntity.ok(DisponibilidadDeTurnosResponse.de(disponibilidad.buscar(
				apiActor.current(), consultorioId, new BusquedaDeDisponibilidad(
						request.ofertaId(), request.profesionalId(), request.desde(), request.hasta(),
						request.horaDesde(), request.horaHasta(), dias, request.limite()))));
	}

	@PostMapping("/disponibilidad/validacion")
	@Operation(
			operationId = "validarHorarioDeTurno",
			summary = "Consultar si un horario se puede reservar",
			description = """
					Si un horario puntual se puede reservar y, si no, **por que y que hay en su lugar**: los \
					horarios mas cercanos en `alternativas`. Con quien puede atender y que boxes hay libres.

					**No reserva ni bloquea nada**, y dos consultas pueden ver el mismo horario libre. Quien \
					decide es la reserva. Exige `turno:read`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El resultado. Un horario que no se puede es un 200 con `NO_DISPONIBLE`"),
			@ApiResponse(responseCode = "403", description = "Sin `turno:read` en esa sede", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede o la oferta no existen, o son de otro tenant", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ValidacionDeHorarioResponse> validar(
			@PathVariable long consultorioId,
			@RequestBody @Valid ValidarHorarioRequest request) {

		return ResponseEntity.ok(ValidacionDeHorarioResponse.de(disponibilidad.validar(
				apiActor.current(), consultorioId, new ValidacionDeHorarioCommand(
						request.ofertaId(), request.inicio(), request.profesionalId(), request.espacioId(),
						request.personaId(), request.casoId(), request.planId(), request.turnoExcluidoId()))));
	}

	@GetMapping("/disponibilidad/profesionales")
	@Operation(
			operationId = "profesionalesParaTurnos",
			summary = "Profesionales que pueden atender",
			description = """
					Con `ofertaId`, los habilitados y vigentes para esa prestacion en esta sede: **una oferta \
					sin habilitaciones declaradas la prestan todos**. Sin `ofertaId`, todos los de la sede. \
					Exige `turno:read`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Profesionales, por nombre"),
			@ApiResponse(responseCode = "403", description = "Sin `turno:read` en esa sede", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede o la oferta no existen, o son de otro tenant", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<RecursoLibreResponse>> profesionales(
			@PathVariable long consultorioId,
			@RequestParam(required = false) Long ofertaId) {

		return ResponseEntity.ok(disponibilidad.profesionales(apiActor.current(), consultorioId, ofertaId).stream()
				.map(RecursoLibreResponse::de)
				.toList());
	}

	@GetMapping("/personas/{personaId}/contexto-de-agenda")
	@Operation(
			operationId = "contextoDeAgendaDePersona",
			summary = "Casos y planes de una persona para programarle turnos",
			description = """
					Sus casos activos, con sus planes activos y **cuantas sesiones le quedan a cada \
					prestacion**: realizadas, programadas y pendientes. Las pendientes son el tope que aplica \
					la reserva.

					Exige `turno:read` y no `hc:read`: **no lleva nada clinico**, ni diagnostico ni objetivos.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El contexto. Sin casos es el caso normal de una primera consulta"),
			@ApiResponse(responseCode = "403", description = "Sin `turno:read` en esa sede", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede o la persona no existen, o son de otro tenant", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ContextoDeAgendaResponse> contextoDePersona(
			@PathVariable long consultorioId,
			@PathVariable long personaId) {

		return ResponseEntity.ok(ContextoDeAgendaResponse.de(
				contexto.dePersona(apiActor.current(), consultorioId, personaId)));
	}

	@GetMapping("/agenda/operativa")
	@Operation(
			operationId = "agendaOperativa",
			summary = "La agenda de la sede para operar",
			description = """
					Todo lo que dibuja la agenda en una ventana de hasta 14 dias: quien atiende y cuando, en \
					que boxes, los turnos con su estado, las clases y los bloqueos. `hasta` es **exclusivo**. \
					Los cancelados no vienen salvo `incluirCancelados=true`. Exige `turno:read`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La agenda"),
			@ApiResponse(responseCode = "400", description = "Fechas ausentes o mal formadas, o ventana de mas de 14 dias", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin `turno:read` en esa sede", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede no existe o es de otro tenant", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AgendaOperativaResponse> agendaOperativa(
			@PathVariable long consultorioId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
			@RequestParam(required = false) Long profesionalId,
			@RequestParam(required = false) Long ofertaId,
			@RequestParam(required = false) Long espacioId,
			@RequestParam(defaultValue = "false") boolean incluirCancelados) {

		return ResponseEntity.ok(AgendaOperativaResponse.de(agendaOperativa.ver(
				apiActor.current(), consultorioId, desde, hasta, profesionalId, ofertaId, espacioId, incluirCancelados)));
	}
}
