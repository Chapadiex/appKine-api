package com.akine.resource.api;

import com.akine.resource.api.dto.CalendarioSedeResponse;
import com.akine.resource.api.dto.UpdateCalendarioRequest;
import com.akine.resource.application.CalendarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Politica de calendario de una sede y feriados del periodo (M05, RF-M05-004).
 *
 * <h2>Un feriado nacional no cierra el centro por si solo</h2>
 *
 * <p>La tabla de feriados es GLOBAL y sin dueno (ADR-0022): es un hecho del calendario, no una
 * decision operativa. La decision de cada sede —cerrar o no ese dia— es lo que administra este
 * controller. Un centro de guardia atiende los feriados y el modelo tiene que poder decirlo.
 *
 * <p><b>Por eso no hay un endpoint global de feriados en esta version del contrato.</b> La unica
 * pregunta que hace una pantalla de esta etapa es "que feriados caen en la ventana que estoy
 * mirando, y esta sede cierra en ellos", y {@code GET .../calendario} la responde entera. Un
 * listado sin sede no tendria consumidor, y un endpoint sin llamador es superficie que hay que
 * mantener, versionar y asegurar para nada.
 *
 * <h2>La fila de politica se crea a demanda, y nunca se borra</h2>
 *
 * <p>Una sede que nunca fue editada no tiene fila: la lectura devuelve los valores por defecto
 * sin escribir nada, y lo dice en {@code existePersistida}. La fila aparece en la primera edicion
 * —o en el primer write de disponibilidad de la sede, que la crea para poder bloquearla—.
 *
 * <p>Esa misma fila es la que serializa los writes de disponibilidad de la sede. Por eso este
 * controller <b>no ofrece ninguna baja</b>: borrarla no dejaria a la sede "sin politica", dejaria
 * a sus writes sin punto de serializacion, y el sintoma aparecerian meses despues como dos
 * bloques solapados que nadie sabe como entraron.
 */
@RestController
@RequestMapping("/api/v1/consultorios/{consultorioId}/calendario")
@Tag(name = "Calendario de sede",
		description = "Politica de feriados de una sede y feriados del periodo consultado")
public class CalendarioController {

	private final CalendarioService calendarioService;
	private final ApiActor apiActor;

	public CalendarioController(CalendarioService calendarioService, ApiActor apiActor) {
		this.calendarioService = calendarioService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "getCalendarioSede",
			summary = "Politica de calendario de la sede y feriados de la ventana",
			description = """
					Devuelve juntas las dos cosas que la pantalla de calendario necesita a la \
					vez: si esta sede cierra los feriados de su pais, y cuales son los feriados \
					que caen en la ventana que se esta mirando.

					existePersistida = false significa que la sede todavia no tiene fila propia y \
					lo que se devuelve son los valores por defecto. Es informacion util: dice que \
					nadie edito nunca la politica de esa sede, no que no tenga una. LA LECTURA NO \
					CREA LA FILA: un GET que escribe convierte una consulta en una mutacion que \
					nadie pidio.

					La ventana es semiabierta: hasta es EXCLUSIVO, y un feriado que cae \
					exactamente en hasta NO viaja. hasta == desde es 400, no "un dia". El maximo \
					es de 366 dias; el error trae maximoDias para que la pantalla recorte sola.

					Exige colaborador:read sobre esa sede.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Politica de la sede y feriados de la ventana",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CalendarioSedeResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Ventana ausente, invertida o vacia (validation-error), o de "
							+ "mas de 366 dias (ventana-demasiado-amplia, con maximoDias)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada, no hay contexto de trabajo, o falta "
							+ "colaborador:read sobre esa sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o pertenece a otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CalendarioSedeResponse> get(
			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Parameter(
					description = "Primer dia de la ventana, fecha local ISO-8601",
					required = true,
					example = "2026-01-01")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,

			@Parameter(
					description = "Fin de la ventana, EXCLUSIVO, fecha local ISO-8601",
					required = true,
					example = "2027-01-01")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {

		return ResponseEntity.ok(CalendarioSedeResponse.from(
				calendarioService.ver(apiActor.current(), consultorioId, desde, hasta)));
	}

	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateCalendarioSede",
			summary = "Edicion de la politica de calendario de la sede",
			description = """
					Cambia el pais del calendario y si la sede cierra sus feriados. Crea la fila \
					a demanda si es la primera vez. Exige consultorio:manage sobre ESA sede.

					Semantica de PATCH aunque el verbo sea PUT: cada campo omitido queda como \
					estaba. cierraPorFeriado OMITIDO no es false — ese es el error que convierte \
					"no toques la politica" en "abri todos los feriados" para todos los \
					profesionales de la sede, hacia adelante y hacia atras. Por el alcance de ese \
					cambio, la edicion se audita aunque sea un solo flag.

					NO LLEVA version Y NO PUEDE DAR 409 POR CONCURRENCIA. La fila se crea a \
					demanda: un cliente que nunca la vio no tiene version que mandar, y \
					exigirsela le impediria su primera edicion. Consecuencia declarada: dos \
					administradores que editan a la vez no producen conflicto, gana el segundo, y \
					la auditoria conserva los dos cambios con su autor. La version viaja igual en \
					la respuesta, para que la pantalla pueda detectar que alguien mas la cambio.

					feriados viene VACIA en esta respuesta: un PUT no tiene ventana. Vacia \
					significa "no se pregunto", nunca "no hay feriados"; quien quiera la lista \
					vuelve a leer con su ventana.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Politica actualizada. feriados viene vacia: el PUT no tiene "
							+ "ventana",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CalendarioSedeResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "El pais viene vacio o no es un codigo de dos letras",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Falta consultorio:manage sobre esa sede, o no hay contexto",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o pertenece a otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CalendarioSedeResponse> update(
			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Valid @RequestBody UpdateCalendarioRequest request) {

		return ResponseEntity.ok(CalendarioSedeResponse.from(calendarioService.actualizar(
				apiActor.current(), consultorioId, request.pais(), request.cierraPorFeriado())));
	}
}
