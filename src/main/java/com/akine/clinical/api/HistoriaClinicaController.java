package com.akine.clinical.api;

import com.akine.clinical.api.dto.ActualizarResumenRequest;
import com.akine.clinical.api.dto.AntecedenteResponse;
import com.akine.clinical.api.dto.BajaDeAntecedenteRequest;
import com.akine.clinical.api.dto.HistoriaClinicaResponse;
import com.akine.clinical.api.dto.RegistrarAntecedenteRequest;
import com.akine.clinical.application.AntecedenteClinicoService;
import com.akine.clinical.application.HistoriaClinicaService;
import com.akine.clinical.domain.TipoAntecedente;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * La puerta REST de la Historia Clinica: abrirla u obtenerla, su resumen y sus antecedentes
 * (M09, RF-M09-001 y RF-M09-002).
 *
 * <h2>Se direcciona por persona, no por id de historia</h2>
 *
 * <p>Quien llama sabe de que paciente habla, no cual es el id de su historia: abrirla es
 * justamente la operacion que la crea. Y {@code HistoriaClinicaService} y
 * {@code AntecedenteClinicoService} ya trabajan por {@code personaId}. Por eso todo cuelga de
 * {@code /historias-clinicas/por-persona/&#123;personaId&#125;}, que no choca con las rutas por
 * id ({@code /historias-clinicas/&#123;historiaClinicaId&#125;/...}) de adjuntos, casos, entradas
 * y timeline: el segmento literal {@code por-persona} gana sobre la variable.
 *
 * <h2>Abrir es idempotente</h2>
 *
 * <p>{@code PUT} y no {@code POST}: repetir el pedido —un boton tocado dos veces, un reintento
 * tras un timeout, dos pedidos simultaneos— devuelve la misma historia y deja una sola fila. La
 * garantia la da el unique de la base, no esta capa.
 *
 * <h2>Toda operacion es acceso clinico</h2>
 *
 * <p>Cada una exige {@code hc:read} o {@code hc:write} mas relacion asistencial o motivo
 * declarado (DP-03) y deja su evento de auditoria, lecturas incluidas. Los errores los mapea
 * {@code ClinicalProblemHandler}.
 */
@RestController
@RequestMapping(path = "/api/v1/historias-clinicas/por-persona/{personaId}")
@Tag(name = "Historia clinica",
		description = "Apertura, resumen y antecedentes de la Historia Clinica de una persona (M09)")
public class HistoriaClinicaController {

	private final HistoriaClinicaService historiaService;
	private final AntecedenteClinicoService antecedenteService;
	private final ClinicalApiActor apiActor;

	public HistoriaClinicaController(
			HistoriaClinicaService historiaService,
			AntecedenteClinicoService antecedenteService,
			ClinicalApiActor apiActor) {

		this.historiaService = historiaService;
		this.antecedenteService = antecedenteService;
		this.apiActor = apiActor;
	}

	// =================================================================================
	// La historia
	// =================================================================================

	@PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "abrirOObtenerHistoriaClinica",
			summary = "Abrir u obtener la Historia Clinica de una persona",
			description = """
					RF-M09-001. Abre la historia de esa persona o devuelve la que ya tiene. Es \
					IDEMPOTENTE: repetir el pedido, incluso en simultaneo, devuelve la misma \
					historia y deja una sola fila. Un boton tocado dos veces o un reintento tras \
					un timeout son el mismo pedido, no un conflicto.

					La persona tiene que ser paciente vigente de la organizacion: si no existe en \
					el tenant responde 404 (indistinguible de "no existe"); si existe pero no \
					tiene perfil de paciente vigente, 409.

					Exige hc:write mas relacion asistencial o motivo declarado. Solo la apertura \
					efectiva deja el evento HISTORIA_CLINICA_OPENED.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La historia, recien abierta o ya existente",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = HistoriaClinicaResponse.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto, sin hc:write, o sin relacion asistencial ni "
							+ "motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La persona no existe o es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "La persona no tiene perfil de paciente vigente",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public HistoriaClinicaResponse abrirOObtener(

			@Parameter(description = "Persona titular de la historia", example = "12")
			@PathVariable long personaId,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return HistoriaClinicaResponse.from(
				historiaService.abrirOObtener(apiActor.current(), personaId, justificacion));
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "verHistoriaClinica",
			summary = "Ver la Historia Clinica de una persona",
			description = """
					RF-M09-001. Devuelve la historia con su resumen, sus antecedentes vigentes y \
					un asomo del timeline (sin contenido clinico). No la abre: si la persona no \
					tiene historia responde 404.

					CADA LECTURA SE AUDITA (DP-03) como HISTORIA_CLINICA_ACCESSED, con la via por \
					la que se accedio. Exige hc:read mas relacion asistencial o motivo declarado.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La historia",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = HistoriaClinicaResponse.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto, sin hc:read, o sin relacion asistencial ni "
							+ "motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La persona no tiene historia, o es de otra organizacion. Los "
							+ "dos casos son indistinguibles a proposito",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public HistoriaClinicaResponse ver(

			@Parameter(description = "Persona titular de la historia", example = "12")
			@PathVariable long personaId,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return HistoriaClinicaResponse.from(
				historiaService.ver(apiActor.current(), personaId, justificacion));
	}

	@PutMapping(path = "/resumen",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "actualizarResumenHistoriaClinica",
			summary = "Actualizar el resumen clinico de la historia",
			description = """
					RF-M09-001. Reemplaza el resumen clinico minimo.

					expectedVersion ES LA VERSION DE LA HISTORIA que el operador leyo. Si alguien \
					la modifico en el medio responde 409 en vez de pisar en silencio lo que \
					escribio el otro: se vuelve a leer la historia y se reintenta.

					El texto del resumen NO queda en la auditoria (es contenido clinico): solo que \
					hubo escritura, quien y cuando. Exige hc:write mas relacion asistencial o \
					motivo declarado.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La historia con el resumen nuevo",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = HistoriaClinicaResponse.class))),
			@ApiResponse(responseCode = "400", description = "Cuerpo invalido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto, sin hc:write, o sin relacion asistencial ni "
							+ "motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La persona no tiene historia, o es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "La historia cambio desde que se leyo (expectedVersion desfasada)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public HistoriaClinicaResponse actualizarResumen(

			@Parameter(description = "Persona titular de la historia", example = "12")
			@PathVariable long personaId,

			@Valid @RequestBody ActualizarResumenRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return HistoriaClinicaResponse.from(historiaService.actualizarResumen(
				apiActor.current(),
				personaId,
				request.texto(),
				request.expectedVersion(),
				justificacion));
	}

	// =================================================================================
	// Antecedentes
	// =================================================================================

	@GetMapping(path = "/antecedentes", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "listarAntecedentesClinicos",
			summary = "Listar los antecedentes de la historia de una persona",
			description = """
					RF-M09-002. Por defecto trae solo los VIGENTES; soloVigentes=false suma los \
					dados de baja, que siguen siendo consultables con su motivo (regla maestra \
					10). tipo filtra por clase de antecedente.

					Es una lectura clinica y se audita como tal. Exige hc:read mas relacion \
					asistencial o motivo declarado.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Los antecedentes de la historia",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = AntecedenteResponse.class)))),
			@ApiResponse(responseCode = "400", description = "tipo desconocido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto, sin hc:read, o sin relacion asistencial ni "
							+ "motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La persona no tiene historia, o es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public List<AntecedenteResponse> listarAntecedentes(

			@Parameter(description = "Persona titular de la historia", example = "12")
			@PathVariable long personaId,

			@Parameter(description = "Filtro opcional por clase de antecedente")
			@RequestParam(required = false) TipoAntecedente tipo,

			@Parameter(description = "Si es true (por defecto) trae solo los vigentes")
			@RequestParam(defaultValue = "true") boolean soloVigentes,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return antecedenteService
				.listar(apiActor.current(), personaId, tipo, soloVigentes, justificacion)
				.stream()
				.map(AntecedenteResponse::from)
				.toList();
	}

	@PostMapping(path = "/antecedentes",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "registrarAntecedenteClinico",
			summary = "Registrar un antecedente clinico",
			description = """
					RF-M09-002. Registra un antecedente en la historia vigente de la persona. No \
					hay operacion de edicion y es el diseño (RN-M09-004): corregir un antecedente \
					es darlo de baja con motivo y registrar el nuevo, y asi quedan las dos \
					versiones con sus autores y sus fechas.

					La descripcion NO queda en la auditoria (es contenido clinico). Exige hc:write \
					mas relacion asistencial o motivo declarado. La persona tiene que tener \
					historia: abrirla es la operacion PUT de la raiz.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Antecedente registrado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AntecedenteResponse.class))),
			@ApiResponse(responseCode = "400", description = "Cuerpo invalido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto, sin hc:write, o sin relacion asistencial ni "
							+ "motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La persona no tiene historia, o es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AntecedenteResponse> registrarAntecedente(

			@Parameter(description = "Persona titular de la historia", example = "12")
			@PathVariable long personaId,

			@Valid @RequestBody RegistrarAntecedenteRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		AntecedenteResponse cuerpo = AntecedenteResponse.from(antecedenteService.registrar(
				apiActor.current(), personaId, request.tipo(), request.descripcion(),
				justificacion));
		return ResponseEntity.status(HttpStatus.CREATED).body(cuerpo);
	}

	@PostMapping(path = "/antecedentes/{antecedenteId}/baja",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "darDeBajaAntecedenteClinico",
			summary = "Dar de baja un antecedente clinico",
			description = """
					RF-M09-002. Baja LOGICA con motivo obligatorio: el antecedente no se borra y \
					sigue consultable (regla maestra 10).

					Es IDEMPOTENTE: dar de baja uno que ya estaba de baja devuelve el antecedente \
					como estaba, con su motivo ORIGINAL intacto. Un antecedente que no es de la \
					historia de esa persona responde 404. Exige hc:write mas relacion asistencial \
					o motivo declarado.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Antecedente dado de baja",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AntecedenteResponse.class))),
			@ApiResponse(responseCode = "400", description = "Cuerpo invalido o motivo ausente",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto, sin hc:write, o sin relacion asistencial ni "
							+ "motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La persona no tiene historia, el antecedente no es de ella, o "
							+ "es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public AntecedenteResponse darDeBajaAntecedente(

			@Parameter(description = "Persona titular de la historia", example = "12")
			@PathVariable long personaId,

			@Parameter(description = "Antecedente a dar de baja", example = "41")
			@PathVariable long antecedenteId,

			@Valid @RequestBody BajaDeAntecedenteRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return AntecedenteResponse.from(antecedenteService.darDeBaja(
				apiActor.current(), personaId, antecedenteId, request.motivo(), justificacion));
	}
}
