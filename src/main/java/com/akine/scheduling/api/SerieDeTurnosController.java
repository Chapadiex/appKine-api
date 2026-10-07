package com.akine.scheduling.api;

import com.akine.scheduling.api.dto.AlcanceDeSerieResponse;
import com.akine.scheduling.api.dto.CancelarSerieDeTurnosRequest;
import com.akine.scheduling.api.dto.CrearSerieDeTurnosRequest;
import com.akine.scheduling.api.dto.ReprogramarSerieDeTurnosRequest;
import com.akine.scheduling.api.dto.SerieDeTurnosPageResponse;
import com.akine.scheduling.api.dto.SerieDeTurnosResponse;
import com.akine.scheduling.application.AltaDeSerieCommand;
import com.akine.scheduling.application.OperacionDeSerieCommand;
import com.akine.scheduling.application.SerieDeTurnosService;
import com.akine.scheduling.domain.AlcanceDeSerie;
import com.akine.scheduling.domain.EstadoDeSerie;
import com.akine.scheduling.domain.ReglaDeRecurrencia;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.DayOfWeek;
import java.util.EnumSet;

/**
 * Series de turnos (AKINE E-3, DP-04).
 *
 * <p>Ruta propia y no un sub-recurso de {@code /turnos}: una serie no es un turno, y colgarla de
 * {@code /turnos/series} competiria con {@code /turnos/{turnoId}}. Los endpoints de un turno suelto
 * siguen sirviendo para una ocurrencia sola: cancelarla o moverla no afecta a sus hermanas.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/series-de-turnos",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Series de turnos", description = "Turnos recurrentes con identidad propia y operaciones con alcance (DP-04)")
public class SerieDeTurnosController {

	/** Tope duro de la bandeja (E-8), como el resto de los listados paginados de la API. */
	private static final int TAMANO_MAXIMO = 100;

	private final SerieDeTurnosService service;
	private final SchedulingApiActor apiActor;

	public SerieDeTurnosController(SerieDeTurnosService service, SchedulingApiActor apiActor) {
		this.service = service;
		this.apiActor = apiActor;
	}

	@PostMapping
	@Operation(
			operationId = "crearSerieDeTurnos",
			summary = "Crear una serie semanal de turnos",
			description = """
					Reserva **todas** las ocurrencias de una regla semanal —dias, hora local, desde y \
					cantidad o fecha fin— **o ninguna**. Cada ocurrencia se revalida como una reserva \
					suelta y bajo el mismo lock de sede, asi que una serie y una reserva que pisan el \
					mismo hueco no entran las dos.

					Si una ocurrencia no tiene lugar, no se crea nada y el 409 conserva el tipo de la \
					causa (`slot-no-disponible`, `slot-completo`, `recurso-ocupado`, \
					`oferta-no-agendable`) con la propiedad `ocurrenciaInicio`.

					Cada ocurrencia es un turno pleno: conserva identidad, estado e historial propios \
					(DP-04), y una ausencia en la primera **no** elimina la serie.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Serie creada con todas sus ocurrencias"),
			@ApiResponse(
					responseCode = "200",
					description = "Reintento con la misma clave y el mismo pedido: la serie ya creada"),
			@ApiResponse(
					responseCode = "400",
					description = "Regla invalida: sin dias, cantidad y fecha fin juntas o ninguna, mas de "
							+ "52 ocurrencias, o la primera ya paso",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la oferta o la persona no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Una ocurrencia no tiene lugar (con `ocurrenciaInicio`), la persona no "
							+ "es paciente, o la clave de idempotencia se reuso con otro pedido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SerieDeTurnosResponse> crear(
			@PathVariable long consultorioId,
			@RequestBody @Valid CrearSerieDeTurnosRequest request) {

		EnumSet<DayOfWeek> dias = EnumSet.noneOf(DayOfWeek.class);
		request.diasSemana().forEach(dia -> dias.add(DayOfWeek.of(dia)));

		var resultado = service.crear(apiActor.current(), consultorioId, new AltaDeSerieCommand(
				request.ofertaId(),
				request.personaId(),
				request.profesionalId(),
				new ReglaDeRecurrencia(dias, request.hora(), request.fechaDesde(),
						request.fechaHasta(), request.cantidad()),
				request.idempotencyKey()));

		SerieDeTurnosResponse cuerpo = SerieDeTurnosResponse.de(resultado.serie());
		if (!resultado.creada()) {
			return ResponseEntity.ok(cuerpo);
		}
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId
						+ "/series-de-turnos/" + resultado.serie().id()))
				.body(cuerpo);
	}

	@GetMapping
	@Operation(
			operationId = "listarSeriesDeTurnos",
			summary = "Bandeja de series de turnos de la sede",
			description = """
					Las series de la sede, **mas nuevas primero**, con la regla resumida, el paciente \
					y la oferta resueltos, y la foto de sus turnos hoy: cuantos tiene, cuantos quedan \
					pendientes y cuando es el proximo. Para ver los turnos de una, `verSerieDeTurnos`.

					- `personaId`: solo las series de ese paciente. Uno de otro tenant da una pagina \
					vacia, no un error.
					- `estado`: la serie **no tiene estado propio** (DP-04); se deriva de sus turnos al \
					leer. `VIGENTE` = le queda algun turno RESERVADO o CONFIRMADO que todavia no \
					empezo; `FINALIZADA` = no le queda ninguno.

					Paginado base cero; `size` se acota a 100. Exige `turno:read`. PHI minima: \
					nombre y documento del paciente, nada clinico.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Una pagina de series"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:read` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SerieDeTurnosPageResponse> listar(
			@PathVariable long consultorioId,
			@RequestParam(required = false) @Parameter(description = "Solo las series de este paciente", example = "128")
			Long personaId,
			@RequestParam(required = false) @Parameter(description = "Estado derivado de sus turnos", example = "VIGENTE")
			EstadoDeSerie estado,
			@RequestParam(defaultValue = "0") @Parameter(description = "Pagina, base cero") int page,
			@RequestParam(defaultValue = "20") @Parameter(description = "Tamano de pagina, maximo 100") int size) {

		int pagina = Math.max(page, 0);
		int tamano = Math.min(Math.max(size, 1), TAMANO_MAXIMO);
		return ResponseEntity.ok(SerieDeTurnosPageResponse.of(
				service.listar(apiActor.current(), consultorioId, personaId, estado, pagina, tamano),
				pagina, tamano));
	}

	@GetMapping("/{serieId}")
	@Operation(
			operationId = "verSerieDeTurnos",
			summary = "Ver una serie y sus turnos",
			description = """
					La regla con la que se genero y los turnos **tal como estan hoy**, en cualquier \
					estado. Exige `turno:read`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La serie"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:read` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La serie o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SerieDeTurnosResponse> ver(
			@PathVariable long consultorioId,
			@PathVariable long serieId) {

		return ResponseEntity.ok(SerieDeTurnosResponse.de(
				service.ver(apiActor.current(), consultorioId, serieId)));
	}

	@GetMapping("/{serieId}/alcance")
	@Operation(
			operationId = "previsualizarAlcanceDeSerie",
			summary = "Previsualizar que turnos toca una operacion con alcance",
			description = """
					Calcula, **sin tocar nada**, los turnos que cancelaria o moveria una operacion \
					con ese alcance y los que dejaria como estan, con su motivo. Es el insumo de la \
					confirmacion explicita de DP-04: la pantalla muestra el resultado y el operador \
					confirma la **cantidad** de afectados, que el comando vuelve a verificar.

					- `ESTE`: solo el turno indicado.
					- `ESTE_Y_SIGUIENTES`: el indicado y los de la serie que empiezan despues, por su \
					horario actual.
					- `TODA_LA_SERIE`: todos (el turno es opcional).

					Exige `turno:read`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Afectados y omitidos"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el turno para ese alcance, o no es de la serie",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:read` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La serie o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AlcanceDeSerieResponse> previsualizar(
			@PathVariable long consultorioId,
			@PathVariable long serieId,
			@RequestParam @Parameter(description = "Alcance de la operacion", example = "ESTE_Y_SIGUIENTES")
			AlcanceDeSerie alcance,
			@RequestParam(required = false) @Parameter(description = "Turno pivote", example = "301")
			Long turnoId) {

		return ResponseEntity.ok(AlcanceDeSerieResponse.de(
				service.previsualizar(apiActor.current(), consultorioId, serieId, alcance, turnoId)));
	}

	@PostMapping("/{serieId}/cancelacion")
	@Operation(
			operationId = "cancelarSerieDeTurnos",
			summary = "Cancelar turnos de una serie con alcance",
			description = """
					Cancela los turnos **futuros pendientes** del alcance, cada uno con la misma \
					cancelacion de un turno suelto: motivo, actor, historial, aviso al paciente y \
					**liberacion del lugar**, sin borrar ninguna fila (RN-M12-002).

					Los pasados, los cancelados o ausentes, los que estan en espera y los que tienen \
					atencion **no se tocan**: vuelven en `omitidos` (DP-04).

					**Confirmacion explicita**: `cantidadConfirmada` tiene que coincidir con los \
					afectados que hay al ejecutar. Si alguien movio o cancelo un turno entre medio, \
					409 `conflict` y no se cancela nada. No es idempotente: repetirla da 409.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Turnos cancelados y omitidos"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta motivo, alcance, cantidad o el turno para ese alcance",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La serie o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La cantidad confirmada ya no es la real, o no queda ningun turno "
							+ "pendiente en el alcance",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AlcanceDeSerieResponse> cancelar(
			@PathVariable long consultorioId,
			@PathVariable long serieId,
			@RequestBody @Valid CancelarSerieDeTurnosRequest request) {

		return ResponseEntity.ok(AlcanceDeSerieResponse.de(service.cancelar(
				apiActor.current(), consultorioId, serieId,
				OperacionDeSerieCommand.cancelacion(
						request.alcance(), request.turnoId(), request.motivo(),
						request.cantidadConfirmada()))));
	}

	@PostMapping("/{serieId}/reprogramacion")
	@Operation(
			operationId = "reprogramarSerieDeTurnos",
			summary = "Mover turnos de una serie con alcance",
			description = """
					**Mueve** los turnos futuros pendientes del alcance: cada uno conserva id, \
					paciente e historial (RN-M12-003). El desplazamiento es el que lleva al turno \
					pivote de su horario actual a `inicio`, medido en hora local de la sede, y se \
					aplica igual a todos.

					Cada destino se revalida como una reserva y bajo el mismo lock de sede. **Todo o \
					nada**: si un destino no tiene lugar no se mueve ninguno, y el 409 conserva el \
					tipo de la causa con `ocurrenciaInicio`.

					La regla de la serie **no** se reescribe: describe como se genero. Misma \
					confirmacion explicita que la cancelacion.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Turnos movidos y omitidos"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta motivo, alcance, cantidad, pivote u horario nuevo, o el horario "
							+ "nuevo es el mismo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La serie, la sede o la oferta no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Un destino no tiene lugar (con `ocurrenciaInicio`), la cantidad "
							+ "confirmada ya no es la real, o no queda ningun turno pendiente",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AlcanceDeSerieResponse> reprogramar(
			@PathVariable long consultorioId,
			@PathVariable long serieId,
			@RequestBody @Valid ReprogramarSerieDeTurnosRequest request) {

		return ResponseEntity.ok(AlcanceDeSerieResponse.de(service.reprogramar(
				apiActor.current(), consultorioId, serieId,
				new OperacionDeSerieCommand(
						request.alcance(), request.turnoId(), request.motivo(),
						request.cantidadConfirmada(), request.inicio(), request.profesionalId()))));
	}
}
