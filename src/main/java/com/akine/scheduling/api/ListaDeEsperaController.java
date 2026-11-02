package com.akine.scheduling.api;

import com.akine.scheduling.api.dto.AltaEnListaDeEsperaRequest;
import com.akine.scheduling.api.dto.AsignarDesdeListaDeEsperaRequest;
import com.akine.scheduling.api.dto.DescartarDeListaDeEsperaRequest;
import com.akine.scheduling.api.dto.EntradaDeEsperaResponse;
import com.akine.scheduling.api.dto.ListaDeEsperaPageResponse;
import com.akine.scheduling.application.AltaEnListaDeEsperaCommand;
import com.akine.scheduling.application.ListaDeEsperaService;
import com.akine.scheduling.domain.EstadoListaDeEspera;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

import java.net.URI;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/** Lista de espera de turnos (TURNO.md §20). Anotarse no ocupa la agenda; asignar crea un turno comun. */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/lista-de-espera",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Lista de espera", description = "Quienes esperan lugar para una prestacion")
public class ListaDeEsperaController {

	private final ListaDeEsperaService service;
	private final SchedulingApiActor apiActor;

	public ListaDeEsperaController(ListaDeEsperaService service, SchedulingApiActor apiActor) {
		this.service = service;
		this.apiActor = apiActor;
	}

	@PostMapping
	@Operation(operationId = "anotarEnListaDeEspera", summary = "Anotar a una persona en la lista de espera")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Anotada"),
			@ApiResponse(responseCode = "403", description = "Sin `turno:manage` en esa sede", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede, la oferta o la persona no existen, o son de otro tenant", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La persona no es paciente, o el caso o el plan no sirven para esta prestacion", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EntradaDeEsperaResponse> anotar(
			@PathVariable long consultorioId,
			@RequestBody @Valid AltaEnListaDeEsperaRequest request) {

		EnumSet<DayOfWeek> dias = EnumSet.noneOf(DayOfWeek.class);
		if (request.dias() != null) {
			request.dias().forEach(dia -> dias.add(DayOfWeek.of(dia)));
		}
		var entrada = service.anotar(apiActor.current(), consultorioId, new AltaEnListaDeEsperaCommand(
				request.personaId(), request.ofertaId(), request.profesionalId(), request.casoId(), request.planId(),
				request.fechaDesde(), request.fechaHasta(), dias, request.horaDesde(), request.horaHasta(),
				request.prioridad(), request.notas()));
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/lista-de-espera/" + entrada.id()))
				.body(EntradaDeEsperaResponse.de(entrada));
	}

	@GetMapping
	@Operation(
			operationId = "listarListaDeEspera",
			summary = "Lista de espera de la sede",
			description = "Primero las mas urgentes y, a igual prioridad, las mas viejas. Exige `turno:read`.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pagina de la lista"),
			@ApiResponse(responseCode = "403", description = "Sin `turno:read` en esa sede", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede no existe o es de otro tenant", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ListaDeEsperaPageResponse> listar(
			@PathVariable long consultorioId,
			@RequestParam(required = false) EstadoListaDeEspera estado,
			@RequestParam(required = false) Long ofertaId,
			@RequestParam(required = false) Long personaId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

		return ResponseEntity.ok(ListaDeEsperaPageResponse.de(
				service.listar(apiActor.current(), consultorioId, estado, ofertaId, personaId, page, size), page, size));
	}

	@GetMapping("/coincidencias")
	@Operation(
			operationId = "coincidenciasEnListaDeEspera",
			summary = "Quienes esperan un lugar como ese",
			description = """
					Las entradas activas a las que les sirve un horario: el de un turno (`turnoId`, tipicamente \
					el que se acaba de cancelar) o el que se indique (`ofertaId` e `inicio`). Primero las mas \
					urgentes. Exige `turno:read`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Entradas compatibles"),
			@ApiResponse(responseCode = "400", description = "Falta decir con que comparar: un turno o una oferta con su horario", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin `turno:read` en esa sede", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede, el turno o la oferta no existen, o son de otro tenant", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<EntradaDeEsperaResponse>> coincidencias(
			@PathVariable long consultorioId,
			@RequestParam(required = false) Long turnoId,
			@RequestParam(required = false) Long ofertaId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant inicio,
			@RequestParam(required = false) Long profesionalId) {

		return ResponseEntity.ok(service.coincidencias(
						apiActor.current(), consultorioId, turnoId, ofertaId, inicio, profesionalId).stream()
				.map(EntradaDeEsperaResponse::de)
				.toList());
	}

	@PostMapping("/{entradaId}/asignacion")
	@Operation(
			operationId = "asignarDesdeListaDeEspera",
			summary = "Darle un turno a quien espera",
			description = """
					Crea un turno comun con todas sus validaciones: estar en la lista no da un lugar que no \
					existe. Los conflictos son los de cualquier reserva, y la entrada queda activa si fallan. \
					Reintentar con la misma version no reserva un segundo turno.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Turno asignado"),
			@ApiResponse(responseCode = "403", description = "Sin `turno:manage` en esa sede", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede o la entrada no existen, o son de otro tenant", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "El horario no se puede reservar, la entrada ya se resolvio, o la version quedo vieja", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EntradaDeEsperaResponse> asignar(
			@PathVariable long consultorioId,
			@PathVariable long entradaId,
			@RequestBody @Valid AsignarDesdeListaDeEsperaRequest request) {

		return ResponseEntity.ok(EntradaDeEsperaResponse.de(service.asignar(
				apiActor.current(), consultorioId, entradaId, request.inicio(), request.profesionalId(),
				request.espacioId(), request.expectedVersion())));
	}

	@PostMapping("/{entradaId}/descarte")
	@Operation(operationId = "descartarDeListaDeEspera", summary = "Sacar a alguien de la lista sin darle turno")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Descartada"),
			@ApiResponse(responseCode = "403", description = "Sin `turno:manage` en esa sede", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede o la entrada no existen, o son de otro tenant", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La entrada ya se resolvio, o la version quedo vieja", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EntradaDeEsperaResponse> descartar(
			@PathVariable long consultorioId,
			@PathVariable long entradaId,
			@RequestBody @Valid DescartarDeListaDeEsperaRequest request) {

		return ResponseEntity.ok(EntradaDeEsperaResponse.de(service.descartar(
				apiActor.current(), consultorioId, entradaId, request.motivo(), request.expectedVersion())));
	}
}
