package com.akine.clinical.api;

import com.akine.clinical.api.dto.AdjuntoClinicoResponse;
import com.akine.clinical.api.dto.AntecedenteResponse;
import com.akine.clinical.api.dto.BajaDeAntecedenteRequest;
import com.akine.clinical.api.dto.DatosDeclaradosResponse;
import com.akine.clinical.api.dto.FichaDeIngresoRequest;
import com.akine.clinical.api.dto.FichaDeIngresoResponse;
import com.akine.clinical.api.dto.RegistrarAntecedenteRequest;
import com.akine.clinical.application.AdjuntoClinicoAltaCommand;
import com.akine.clinical.application.AdjuntoClinicoService.AdjuntoClinicoAlta;
import com.akine.clinical.application.ContenidoDeAdjuntoClinico;
import com.akine.clinical.application.DatosDeclaradosService;
import com.akine.clinical.application.DatosDeclaradosService.TipoDatoDeclarado;
import com.akine.clinical.domain.CategoriaAdjuntoClinico;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * Datos que declara el paciente en su historia (DP-47), por persona.
 *
 * <p>Va por persona y no por historia: quien declara puede no tener —ni ver— la historia, y la
 * primera declaracion la abre. Todo exige {@code hc:declarar}, que no implica {@code hc:read}.
 * Revisar lo declarado son dos operaciones aparte, en los controladores de antecedentes y
 * adjuntos, con {@code hc:write}.
 */
@RestController
@RequestMapping(path = "/api/v1/personas/{personaId}/datos-declarados")
@Tag(name = "Datos declarados", description = "Lo que el paciente cuenta o trae, sin revisar (DP-47)")
public class DatosDeclaradosController {

	private static final String PERSONA_DOC = "Persona titular de la historia";

	private final DatosDeclaradosService service;
	private final ClinicalApiActor apiActor;

	public DatosDeclaradosController(DatosDeclaradosService service, ClinicalApiActor apiActor) {
		this.service = service;
		this.apiActor = apiActor;
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "verDatosDeclarados", summary = "Ver los datos declarados de una persona",
			description = """
					Antecedentes y archivos DECLARADO vigentes, con su estado de revision. Nada de lo \
					que cargo un profesional. Sin historia: listas vacias e historiaClinicaId null. \
					Exige hc:declarar mas motivo declarado; la lectura se audita.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Lo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = DatosDeclaradosResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:declarar o sin motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La persona es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public DatosDeclaradosResponse ver(
			@Parameter(description = PERSONA_DOC, example = "12") @PathVariable long personaId,
			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false) String justificacion) {
		return DatosDeclaradosResponse.from(service.ver(apiActor.current(), personaId, justificacion));
	}

	@PostMapping(path = "/antecedentes", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "declararAntecedente", summary = "Declarar un antecedente del paciente",
			description = """
					Queda DECLARADO y sin revisar hasta que lo revisa un profesional. Abre la \
					historia si falta. La persona tiene que tener perfil de paciente vigente (409 \
					persona-sin-perfil-paciente). Exige hc:declarar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Antecedente declarado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AntecedenteResponse.class))),
			@ApiResponse(responseCode = "400", description = "Cuerpo invalido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:declarar o sin motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La persona es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Sin perfil de paciente vigente",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AntecedenteResponse> declararAntecedente(
			@Parameter(description = PERSONA_DOC, example = "12") @PathVariable long personaId,
			@Valid @RequestBody RegistrarAntecedenteRequest request,
			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false) String justificacion) {
		AntecedenteResponse cuerpo = AntecedenteResponse.from(service.declararAntecedente(
				apiActor.current(), personaId, request.tipo(), request.descripcion(), request.detalle(),
				justificacion));
		return ResponseEntity.status(HttpStatus.CREATED).body(cuerpo);
	}

	@PutMapping(path = "/ficha", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "declararFichaDeIngreso", summary = "Guardar la ficha de ingreso declarada",
			description = """
					Ocupacion, peso, altura y actividad fisica. Reemplaza la ficha entera y exige la \n					version leida (null si todavia no existe); otra version es 409 \n					concurrent-modification. Queda DECLARADA y sin revisar, aunque ya estuviera \n					revisada. Abre la historia si falta. Exige hc:declarar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Ficha guardada",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = FichaDeIngresoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Valores fuera de rango u ocupacion inexistente",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:declarar o sin motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La persona es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Version vieja o sin perfil de paciente",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public FichaDeIngresoResponse declararFicha(
			@Parameter(description = PERSONA_DOC, example = "12") @PathVariable long personaId,
			@Valid @RequestBody FichaDeIngresoRequest request,
			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false) String justificacion) {
		return FichaDeIngresoResponse.from(service.declararFicha(
				apiActor.current(), personaId, request.datos(), request.version(), justificacion));
	}

	@PostMapping(path = "/adjuntos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "declararAdjunto", summary = "Declarar un archivo que trae el paciente",
			description = """
					Mismas reglas de archivo que subirAdjuntoClinico (tipo por bytes, tope de \
					tamano). Categorias: ESTUDIO, INFORME, IMAGEN u OTRO; las demas son 400. \
					Idempotente: el mismo archivo ya declarado devuelve 200 con el existente. Si el \
					mismo archivo ya lo cargo un profesional, 409 adjunto-ya-en-historia, sin datos \
					del existente. Abre la historia si falta. Exige hc:declarar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Archivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AdjuntoClinicoResponse.class))),
			@ApiResponse(responseCode = "200", description = "Ya estaba declarado: se devuelve el existente",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AdjuntoClinicoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Categoria no declarable, tipo no permitido o "
					+ "archivo demasiado grande",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:declarar o sin motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La persona es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Sin perfil de paciente vigente, o el archivo "
					+ "ya esta en la historia",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AdjuntoClinicoResponse> declararAdjunto(
			@Parameter(description = PERSONA_DOC, example = "12") @PathVariable long personaId,
			@Parameter(description = "El archivo") @RequestPart("archivo") MultipartFile archivo,
			@Parameter(description = "ESTUDIO, INFORME, IMAGEN u OTRO") @RequestParam CategoriaAdjuntoClinico categoria,
			@Parameter(description = "Titulo. Opcional, hasta 160 caracteres") @RequestParam(required = false)
			String titulo,
			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false) String justificacion) {
		AdjuntoClinicoAlta resultado = service.declararAdjunto(apiActor.current(), personaId,
				new AdjuntoClinicoAltaCommand(categoria, null, titulo,
						AdjuntoClinicoController.nombreDe(archivo), archivo.getContentType(),
						AdjuntoClinicoController.bytesDe(archivo)),
				justificacion);
		AdjuntoClinicoResponse cuerpo = AdjuntoClinicoResponse.from(resultado.adjunto());
		return resultado.creado()
				? ResponseEntity.status(HttpStatus.CREATED).body(cuerpo)
				: ResponseEntity.ok(cuerpo);
	}

	@GetMapping(path = "/adjuntos/{adjuntoId}/contenido",
			produces = {MediaType.APPLICATION_OCTET_STREAM_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(operationId = "verContenidoDeAdjuntoDeclarado", summary = "Abrir un archivo declarado",
			description = """
					Los bytes de un archivo DECLARADO, revisado o no, para que quien lo cargo \
					confirme que subio el correcto. Uno del profesional responde 404, igual que uno \
					inexistente. Mismas cabeceras que descargarAdjuntoClinico (attachment, nosniff). \
					Exige hc:declarar y se audita como ADJUNTO_CLINICO_DOWNLOADED.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El contenido del archivo",
					content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE)),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:declarar o sin motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No hay un archivo declarado con ese id",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "El almacenamiento no tiene el contenido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<byte[]> verContenido(
			@Parameter(description = PERSONA_DOC, example = "12") @PathVariable long personaId,
			@Parameter(description = "Archivo declarado", example = "17") @PathVariable long adjuntoId,
			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false) String justificacion) {
		ContenidoDeAdjuntoClinico contenido = service.contenido(apiActor.current(), personaId, adjuntoId, justificacion);
		ContentDisposition disposicion = ContentDisposition.attachment()
				.filename(contenido.nombreArchivo(), StandardCharsets.UTF_8)
				.build();
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, disposicion.toString())
				.header("X-Content-Type-Options", "nosniff")
				.contentType(MediaType.parseMediaType(contenido.contentType()))
				.body(contenido.contenido());
	}

	@PostMapping(path = "/{tipoDato}/{id}/baja", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(operationId = "darDeBajaDatoDeclarado", summary = "Dar de baja un dato declarado sin revisar",
			description = """
					Corrige un error de carga: solo un dato DECLARADO sin revisar. Revisado: 409 \
					dato-declarado-revisado (lo da de baja el profesional desde la historia). Uno \
					del profesional: 404. Motivo obligatorio. Exige hc:declarar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Dado de baja"),
			@ApiResponse(responseCode = "400", description = "Sin motivo o tipo de dato invalido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:declarar o sin motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No hay un dato declarado con ese id",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Ya lo reviso un profesional",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> darDeBaja(
			@Parameter(description = PERSONA_DOC, example = "12") @PathVariable long personaId,
			@Parameter(description = "antecedentes o adjuntos", example = "antecedentes",
					schema = @Schema(allowableValues = {"antecedentes", "adjuntos"}))
			@PathVariable String tipoDato,
			@Parameter(description = "Dato declarado", example = "41") @PathVariable long id,
			@Valid @RequestBody BajaDeAntecedenteRequest request,
			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false) String justificacion) {
		TipoDatoDeclarado tipo = switch (tipoDato) {
			case "antecedentes" -> TipoDatoDeclarado.ANTECEDENTES;
			case "adjuntos" -> TipoDatoDeclarado.ADJUNTOS;
			default -> throw new IllegalArgumentException("tipoDato es antecedentes o adjuntos");
		};
		service.darDeBaja(apiActor.current(), personaId, tipo, id, request.motivo(), justificacion);
		return ResponseEntity.noContent().build();
	}
}
