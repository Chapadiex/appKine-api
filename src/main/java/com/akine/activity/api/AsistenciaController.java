package com.akine.activity.api;

import com.akine.activity.api.dto.AsistenciaEventoResponse;
import com.akine.activity.api.dto.ClaseResponse;
import com.akine.activity.api.dto.DetalleOperativoResponse;
import com.akine.activity.api.dto.IngresoSinInscripcionRequest;
import com.akine.activity.api.dto.RegistrarAsistenciaLoteRequest;
import com.akine.activity.api.dto.RegistrarAsistenciaRequest;
import com.akine.activity.api.dto.ResultadoDeAsistenciaResponse;
import com.akine.activity.api.dto.ResultadoDeCierreResponse;
import com.akine.activity.api.dto.ResultadoDeLoteResponse;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.IngresoSinInscripcionCommand;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.ResultadoDeAsistencia;
import com.akine.activity.domain.OrigenAsistencia;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Asistencia y operacion de clases (M28, AKINE-08.03).
 *
 * <p>La ruta cuelga de la CLASE por lo mismo que las inscripciones: una asistencia no existe sin
 * ella, y su ciclo de vida lo manda la clase.
 *
 * <p><b>{@code produces} declara los dos tipos.</b> Declarar solo {@code application/json} hizo que
 * siete operaciones de otras etapas respondieran 406 al cliente generado, que manda
 * {@code application/problem+json} en el {@code Accept}: la negociacion corta ANTES de entrar al
 * metodo y ningun test lo agarraba.
 *
 * <p><b>Ninguna operacion de aca devenga ni crea registro clinico.</b> Marcar presente no cobra
 * (RF-M18-008 no es evaluable todavia: su esquema y su politica no existen en ninguna tabla) y no
 * crea Sesion (RN-M28-007). 08.04 deriva al circuito clinico y 08.06/08.07 ponen la economia.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/clases/{claseId}",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(
		name = "Asistencia de clases",
		description = "Ciclo operativo de la clase y asistencia por participante (M28, M13)")
public class AsistenciaController {

	/** Igual que el de los adjuntos clinicos: base cero, tope duro y default razonable. */
	private static final String PAGINA_POR_DEFECTO = "0";
	private static final String TAMANO_POR_DEFECTO = "20";
	private static final int TAMANO_MAXIMO = 100;

	private final AsistenciaService asistenciaService;
	private final ActivityApiActor apiActor;

	public AsistenciaController(AsistenciaService asistenciaService, ActivityApiActor apiActor) {
		this.asistenciaService = asistenciaService;
		this.apiActor = apiActor;
	}

	// =================================================================================
	// Ciclo operativo
	// =================================================================================

	@PostMapping("/inicio")
	@Operation(
			summary = "Abrir la clase para tomar lista",
			description = """
					`PROGRAMADA` -> `EN_CURSO` (RF-M13-007). **Idempotente**: iniciar una que ya \
					esta en curso devuelve la misma clase y conserva al actor que la abrio primero.

					**No exige que el reloj haya llegado al horario**: una clase que arranca cinco \
					minutos antes es normal y el sistema no tiene por que discutirlo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La clase quedo en curso"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `clase:manage` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la clase no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La clase esta cancelada o ya se realizo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ClaseResponse iniciar(
			@PathVariable long consultorioId, @PathVariable long claseId) {

		return ClaseResponse.de(asistenciaService.iniciar(apiActor.current(), consultorioId, claseId));
	}

	@PostMapping("/cierre")
	@Operation(
			summary = "Cerrar la operacion de la clase",
			description = """
					Marca como **ausentes** a los que tenian lugar y nadie resolvio, cancela a los \
					que quedaron en la cola de una clase que ya ocurrio, y deja la clase \
					`REALIZADA`.

					**Idempotente sin bandera**: un segundo cierre no encuentra pendientes porque \
					el unique `(clase, persona)` hace imposible una segunda asistencia. Por eso \
					"cerrar dos veces no duplica obligaciones" va a seguir siendo cierto cuando \
					08.06 cuelgue el devengo.

					**Cerrar no cobra y no devenga nada**, igual que el cierre de Sesion (DP-06).""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La clase quedo realizada"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `clase:manage` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la clase no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La clase esta cancelada y no tiene operacion que cerrar",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResultadoDeCierreResponse cerrar(
			@PathVariable long consultorioId, @PathVariable long claseId) {

		return ResultadoDeCierreResponse.de(
				asistenciaService.cerrar(apiActor.current(), consultorioId, claseId));
	}

	// =================================================================================
	// Asistencia
	// =================================================================================

	@PostMapping("/asistencias")
	@Operation(
			summary = "Marcar a un participante",
			description = """
					Registra el hecho de que la persona estuvo —o no— (RF-M28-007). **No crea una \
					Sesion clinica** (RN-M28-007) y **no genera obligacion**.

					**La idempotencia sale del hecho, no de una clave**: repetir el mismo \
					resultado devuelve 200 sin escribir nada; mandar otro resultado es una \
					**correccion**, que exige `motivo` y **apendea** el valor anterior en el \
					historial en vez de borrarlo.

					**El cupo no se mueve**: los cuatro estados involucrados consumen lugar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Se registro el hecho por primera vez"),
			@ApiResponse(
					responseCode = "200",
					description = "Se corrigio un hecho previo, o el pedido repetia lo ya "
							+ "registrado y no se escribio nada"),
			@ApiResponse(
					responseCode = "400",
					description = "Correccion sin motivo, o datos invalidos",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `asistencia:manage` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la clase o la inscripcion no existen en este alcance",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La clase todavia no se inicio o esta cancelada, o la "
							+ "inscripcion esta cancelada o en lista de espera",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ResultadoDeAsistenciaResponse> registrar(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@RequestBody @Valid RegistrarAsistenciaRequest request) {

		ResultadoDeAsistencia resultado = asistenciaService.registrar(
				apiActor.current(), consultorioId, claseId,
				comando(request, OrigenAsistencia.MOSTRADOR));

		ResultadoDeAsistenciaResponse cuerpo = ResultadoDeAsistenciaResponse.de(resultado);

		// 201 solo cuando se creo el hecho. Una correccion y un reintento devuelven 200: un 201
		// afirmaria que se creo algo que ya existia.
		if (!resultado.registrada()) {
			return ResponseEntity.ok(cuerpo);
		}
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/clases/" + claseId
						+ "/asistencias/" + resultado.asistencia().id()))
				.body(cuerpo);
	}

	@PostMapping("/asistencias/lote")
	@Operation(
			summary = "Marcar a varios participantes",
			description = """
					La lista compacta de RF-M13-008. **Cada item corre en su propia transaccion**: \
					un fallo individual no revierte a los anteriores ni oculta los resultados de \
					los demas (CA-M13-008-06).

					**Responde 200 aunque haya fallos parciales** — no es un error del pedido, es \
					el resultado del pedido—, y cada item fallido trae el mismo `problemType` que \
					habria viajado en la operacion individual.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Resultado por participante"),
			@ApiResponse(
					responseCode = "400",
					description = "Lote vacio o por encima del tope",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `asistencia:manage` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la clase no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResultadoDeLoteResponse registrarLote(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@RequestBody @Valid RegistrarAsistenciaLoteRequest request) {

		List<RegistrarAsistenciaCommand> comandos = request.items().stream()
				.map(item -> comando(item, OrigenAsistencia.LOTE))
				.toList();

		return ResultadoDeLoteResponse.de(asistenciaService.registrarLote(
				apiActor.current(), consultorioId, claseId, comandos));
	}

	@PostMapping("/ingresos")
	@Operation(
			summary = "Dejar entrar a quien se presento sin estar inscripto",
			description = """
					**No hay asistencia sin inscripcion**: una asistencia huerfana es una persona \
					adentro de la clase que el contador de cupo no ve, o sea la sobreventa por la \
					puerta de atras. Por eso esta operacion **crea la inscripcion tomando el lugar \
					de verdad**, con la misma sentencia condicional que protege el cupo desde \
					08.02.

					Si no queda lugar, `clase-completa` con la capacidad efectiva y los ocupados. \
					**No hay lista de espera**: una clase en curso no tiene cola.

					Es la unica operacion de esta etapa que levanta la regla "la clase ya empezo", \
					porque es justamente su condicion de entrada.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Inscripta y marcada"),
			@ApiResponse(
					responseCode = "403",
					description = "Faltan `inscripcion:manage` y `asistencia:manage`, o el "
							+ "contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la clase o la persona no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La clase no esta en curso, la persona ya esta anotada, o no "
							+ "queda lugar",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ResultadoDeAsistenciaResponse> registrarIngreso(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@RequestBody @Valid IngresoSinInscripcionRequest request) {

		ResultadoDeAsistencia resultado = asistenciaService.registrarIngresoSinInscripcion(
				apiActor.current(), consultorioId, claseId,
				new IngresoSinInscripcionCommand(
						request.personaId(),
						request.resultado().aDominio(),
						request.observaciones()));

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/clases/" + claseId
						+ "/asistencias/" + resultado.asistencia().id()))
				.body(ResultadoDeAsistenciaResponse.de(resultado));
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	@GetMapping("/detalle-operativo")
	@Operation(
			summary = "Detalle operativo de la clase",
			description = """
					Cabecera y listado compacto paginado (RF-M28-009, CA-M28-009-06). Los nombres \
					se resuelven en **un solo batch**: de a uno, la pantalla haria tantas \
					consultas como participantes.

					**Lo que no lleva es la mitad del punto** (RNF-M28-002): ni un dato clinico, \
					ni cobertura, ni deuda, ni pase, ni abono. Lo clinico porque quien toma lista \
					no puede ver a que se atiende nadie; lo economico porque todavia no existe.

					Exige `inscripcion:read`, **que no es `clase:read`**: quien mira la grilla del \
					dia no necesita saber quien esta anotado.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pagina de participantes"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `inscripcion:read` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la clase no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public DetalleOperativoResponse detalleOperativo(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@RequestParam(defaultValue = PAGINA_POR_DEFECTO) int page,
			@RequestParam(defaultValue = TAMANO_POR_DEFECTO) int size) {

		int pagina = Math.max(page, 0);
		int tamano = Math.min(Math.max(size, 1), TAMANO_MAXIMO);

		return DetalleOperativoResponse.de(asistenciaService.detalleOperativo(
				apiActor.current(), consultorioId, claseId, pagina, tamano));
	}

	@GetMapping("/asistencias/{asistenciaId}/historial")
	@Operation(
			summary = "Historial de una asistencia",
			description = """
					Los registros y correcciones, del mas viejo al mas nuevo (RN-M28-009). Es lo \
					que hace auditable la correccion: **el resultado anterior no se pierde al \
					pisarse**, queda aca con su motivo, su actor y su instante.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Historial append-only"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `inscripcion:read` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la clase o la asistencia no existen en este alcance",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public List<AsistenciaEventoResponse> historial(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@PathVariable long asistenciaId) {

		return asistenciaService
				.historial(apiActor.current(), consultorioId, claseId, asistenciaId).stream()
				.map(AsistenciaEventoResponse::de)
				.toList();
	}

	/**
	 * El {@code origen} lo fija el <b>servidor</b> segun el endpoint, nunca el cliente: es lo que
	 * distingue una ausencia declarada de un no-show puesto por el cierre, y dejarlo elegir
	 * convertiria esa distincion en una declaracion jurada del frontend.
	 */
	private static RegistrarAsistenciaCommand comando(
			RegistrarAsistenciaRequest request, OrigenAsistencia origen) {

		return new RegistrarAsistenciaCommand(
				request.inscripcionId(),
				request.resultado().aDominio(),
				origen,
				request.observaciones(),
				request.motivo());
	}
}
