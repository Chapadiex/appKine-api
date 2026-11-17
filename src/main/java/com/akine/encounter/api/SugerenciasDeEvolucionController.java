package com.akine.encounter.api;

import com.akine.encounter.api.dto.SugerenciaDeEvolucionGeneracionResponse;
import com.akine.encounter.api.dto.SugerenciaDeEvolucionPageResponse;
import com.akine.encounter.api.dto.SugerenciaDeEvolucionRespuestaRequest;
import com.akine.encounter.api.dto.SugerenciaDeEvolucionResponse;
import com.akine.encounter.application.SugerenciasDeEvolucionService;
import com.akine.encounter.domain.SugerenciaEvolucion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sugerencias de revision del tratamiento con respaldo de la biblioteca (M31 fase 2). */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/casos/{casoId}/evolucion/sugerencias",
		produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@Tag(name = "Sugerencias de evolucion",
		description = "Sugerencias para revisar el tratamiento de un caso, respaldadas por la biblioteca")
class SugerenciasDeEvolucionController {

	private static final String JUSTIFICACION = "X-Justificacion-Acceso";
	private static final String JUSTIFICACION_DESCRIPCION = "Motivo declarado del acceso (DP-03). Obligatorio "
			+ "sin relacion asistencial con el paciente. Viaja en cabecera para no quedar en los logs de un "
			+ "proxy, y queda auditado";
	private static final int TAMANO_POR_DEFECTO = 20;
	private static final int TAMANO_MAXIMO = 100;

	private final SugerenciasDeEvolucionService servicio;
	private final EncounterApiActor apiActor;

	SugerenciasDeEvolucionController(SugerenciasDeEvolucionService servicio, EncounterApiActor apiActor) {
		this.servicio = servicio;
		this.apiActor = apiActor;
	}

	@PostMapping
	@Operation(
			operationId = "generarSugerenciasDeEvolucion",
			summary = "Pedir sugerencias de revision del tratamiento de un caso",
			description = """
					Elige hasta 3 hallazgos de la evolucion del caso (los que tiene sentido respaldar \
					con bibliografia), busca en la biblioteca del centro una vez por hallazgo y le pide \
					al modelo local hasta 5 sugerencias que citen esos pasajes. Un validador sin modelo \
					descarta las que citan un pasaje de otro hallazgo, inventan numeros, prescriben o \
					exceden el largo; las demas se guardan PENDIENTE y se devuelven.

					Sin hallazgos elegibles responde SIN_HALLAZGOS_ELEGIBLES y sin material en la \
					biblioteca SIN_MATERIAL: en los dos casos no llama al modelo. Es una sugerencia para \
					revisar, no una indicacion.

					EXIGE evolucion:sugerir, hc:read Y RELACION ASISTENCIAL O JUSTIFICACION. Se audita \
					(SUGERENCIAS_DE_EVOLUCION_GENERADAS), sin texto clinico. Un pedido a la vez por \
					cuenta (429 generacion-en-curso). Si el modelo no responde en el plazo, 503 \
					modelo-no-disponible; con el cupo lleno, 503 modelo-ocupado.

					Con la funcion apagada en el despliegue (o la evolucion o la biblioteca apagadas) \
					responde 404.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Resultado del pedido",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = SugerenciaDeEvolucionGeneracionResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin evolucion:sugerir o hc:read, o "
					+ "sin relacion ni justificacion (justificacion-de-acceso-requerida)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede o el caso no existen o son de otro tenant, "
					+ "o la funcion esta apagada",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "429", description = "La cuenta ya tiene un pedido en curso "
					+ "(generacion-en-curso)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "503", description = "El modelo no respondio (modelo-no-disponible) o "
					+ "su cupo esta lleno (modelo-ocupado)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SugerenciaDeEvolucionGeneracionResponse> generar(
			@Parameter(description = "Sede del contexto", example = "7") @PathVariable long consultorioId,
			@Parameter(description = "Caso clinico", example = "41") @PathVariable long casoId,
			@Parameter(description = JUSTIFICACION_DESCRIPCION)
			@RequestHeader(name = JUSTIFICACION, required = false) String justificacion) {

		return ResponseEntity.ok(SugerenciaDeEvolucionGeneracionResponse.de(
				servicio.generar(apiActor.current(), consultorioId, casoId, justificacion)));
	}

	@GetMapping
	@Operation(
			operationId = "listarSugerenciasDeEvolucion",
			summary = "Historial de sugerencias de evolucion de un caso",
			description = """
					Las sugerencias guardadas del caso, de la mas nueva a la mas vieja, con su respuesta. \
					Es lectura clinica: exige evolucion:sugerir, hc:read y relacion o justificacion, y se \
					audita (SUGERENCIAS_DE_EVOLUCION_CONSULTADAS). Con la funcion apagada responde 404.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pagina del historial",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = SugerenciaDeEvolucionPageResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, permiso o justificacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede o el caso no existen, o la funcion esta "
					+ "apagada",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SugerenciaDeEvolucionPageResponse> listar(
			@Parameter(description = "Sede del contexto", example = "7") @PathVariable long consultorioId,
			@Parameter(description = "Caso clinico", example = "41") @PathVariable long casoId,
			@Parameter(description = "Pagina, base cero") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "Elementos por pagina, hasta 100") @RequestParam(defaultValue = "20") int size,
			@Parameter(description = JUSTIFICACION_DESCRIPCION)
			@RequestHeader(name = JUSTIFICACION, required = false) String justificacion) {

		PageRequest pagina = PageRequest.of(Math.max(page, 0),
				size < 1 ? TAMANO_POR_DEFECTO : Math.min(size, TAMANO_MAXIMO));
		return ResponseEntity.ok(SugerenciaDeEvolucionPageResponse.de(
				servicio.listar(apiActor.current(), consultorioId, casoId, justificacion, pagina)));
	}

	@PostMapping(path = "/{sugerenciaId}/respuesta", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "responderSugerenciaDeEvolucion",
			summary = "Aceptar o descartar una sugerencia de evolucion",
			description = """
					Solo desde PENDIENTE y una sola vez: si ya tiene respuesta, 409 \
					sugerencia-ya-respondida y la primera no se pisa. Descartar exige motivo (400). \
					Hay que mandar la version leida; si no coincide, 409 concurrent-modification. \
					Registra quien respondio, que puede no ser quien la pidio. Exige el mismo acceso \
					que pedirlas y se audita (SUGERENCIA_DE_EVOLUCION_RESPONDIDA), sin el motivo. Con la \
					funcion apagada responde 404.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La sugerencia con su respuesta",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = SugerenciaDeEvolucionResponse.class))),
			@ApiResponse(responseCode = "400", description = "Respuesta invalida, o descarte sin motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, permiso o justificacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede, el caso o la sugerencia no existen, son "
					+ "de otro tenant u otro caso, o la funcion esta apagada",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Ya respondida (sugerencia-ya-respondida) o version "
					+ "vieja (concurrent-modification)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SugerenciaDeEvolucionResponse> responder(
			@Parameter(description = "Sede del contexto", example = "7") @PathVariable long consultorioId,
			@Parameter(description = "Caso clinico", example = "41") @PathVariable long casoId,
			@Parameter(description = "Sugerencia", example = "301") @PathVariable long sugerenciaId,
			@Parameter(description = JUSTIFICACION_DESCRIPCION)
			@RequestHeader(name = JUSTIFICACION, required = false) String justificacion,
			@RequestBody @Valid SugerenciaDeEvolucionRespuestaRequest request) {

		return ResponseEntity.ok(SugerenciaDeEvolucionResponse.de(servicio.responder(apiActor.current(),
				consultorioId, casoId, sugerenciaId, SugerenciaEvolucion.Respuesta.valueOf(request.respuesta()),
				request.motivo(), request.version(), justificacion)));
	}
}
