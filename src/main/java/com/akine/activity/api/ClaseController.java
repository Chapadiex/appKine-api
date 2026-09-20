package com.akine.activity.api;

import com.akine.activity.api.dto.CancelarClaseRequest;
import com.akine.activity.api.dto.ClaseResponse;
import com.akine.activity.api.dto.EventoDeClaseResponse;
import com.akine.activity.api.dto.ProgramarClaseRequest;
import com.akine.activity.api.dto.ReprogramarClaseRequest;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.ProgramarClaseCommand;
import com.akine.activity.application.ReprogramarClaseCommand;
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
import java.time.Instant;
import java.util.List;

/**
 * Clases programadas (M28, AKINE-08.01).
 *
 * <p>La ruta cuelga de la SEDE y no de la oferta: una clase pertenece a la sede y su oferta es un
 * dato suyo. Colgarla de la oferta obligaria a conocerla para leer una clase que ya existe — es el
 * mismo criterio con el que M12 colgo los turnos de la sede y los slots de la oferta.
 *
 * <p><b>{@code produces} declara los dos tipos.</b> Declarar solo {@code application/json} hizo que
 * siete operaciones de otras etapas respondieran 406 al cliente generado, que manda
 * {@code application/problem+json} en el {@code Accept}: la negociacion corta ANTES de entrar al
 * metodo y ningun test lo agarraba.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/clases",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(
		name = "Clases",
		description = "Eventos grupales unicos de agenda. Una clase NO es N turnos (M28)")
public class ClaseController {

	private final ClaseService claseService;
	private final ActivityApiActor apiActor;

	public ClaseController(ClaseService claseService, ActivityApiActor apiActor) {
		this.claseService = claseService;
		this.apiActor = apiActor;
	}

	@PostMapping("/ofertas/{ofertaId}")
	@Operation(
			summary = "Programar una clase",
			description = """
					Crea un evento grupal unico sobre una oferta **GRUPAL**. No genera un turno por \
					participante: la clase existe una sola vez cualquiera sea el numero de \
					inscriptos (CA-M28-001-06).

					**El servidor revalida todo**: que la oferta sea grupal y siga vigente ese dia, \
					que el profesional siga habilitado y atendiendo en ese horario, que el cupo \
					pedido entre en la oferta y en el box, y que **ni el profesional ni el espacio \
					tengan un turno o una clase que se cruce**.

					Esa ultima revalidacion es el punto de la etapa. Una clase y un turno **se \
					disputan la misma fila de bloqueo de la sede**, asi que dos escrituras \
					concurrentes se serializan y una sola gana: no se puede reservar un turno \
					encima de una clase.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Clase programada"),
			@ApiResponse(
					responseCode = "200",
					description = "Reintento con la misma clave de idempotencia y el mismo pedido: "
							+ "se devuelve la clase ya creada, sin crear otra"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `clase:manage` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la oferta no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La oferta no es grupal o no esta vigente, la capacidad no es "
							+ "sostenible, el horario no existe, el recurso ya esta ocupado por un "
							+ "turno o por otra clase, o la clave de idempotencia se reuso con otro "
							+ "pedido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ClaseResponse> programar(
			@PathVariable long consultorioId,
			@PathVariable long ofertaId,
			@RequestBody @Valid ProgramarClaseRequest request) {

		var resultado = claseService.programar(
				apiActor.current(), consultorioId, ofertaId,
				new ProgramarClaseCommand(
						request.inicio(),
						request.fin(),
						request.profesionalId(),
						request.capacidad(),
						request.titulo(),
						request.idempotencyKey()));

		ClaseResponse cuerpo = ClaseResponse.de(resultado.clase());

		// 200 cuando la clave de idempotencia ya tenia clase: un 201 afirmaria que se creo algo que
		// no se creo, y el contrato promete 200 para ese caso.
		if (!resultado.creada()) {
			return ResponseEntity.ok(cuerpo);
		}
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/clases/"
						+ resultado.clase().id()))
				.body(cuerpo);
	}

	@PostMapping("/{claseId}/reprogramacion")
	@Operation(
			summary = "Reprogramar una clase",
			description = """
					Mueve horario, profesional, espacio o capacidad. **Conserva la clase y sus \
					participantes**: es la misma fila, con el mismo id, y las inscripciones cuelgan \
					de el (CA-M28-005-06).

					Revalida lo mismo que programar, excluyendose a si misma del control de \
					solapamiento — una clase que se corre media hora chocaria contra si misma.

					**No permite bajar la capacidad por debajo de los participantes confirmados** \
					(RF-M12-012).""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Clase reprogramada"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `clase:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la clase no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La clase ya esta cancelada o ya empezo, la capacidad no es "
							+ "sostenible, el recurso esta ocupado, o la version no coincide",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ClaseResponse> reprogramar(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@RequestBody @Valid ReprogramarClaseRequest request) {

		return ResponseEntity.ok(ClaseResponse.de(claseService.reprogramar(
				apiActor.current(), consultorioId, claseId,
				new ReprogramarClaseCommand(
						request.inicio(),
						request.fin(),
						request.profesionalId(),
						request.capacidad(),
						request.version()))));
	}

	@PostMapping("/{claseId}/cancelacion")
	@Operation(
			summary = "Cancelar una clase",
			description = """
					Cancela el evento con motivo obligatorio. **No borra nada**: la fila queda, con \
					su motivo, su actor y su historial (RN-M28-009).

					Libera el horario en el acto: desde la cancelacion, un turno puede tomar ese \
					box y ese profesional.

					**Es idempotente** (CA-M28-006-06). Una segunda ejecucion devuelve la misma \
					clase con el motivo original y no registra un segundo evento. Cuando AKINE-08.02 \
					y AKINE-08.07 cuelguen de aca la devolucion de creditos, esa idempotencia es lo \
					que impide devolver plata dos veces.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Clase cancelada, o ya estaba cancelada"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `clase:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la clase no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ClaseResponse> cancelar(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@RequestBody @Valid CancelarClaseRequest request) {

		return ResponseEntity.ok(ClaseResponse.de(claseService.cancelar(
				apiActor.current(), consultorioId, claseId, request.motivo())));
	}

	@GetMapping("/{claseId}")
	@Operation(
			summary = "Ver una clase",
			description = """
					Detalle de una clase. **No incluye la lista de participantes**: la vista de la \
					grilla no expone quien esta inscripto.

					Exige `clase:read` y no `clase:manage`: mirar la grilla no es programar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La clase"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `clase:read` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la clase no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ClaseResponse> ver(
			@PathVariable long consultorioId, @PathVariable long claseId) {

		return ResponseEntity.ok(ClaseResponse.de(
				claseService.ver(apiActor.current(), consultorioId, claseId)));
	}

	@GetMapping
	@Operation(
			summary = "Listar las clases de una sede en una ventana",
			description = """
					**Incluye las canceladas.** Alguien puede presentarse a una clase que se \
					cancelo, y esconderla deja al mostrador sin nada que decirle.

					Filtra por instante de inicio, no por solapamiento: la recepcion piensa en "lo \
					de hoy", que es lo que empieza hoy.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Las clases de la ventana"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `clase:read` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<ClaseResponse>> listar(
			@PathVariable long consultorioId,
			@Parameter(description = "Inicio de la ventana, UTC, inclusivo") @RequestParam Instant desde,
			@Parameter(description = "Fin de la ventana, UTC, exclusivo") @RequestParam Instant hasta) {

		return ResponseEntity.ok(claseService
				.listar(apiActor.current(), consultorioId, desde, hasta).stream()
				.map(ClaseResponse::de)
				.toList());
	}

	@GetMapping("/{claseId}/historial")
	@Operation(
			summary = "Historial de una clase",
			description = """
					Las transiciones registradas, de la mas vieja a la mas nueva (RN-M28-009). \
					**Append-only**: ninguna operacion del sistema las modifica ni las borra.

					Una reprogramacion guarda el intervalo y la capacidad anteriores, que es lo que \
					la hace trazable sin recrear la clase.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El historial"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `clase:read` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la clase no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<EventoDeClaseResponse>> historial(
			@PathVariable long consultorioId, @PathVariable long claseId) {

		return ResponseEntity.ok(claseService
				.historial(apiActor.current(), consultorioId, claseId).stream()
				.map(EventoDeClaseResponse::de)
				.toList());
	}
}
