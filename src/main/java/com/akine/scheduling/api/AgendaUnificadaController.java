package com.akine.scheduling.api;

import com.akine.scheduling.api.dto.AgendaUnificadaResponse;
import com.akine.scheduling.application.AgendaUnificadaService;
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
 * La agenda unificada de una sede (M12, RF-M12-011 y RF-M12-013).
 *
 * <p>Cuelga de la sede y no de una oferta, a diferencia del buscador de slots: la grilla del dia es
 * de la sede entera, y pedirle una oferta obligaria a la pantalla a hacer una llamada por oferta
 * para dibujar un solo calendario.
 *
 * <p><b>Es un endpoint nuevo, no un reemplazo.</b> {@code GET /turnos?fecha=} sigue devolviendo
 * exactamente lo que devolvia: cambiar su respuesta para meterle clases habria sido incompatible.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/agenda",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(
		name = "Agenda unificada",
		description = "Turnos y clases en la misma grilla, sin fusionar su semantica (M12)")
public class AgendaUnificadaController {

	private final AgendaUnificadaService agendaUnificada;
	private final SchedulingApiActor apiActor;

	public AgendaUnificadaController(
			AgendaUnificadaService agendaUnificada, SchedulingApiActor apiActor) {

		this.agendaUnificada = agendaUnificada;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			summary = "Ver la agenda del dia con turnos y clases",
			description = """
					Devuelve todo lo que empieza ese dia local en la sede, ordenado por hora.

					**La lista es una union discriminada por `tipo`** (`TURNO` o `CLASE`). Turno y \
					ClaseProgramada siguen siendo entidades separadas con reglas y comandos propios: \
                    esto es una proyeccion de lectura y nada mas. No hay tabla comun, no hay \
					herencia y no se migro un solo turno (RF-M12-013).

					**El id no es unico entre tipos.** Un turno 7 y una clase 7 existen a la vez: la \
					clave de una fila es el par `(tipo, eventoId)`.

					**Las clases no exponen participantes.** De un turno viaja la persona, que es lo \
					que la recepcion necesita para llamar a alguien; de una clase, ninguna.

					La fecha se interpreta en la zona de la **sede**, que viaja en la respuesta: \
					resolverla con la del navegador correria la grilla entera sin fallar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La grilla del dia"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:read` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AgendaUnificadaResponse> delDia(
			@PathVariable long consultorioId,
			@Parameter(description = "Dia local de la sede", example = "2026-10-05")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		return ResponseEntity.ok(AgendaUnificadaResponse.de(
				agendaUnificada.delDia(apiActor.current(), consultorioId, fecha)));
	}
}
