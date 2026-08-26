package com.akine.resource.api;

import com.akine.resource.api.dto.CreateExcepcionRequest;
import com.akine.resource.api.dto.DeactivateExcepcionRequest;
import com.akine.resource.api.dto.ExcepcionResponse;
import com.akine.resource.application.ExcepcionAltaCommand;
import com.akine.resource.application.ExcepcionService;
import com.akine.resource.application.ExcepcionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * Cierres y aperturas puntuales de disponibilidad de una sede (M05, RF-M05-004).
 *
 * <h2>Por que la ruta cuelga de la sede y NO del profesional</h2>
 *
 * <p>Es la diferencia con {@link DisponibilidadController}, y es de modelo, no de estilo: una
 * excepcion puede ser de un profesional <b>o de la SEDE ENTERA</b>. El alcance es un dato del
 * pedido, no de la ruta; ponerlo en la URL obligaria a inventar un id centinela para el caso
 * "toda la sede", que es exactamente como se cuelan los bugs de alcance —el dia que ese id exista
 * de verdad, el cierre de la sede se le aplica a una persona—.
 *
 * <p>La organizacion tampoco viaja en la ruta: sale del contexto revalidado. Ver el javadoc de
 * {@link DisponibilidadController} para la consecuencia sobre el {@code PLATFORM_ADMIN}.
 *
 * <h2>Autorizacion</h2>
 *
 * <p>Lectura {@code colaborador:read}, mutacion {@code consultorio:manage}, los mismos dos
 * codigos que el resto de M05. Sin codigos nuevos.
 */
@RestController
@RequestMapping("/api/v1/consultorios/{consultorioId}/excepciones")
@Tag(name = "Excepciones de disponibilidad",
		description = "Cierres y aperturas puntuales de una sede o de un profesional")
public class ExcepcionController {

	private static final Logger log = LoggerFactory.getLogger(ExcepcionController.class);

	private final ExcepcionService excepcionService;
	private final ApiActor apiActor;

	public ExcepcionController(ExcepcionService excepcionService, ApiActor apiActor) {
		this.excepcionService = excepcionService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listExcepciones",
			summary = "Excepciones vigentes de la sede en una ventana",
			description = """
					Devuelve las excepciones ACTIVAS cuya ventana de fechas se solapa con \
					[desde, hasta).

					LAS DOS RESPUESTAS SON DISTINTAS, Y NO POR CASUALIDAD:

					CON membershipId devuelve las de ESE profesional MAS las de alcance SEDE \
					ENTERA. Las dos poblaciones son obligatorias: si devolviera solo las suyas, \
					un cierre de sede completo dejaria de aplicarsele sin que nada lo detecte.

					SIN membershipId devuelve SOLO las de alcance SEDE. Es la vista del calendario \
					del centro; devolver ahi las de todos los profesionales convertiria una vista \
					de sede en un listado de ausencias de personas, que es informacion de otra \
					pantalla y de otro permiso.

					La ventana es semiabierta: hasta es EXCLUSIVO, y hasta == desde es 400, no \
					"un dia". El maximo es de 366 dias; el error trae maximoDias para que la \
					pantalla recorte sola.

					Un profesional DESVINCULADO se sigue pudiendo consultar: RN-M05-003, \
					desvincular no borra su historia.

					Exige colaborador:read sobre esa sede.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Excepciones activas que cubren parte de la ventana. Lista "
							+ "vacia si no hay ninguna",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = ExcepcionResponse.class)))),
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
					description = "La sede o el profesional no existen, o pertenecen a otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<ExcepcionResponse>> list(
			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Parameter(
					description = "Primer dia de la ventana, fecha local ISO-8601",
					required = true,
					example = "2026-09-01")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,

			@Parameter(
					description = "Fin de la ventana, EXCLUSIVO, fecha local ISO-8601",
					required = true,
					example = "2026-10-01")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,

			@Parameter(
					description = "Vinculo del profesional. OMITIRLO devuelve SOLO las "
							+ "excepciones de alcance SEDE, no las de todos los profesionales",
					example = "1")
			@RequestParam(required = false) Long membershipId) {

		return ResponseEntity.ok(
				excepcionService.listar(
								apiActor.current(), consultorioId, desde, hasta, membershipId)
						.stream()
						.map(ExcepcionResponse::from)
						.toList());
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createExcepcion",
			summary = "Alta de un cierre o de una apertura puntual",
			description = """
					Carga una excepcion al horario base: una ausencia, una licencia, un bloqueo o \
					una ampliacion excepcional (RF-M05-004). Exige consultorio:manage sobre ESA \
					sede.

					RESPONDE 201 O 200, Y UN CLIENTE QUE SOLO CONTEMPLE 201 SE ROMPE. El alta es \
					IDEMPOTENTE sin Idempotency-Key (CA-M05-004-05): un pedido que coincide \
					EXACTAMENTE con una excepcion activa que ya existe devuelve 200 con esa fila, \
					no crea nada y no vuelve a auditar. notes y feriadoId quedan FUERA de esa \
					comparacion. Solo el alta que crea la fila responde 201, y solo ella trae \
					Location.

					NO HAY 409 POR SOLAPAMIENTO, Y ES UNA DIFERENCIA DELIBERADA CON LOS BLOQUES. \
					Dos excepciones que se pisan sin ser identicas son las dos legitimas y las \
					dos se guardan: una ausencia de una tarde dentro de una licencia mas larga es \
					un hecho corriente, no un conflicto. No escribir manejo de error de \
					solapamiento para esta operacion: nunca lo va a emitir.

					Los 409 que SI existen aca son otros dos, y ninguno depende de las fechas: la \
					sede dada de baja (consultorio-inactive) y el profesional sin vinculo vigente \
					en esa sede (profesional-no-vinculado), este ultimo solo cuando se manda \
					membershipId.

					OMITIR membershipId significa SEDE ENTERA —afecta a TODOS los profesionales—, \
					no un dato faltante. Y fechaHasta es EXCLUSIVA: un cierre de un solo dia se \
					carga como [D, D+1). Mandar fechaDesde == fechaHasta cierra cero dias.

					APERTURA no es el caso raro: es como un centro declara que atiende un feriado \
					o que suma una banda un sabado puntual.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Excepcion creada. La cabecera Location apunta al recurso",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ExcepcionResponse.class))),
			@ApiResponse(
					responseCode = "200",
					description = "ALTA IDEMPOTENTE: ya existia una excepcion activa identica y "
							+ "se devuelve esa. No se creo ninguna fila y no hay Location",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ExcepcionResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos obligatorios ausentes, rango de fechas incoherente, o "
							+ "una sola de las dos horas (vienen las dos o ninguna)",
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
					description = "La sede o el profesional no existen, o pertenecen a otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Sede dada de baja (consultorio-inactive), o profesional sin "
							+ "vinculo vigente en esa sede (profesional-no-vinculado). NUNCA por "
							+ "solapamiento: dos excepciones que se pisan son las dos validas",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ExcepcionResponse> create(
			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Valid @RequestBody CreateExcepcionRequest request) {

		log.info("Alta de excepcion de disponibilidad solicitada: consultorioId={} alcance={}",
				consultorioId, request.membershipId() == null ? "SEDE" : "PROFESIONAL");

		ExcepcionView resultado = excepcionService.crear(
				apiActor.current(), consultorioId,
				new ExcepcionAltaCommand(
						request.membershipId(),
						request.tipo(),
						request.motivo(),
						request.fechaDesde(),
						request.fechaHasta(),
						request.horaDesde(),
						request.horaHasta(),
						request.feriadoId(),
						request.notes()));

		ExcepcionResponse cuerpo = ExcepcionResponse.from(resultado);
		if (!resultado.nuevo()) {
			// Reintento idempotente: 200 y sin Location, por lo mismo que en el alta de bloques.
			return ResponseEntity.ok(cuerpo);
		}
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/excepciones/"
						+ resultado.id()))
				.body(cuerpo);
	}

	@DeleteMapping(path = "/{excepcionId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "deactivateExcepcion",
			summary = "Baja logica de una excepcion de disponibilidad",
			description = """
					Da de baja la excepcion con MOTIVO OBLIGATORIO en el cuerpo. NO borra nada: \
					la fila queda INACTIVA con su motivo, su autor y su instante, y sus fechas \
					intactas. Cancelar una licencia es un hecho que hay que poder reconstruir.

					El motivo va en el CUERPO y no en la query string: en la URL quedaria en los \
					logs de acceso de cualquier proxy intermedio.

					Se permite aunque la sede este dada de baja y aunque el profesional ya se \
					haya desvinculado: son las operaciones con las que se ordena el calendario de \
					un centro que se esta cerrando.

					Dar de baja dos veces es 409 excepcion-already-inactive: "ya estaba dada de \
					baja" es informacion distinta de "no existe".

					Exige consultorio:manage sobre esa sede.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Excepcion dada de baja, con sus fechas intactas",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ExcepcionResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo de la baja, o el cuerpo esta vacio o mal "
							+ "formado",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "415",
					description = "Falta la cabecera Content-Type: application/json en el cuerpo "
							+ "del DELETE. Algunos clientes HTTP generados no la emiten por "
							+ "defecto en un DELETE con cuerpo",
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
					description = "La excepcion no existe, es de otra sede o de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ya estaba dada de baja (excepcion-already-inactive)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ExcepcionResponse> deactivate(
			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador de la excepcion", example = "1")
			@PathVariable long excepcionId,

			@Valid @RequestBody DeactivateExcepcionRequest request) {

		return ResponseEntity.ok(ExcepcionResponse.from(excepcionService.darDeBaja(
				apiActor.current(), consultorioId, excepcionId, request.reason())));
	}
}
