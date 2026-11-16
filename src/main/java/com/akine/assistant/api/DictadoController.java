package com.akine.assistant.api;

import com.akine.assistant.api.dto.PropuestaDeEvaluacionResponse;
import com.akine.assistant.api.dto.ProponerEvaluacionRequest;
import com.akine.assistant.api.dto.TranscripcionResponse;
import com.akine.assistant.application.DictadoService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Dictado por voz (docs/diseno/AKINE-dictado-por-voz.md). Un servidor Whisper local transcribe; ni
 * el audio ni el texto se guardan ni van al log.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/dictado",
		produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@Tag(name = "Dictado", description = "Dictado por voz en los campos de texto")
public class DictadoController {

	private static final String ERRORES_COMUNES = """
			Requiere `dictado:use` y el interruptor `akine.dictado.enabled`. No guarda nada: el texto \
			se guarda cuando el profesional guarda la pantalla.""";

	private final DictadoService dictado;
	private final AsistenteApiActor apiActor;

	public DictadoController(DictadoService dictado, AsistenteApiActor apiActor) {
		this.dictado = dictado;
		this.apiActor = apiActor;
	}

	@PostMapping(path = "/transcripciones", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(operationId = "transcribirDictado", summary = "Pasar a texto un audio dictado",
			description = """
					Transcribe una grabacion corta con un servidor Whisper local: la voz no sale del \
					servidor del centro. Acepta `audio/*` (y `video/webm` o `video/mp4`, como etiquetan \
					algunos navegadores una grabacion de solo audio) hasta `akine.dictado.max-bytes`. \
					El texto vuelve tal cual, sin resumir ni corregir.

					""" + ERRORES_COMUNES)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El texto dictado"),
			@ApiResponse(responseCode = "400", description = "`validation-error`: sin audio, demasiado grande o no "
					+ "es audio", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin `dictado:use` o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "`not-found`: la sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "`dictado-deshabilitado`: el interruptor esta apagado",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "503", description = "`dictado-no-disponible`: Whisper no responde o "
					+ "vencio el plazo", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public TranscripcionResponse transcribirDictado(
			@PathVariable long consultorioId,
			@Parameter(description = "La grabacion, como la produjo el navegador")
			@RequestPart("audio") MultipartFile audio) {

		return new TranscripcionResponse(dictado.transcribir(apiActor.current(), consultorioId, bytesDe(audio),
				audio.getContentType()));
	}

	@PostMapping(path = "/evaluaciones", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "proponerEvaluacionDictada",
			summary = "Repartir un texto dictado entre los campos de la evaluacion",
			description = """
					Propone dolor, zona, lateralidad, evolucion, objetivo, motivo y limitacion a partir \
					de un texto dictado. Lo decide el modelo de lenguaje local del asistente (exige \
					tambien `akine.asistente.enabled`), y el servidor descarta lo que no valida. **No \
					escribe en la sesion**: la pantalla muestra la propuesta y aplica solo lo que el \
					profesional confirma. Lo que no entro en ningun campo vuelve en `restante`.

					""" + ERRORES_COMUNES)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La propuesta"),
			@ApiResponse(responseCode = "400", description = "`validation-error`: texto vacio o de mas de 4000 "
					+ "caracteres", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin `dictado:use` o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "`not-found`: la sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "`dictado-deshabilitado`: el interruptor esta apagado",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "503", description = "`dictado-no-disponible`: el modelo esta apagado, no "
					+ "responde o vencio el plazo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public PropuestaDeEvaluacionResponse proponerEvaluacionDictada(
			@PathVariable long consultorioId,
			@RequestBody @Valid ProponerEvaluacionRequest request) {

		return PropuestaDeEvaluacionResponse.de(
				dictado.proponerEvaluacion(apiActor.current(), consultorioId, request.texto()));
	}

	/** Leer la parte es trabajo de {@code api}: ArchUnit no deja que {@code application} importe web. */
	private static byte[] bytesDe(MultipartFile audio) {
		try {
			return audio.getBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException("No se pudo leer el audio subido", e);
		}
	}
}
