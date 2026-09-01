package com.akine.scheduling.api;

import com.akine.scheduling.api.dto.ReservarTurnoRequest;
import com.akine.scheduling.api.dto.TurnoResponse;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Reserva y confirmacion de turnos (M12).
 *
 * <p>La ruta cuelga de la SEDE y no de la oferta, a diferencia de la agenda: un turno pertenece a
 * la sede y su oferta es un dato suyo, mientras que un slot solo existe en relacion a una oferta.
 * Colgar el turno de la oferta obligaria a conocerla para leer un turno que ya existe.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/turnos",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Turnos", description = "Reserva de slots con revalidacion atomica e idempotencia (M12)")
public class TurnoController {

	private final TurnoService turnoService;
	private final SchedulingApiActor apiActor;

	public TurnoController(TurnoService turnoService, SchedulingApiActor apiActor) {
		this.turnoService = turnoService;
		this.apiActor = apiActor;
	}

	@PostMapping("/ofertas/{ofertaId}")
	@Operation(
			summary = "Reservar un turno",
			description = """
					Toma un slot de una oferta. **El servidor revalida todo**: que la oferta siga \
					vigente ese dia, que el profesional siga habilitado y atendiendo en ese \
					horario, que quede cupo y que ni el profesional ni el box tengan otro turno \
					que se cruce.

					**Una sola reserva gana.** Dos peticiones concurrentes por el mismo hueco se \
					serializan; la segunda recibe un 409 con el tipo que corresponde a su caso.

					Los cuatro conflictos son tipos distintos a proposito, porque llevan a la \
					pantalla a acciones distintas: `slot-no-disponible` (recargar la agenda), \
                    `slot-completo` (ofrecer el siguiente), `recurso-ocupado` (elegir otro \
					horario o profesional) y `persona-sin-perfil-paciente` (activar el perfil).

					**No hay cancelacion todavia**: llega en AKINE-05.03.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Turno reservado"),
			@ApiResponse(
					responseCode = "200",
					description = "Reintento con la misma clave de idempotencia y el mismo pedido: "
							+ "se devuelve el turno ya creado, sin crear otro"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la oferta o la persona no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El hueco dejo de existir, no queda cupo, el recurso esta ocupado, "
							+ "la persona no es paciente, o la clave de idempotencia se reuso con "
							+ "otro pedido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<TurnoResponse> reservar(
			@PathVariable long consultorioId,
			@PathVariable long ofertaId,
			@RequestBody @Valid ReservarTurnoRequest request) {

		var resultado = turnoService.reservar(
				apiActor.current(), consultorioId, ofertaId,
				new ReservaCommand(
						request.personaId(),
						request.inicio(),
						request.profesionalId(),
						request.idempotencyKey()));

		TurnoResponse cuerpo = TurnoResponse.de(resultado.turno());

		// 200 cuando la clave de idempotencia ya tenia turno: un 201 afirmaria que se creo algo
		// que no se creo, y el contrato promete 200 para ese caso.
		if (!resultado.creado()) {
			return ResponseEntity.ok(cuerpo);
		}
		return ResponseEntity
				.created(URI.create(
						"/api/v1/consultorios/" + consultorioId + "/turnos/" + resultado.turno().id()))
				.body(cuerpo);
	}

	/**
	 * <p><b>Idempotente</b>: confirmar un turno ya confirmado devuelve 200 y no mueve la fecha de
	 * confirmacion. El doble click es el caso normal y castigarlo con un 409 obligaria a la pantalla
	 * a distinguir dos situaciones que para el usuario son la misma.
	 */
	@PostMapping("/{turnoId}/confirmacion")
	@Operation(
			summary = "Confirmar un turno reservado",
			description = """
					Marca la reserva como confirmada. **Es idempotente**: confirmar dos veces \
					devuelve 200 sin cambiar nada.

					Confirmar es un estado de la RESERVA y no del cobro ni de la llegada del \
					paciente: DP-06 deja el prepago como politica configurable y nunca como \
					condicion del dominio clinico.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Turno confirmado"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<TurnoResponse> confirmar(
			@PathVariable long consultorioId,
			@PathVariable long turnoId) {

		return ResponseEntity.ok(TurnoResponse.de(
				turnoService.confirmar(apiActor.current(), consultorioId, turnoId)));
	}
}
