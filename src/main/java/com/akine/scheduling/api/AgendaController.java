package com.akine.scheduling.api;

import com.akine.scheduling.api.dto.AgendaResponse;
import com.akine.scheduling.application.AgendaService;
import com.akine.scheduling.application.AgendaView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Consulta de turnos disponibles de una oferta (M12, RF-M12-001).
 *
 * <p>Es la unica superficie de esta etapa y es de <b>solo lectura</b>: reservar llega en 05.02.
 *
 * <p><b>La ruta cuelga de la oferta y no de la sede a secas.</b> Un slot solo existe en relacion a
 * una oferta —de ella salen la duracion, la capacidad y que profesionales y espacios pueden
 * prestarla— asi que una agenda "de la sede" no se podria calcular sin inventar cual de todas las
 * ofertas se esta pidiendo.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/ofertas/{ofertaId}/agenda",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(
		name = "Agenda",
		description = "Turnos disponibles calculados a partir de disponibilidad, oferta y "
				+ "habilitaciones (M12)")
public class AgendaController {

	private final AgendaService agendaService;
	private final SchedulingApiActor apiActor;

	public AgendaController(AgendaService agendaService, SchedulingApiActor apiActor) {
		this.agendaService = agendaService;
		this.apiActor = apiActor;
	}

	/**
	 * <p><b>{@code produces} declara los dos tipos.</b> Declarar solo {@code application/json}
	 * hizo que siete operaciones respondieran 406 al cliente generado, que manda
	 * {@code application/problem+json} en el {@code Accept} — la negociacion corta ANTES de entrar
	 * al metodo y ningun test lo agarraba, porque {@code HttpTestingController} del frontend no
	 * negocia contenido y {@code MockMvc} manda su propio {@code Accept}. Ver el fix {@code be14ba5}.
	 */
	@GetMapping
	@Operation(
			operationId = "buscarAgenda",
			summary = "Buscar turnos disponibles",
			description = """
					Devuelve los slots libres de una oferta entre dos fechas, dia por dia.

					**Los slots se calculan al leer y no se persisten.** Un slot devuelto no es una \
					reserva ni garantiza que se pueda reservar: entre esta lectura y la escritura \
					puede entrar otro. La exclusion real es de la operacion de reserva.

					**Ningun dia de la ventana se omite.** El dia sin turnos viaja con el motivo \
					que lo explica, porque un dia en blanco sin explicacion es indistinguible de \
					un error.

					El extremo `hasta` es **exclusivo**, y la ventana no puede superar los \
					62 dias.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Agenda calculada"),
			@ApiResponse(
					responseCode = "400",
					description = "Ventana invertida, vacia o mas ancha que el maximo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:read` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la oferta no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La oferta esta de baja, o su vigencia no toca la ventana pedida",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AgendaResponse> buscar(
			@PathVariable long consultorioId,
			@PathVariable long ofertaId,

			@Parameter(description = "Primera fecha incluida, local de la sede", example = "2026-09-15")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,

			@Parameter(
					description = "Fin de la ventana, **EXCLUSIVO**, local de la sede",
					example = "2026-09-22")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,

			@Parameter(
					description = "Para acotar a un solo profesional habilitado. Sin este parametro "
							+ "se devuelven los slots de todos.",
					example = "31")
			@RequestParam(required = false) Long profesionalId) {

		AgendaView vista = agendaService.buscar(
				apiActor.current(), consultorioId, ofertaId, desde, hasta, profesionalId);

		return ResponseEntity.ok(proyectar(vista));
	}

	private static AgendaResponse proyectar(AgendaView vista) {
		return new AgendaResponse(
				vista.ofertaId(),
				vista.consultorioId(),
				vista.nombreComercial(),
				vista.duracionMinutos(),
				vista.timezone(),
				vista.dias().stream()
						.map(dia -> new AgendaResponse.DiaResponse(
								dia.fecha(),
								dia.motivoSinSlots(),
								dia.slots().stream()
										.map(slot -> new AgendaResponse.SlotResponse(
												slot.desde(),
												slot.hasta(),
												slot.profesionalId(),
												slot.espacioId(),
												slot.cupoTotal(),
												slot.cupoLibre()))
										.toList()))
						.toList());
	}
}
