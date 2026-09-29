package com.akine.activity.api;

import com.akine.activity.api.dto.CancelarInscripcionRequest;
import com.akine.activity.api.dto.CuposResponse;
import com.akine.activity.api.dto.InscribirRequest;
import com.akine.activity.api.dto.InscripcionResponse;
import com.akine.activity.api.dto.ParticipanteResponse;
import com.akine.activity.api.dto.ResultadoDeCancelacionResponse;
import com.akine.activity.api.dto.ResultadoDeInscripcionResponse;
import com.akine.activity.application.InscribirCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ResultadoDeInscripcion;
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
 * Inscripciones, cupos y lista de espera (M28, AKINE-08.02).
 *
 * <p>La ruta cuelga de la CLASE porque una inscripcion no existe sin ella: su ciclo de vida lo
 * manda la clase —si la clase se cancela, se cancelan todas— y su invariante fuerte es contra el
 * cupo de esa clase.
 *
 * <p><b>{@code produces} declara los dos tipos.</b> Declarar solo {@code application/json} hizo que
 * siete operaciones de otras etapas respondieran 406 al cliente generado, que manda
 * {@code application/problem+json} en el {@code Accept}: la negociacion corta ANTES de entrar al
 * metodo y ningun test lo agarraba.
 *
 * <p><b>Staff unicamente, sin autoservicio.</b> M28 §2 nombra a la persona como actor "cuando
 * exista autoservicio", y no existe: no hay vinculo entre {@code cuenta} y {@code persona} en
 * ningun lado del sistema. Sin ese vinculo, "inscribirme a mi mismo" no se puede autorizar sin
 * abrir "inscribir a cualquiera".
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/clases/{claseId}",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(
		name = "Inscripciones",
		description = "Cupos de una clase por Persona, lista de espera y promocion (M28)")
public class InscripcionController {

	private final InscripcionService inscripcionService;
	private final ActivityApiActor apiActor;

	public InscripcionController(
			InscripcionService inscripcionService, ActivityApiActor apiActor) {

		this.inscripcionService = inscripcionService;
		this.apiActor = apiActor;
	}

	@PostMapping("/inscripciones")
	@Operation(
			summary = "Inscribir una persona en una clase",
			description = """
					Reserva un cupo. **El lugar lo otorga la base, no el servicio**: una sentencia \
					condicional que no puede pasar del tope. Dos solicitudes concurrentes sobre el \
					ultimo lugar dejan exactamente una confirmada (CA-M28-002-06).

					Si no queda lugar y `aceptaListaEspera` es `true`, la persona entra en la cola \
					con una posicion que **no se recicla**; si es `false`, la respuesta es \
					`clase-completa` con la capacidad efectiva y los ocupados, para que la \
					pantalla pueda ofrecer la espera sin otra vuelta.

					**La persona no se convierte en paciente** (RF-M07-010) y **no se devenga \
					nada**: reservar un lugar no prueba que nadie haya entrenado.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Inscripta, con lugar o en espera"),
			@ApiResponse(
					responseCode = "200",
					description = "Reintento con la misma clave de idempotencia y el mismo pedido: "
							+ "se devuelve la inscripcion ya creada, sin crear otra"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `inscripcion:manage` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la clase o la persona no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La clase esta cancelada o ya empezo, la persona ya esta "
							+ "anotada, no hay lugar y no se acepto la espera, o la clave de "
							+ "idempotencia se reuso con otro pedido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ResultadoDeInscripcionResponse> inscribir(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@RequestBody @Valid InscribirRequest request) {

		ResultadoDeInscripcion resultado = inscripcionService.inscribir(
				apiActor.current(), consultorioId, claseId,
				new InscribirCommand(
						request.personaId(),
						request.aceptaListaEspera(),
						request.idempotencyKey()));

		ResultadoDeInscripcionResponse cuerpo = ResultadoDeInscripcionResponse.de(resultado);

		// 200 cuando la clave ya tenia inscripcion: un 201 afirmaria que se creo algo que no se
		// creo, y el contrato promete 200 para ese caso.
		if (!resultado.creada()) {
			return ResponseEntity.ok(cuerpo);
		}
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/clases/" + claseId
						+ "/inscripciones/" + resultado.inscripcion().id()))
				.body(cuerpo);
	}

	@PostMapping("/inscripciones/{inscripcionId}/confirmacion")
	@Operation(
			operationId = "confirmarInscripcion",
			summary = "Confirmar una inscripcion",
			description = """
					`RESERVADA` -> `CONFIRMADA`. **No toca el cupo**: los dos estados lo consumen, \
					asi que la transicion no otorga ni libera nada.

					Confirmar **no** afirma que la persona haya venido. Eso es `ASISTIO`, y lo \
					registra AKINE-08.03.

					Es idempotente: confirmar una confirmada devuelve la misma fila.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Confirmada, o ya lo estaba"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `inscripcion:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la clase o la inscripcion no existen",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La inscripcion esta en la lista de espera o cancelada",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<InscripcionResponse> confirmar(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@PathVariable long inscripcionId) {

		return ResponseEntity.ok(InscripcionResponse.de(inscripcionService.confirmar(
				apiActor.current(), consultorioId, claseId, inscripcionId)));
	}

	@PostMapping("/inscripciones/{inscripcionId}/cancelacion")
	@Operation(
			operationId = "cancelarInscripcion",
			summary = "Cancelar una inscripcion",
			description = """
					Libera el cupo y **conserva el historial individual** (RF-M28-003): la fila \
					queda, con su motivo, su actor y su instante. No se borra nada.

					Si la inscripcion tenia lugar, **la cabeza de la lista de espera entra en su \
					lugar automaticamente** y recibe un aviso. La respuesta dice a quien, para que \
					el mostrador pueda avisarle en el momento.

					**Cancelar una inscripcion no cancela la clase ni a los demas** \
					(CA-M28-003-06).

					**Es idempotente**: una segunda ejecucion no libera un segundo lugar ni \
					promueve a nadie. Cuando AKINE-08.07 cuelgue de aca la devolucion de creditos, \
					eso es lo que impide devolver dos veces.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Cancelada, o ya lo estaba"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `inscripcion:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la clase o la inscripcion no existen",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La inscripcion ya tiene asistencia registrada",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ResultadoDeCancelacionResponse> cancelar(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@PathVariable long inscripcionId,
			@RequestBody @Valid CancelarInscripcionRequest request) {

		return ResponseEntity.ok(ResultadoDeCancelacionResponse.de(inscripcionService.cancelar(
				apiActor.current(), consultorioId, claseId, inscripcionId, request.motivo())));
	}

	@GetMapping("/inscripciones")
	@Operation(
			summary = "Listar los participantes de una clase",
			description = """
					**Exige `inscripcion:read`, que no es `clase:read`.** Quien mira la grilla del \
					dia no necesita saber quien esta anotado: la clase y sus cupos no nombran a \
					nadie, y esta es la unica respuesta de M28 que lleva datos de persona.

					Incluye a los cancelados y a los que esperan, en orden de cola. Nombre y \
					documento, nada mas.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Los participantes"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `inscripcion:read` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la clase no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<ParticipanteResponse>> participantes(
			@PathVariable long consultorioId, @PathVariable long claseId) {

		return ResponseEntity.ok(inscripcionService
				.participantes(apiActor.current(), consultorioId, claseId).stream()
				.map(ParticipanteResponse::de)
				.toList());
	}

	@GetMapping("/cupos")
	@Operation(
			summary = "Consultar los cupos de una clase",
			description = """
					RF-M12-010. Capacidad propia, capacidad efectiva, ocupados, disponibles y \
					cuantos esperan.

					**No devuelve ningun dato de persona**, y por eso alcanza con `clase:read`.

					`ocupados` sale de la columna que **otorga** el lugar, no de un conteo sobre \
					las inscripciones: es el mismo numero contra el que la base rechaza la \
					sobreventa.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Los cupos"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `clase:read` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la clase no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CuposResponse> cupos(
			@PathVariable long consultorioId, @PathVariable long claseId) {

		return ResponseEntity.ok(CuposResponse.de(
				inscripcionService.cupos(apiActor.current(), consultorioId, claseId)));
	}
}
