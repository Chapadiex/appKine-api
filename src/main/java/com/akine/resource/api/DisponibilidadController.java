package com.akine.resource.api;

import com.akine.resource.api.dto.BloqueResponse;
import com.akine.resource.api.dto.CreateBloqueRequest;
import com.akine.resource.api.dto.DeactivateBloqueRequest;
import com.akine.resource.api.dto.DisponibilidadEfectivaResponse;
import com.akine.resource.api.dto.UpdateBloqueRequest;
import com.akine.resource.application.BloqueAltaCommand;
import com.akine.resource.application.BloqueEdicionCommand;
import com.akine.resource.application.BloqueView;
import com.akine.resource.application.DisponibilidadEfectivaService;
import com.akine.resource.application.DisponibilidadService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * Horario semanal de un profesional en una sede, y la disponibilidad efectiva que resulta de el
 * (M05: RF-M05-003, RF-M05-005).
 *
 * <h2>Por que la ruta cuelga de la sede y del profesional, y no lleva la organizacion</h2>
 *
 * <p>RN-M05-001: un bloque de disponibilidad es de un profesional EN UNA SEDE. Los dos ids tienen
 * que estar en la URL para que sean verificables contra el contexto ya validado.
 *
 * <p>La <b>organizacion no viaja en la ruta</b>, a diferencia de {@link EspacioController}: sale
 * del contexto que {@code TenantContextFilter} revalido contra la base en este request, nunca de
 * un parametro del cliente. Tiene una consecuencia que conviene saber antes de reportarla como
 * bug: <b>un {@code PLATFORM_ADMIN} no puede operar esta API</b>. Un administrador de plataforma
 * no tiene contexto de tenant (matriz §1.3), asi que cae en el 403 uniforme por falta de
 * contexto, exista o no la sede. Es deliberado y por eso el servicio tampoco tiene camino de
 * soporte ni emite {@code SUPPORT_ACCESS_USED}: seria codigo muerto. Darle paridad con espacios
 * es una decision DE CONTRATO —agregar la organizacion a la ruta— y no de este archivo.
 *
 * <h2>Codigos, y por que no son intercambiables</h2>
 *
 * <ul>
 *   <li><b>404</b> para una sede, un profesional o un bloque de otro tenant, de otra sede, de
 *       otro profesional, o inexistente. Un 403 confirmaria que ese id existe y bastaria recorrer
 *       numeros para reconstruir la agenda de cada centro del SaaS.</li>
 *   <li><b>403</b> cuando el actor esta dentro de su alcance y le falta el permiso, o cuando no
 *       eligio contexto de trabajo. <b>Nunca 401</b>: el interceptor del frontend borra el token
 *       ante cualquier 401 y dejaria al usuario en un bucle de login del que no sale.</li>
 *   <li><b>409</b> para los invariantes de estado —solapamiento, bloque ya dado de baja, sede
 *       dada de baja, profesional no vinculado a esa sede, version desactualizada—. El actor
 *       tiene el permiso; lo que no admite la operacion es el estado.</li>
 *   <li><b>400</b> para el pedido incoherente: dia fuera de 1..7, horario invertido, ventana
 *       invertida o mas amplia que el tope, baja sin motivo.</li>
 * </ul>
 *
 * <h2>Autorizacion: mutar y leer no piden lo mismo</h2>
 *
 * <ul>
 *   <li><b>Mutar</b> exige {@code consultorio:manage} sobre esa sede: pasan {@code ORG_ADMIN} y
 *       el {@code CONSULTORIO_ADMIN} de esa sede. De ahi sale sola la politica "el profesional no
 *       edita su propia disponibilidad": la matriz no le da ese codigo.</li>
 *   <li><b>Leer</b> exige {@code colaborador:read} sobre esa sede. Sin esa lectura un profesional
 *       no puede ver su propio horario.</li>
 * </ul>
 *
 * <p>No se creo ningun codigo de permiso nuevo para esta etapa.
 */
@RestController
@RequestMapping("/api/v1/consultorios/{consultorioId}/profesionales/{membershipId}/disponibilidad")
@Tag(name = "Disponibilidad profesional",
		description = "Horario semanal de un profesional en una sede y su disponibilidad efectiva")
public class DisponibilidadController {

	private static final Logger log = LoggerFactory.getLogger(DisponibilidadController.class);

	private final DisponibilidadService disponibilidadService;
	private final DisponibilidadEfectivaService disponibilidadEfectivaService;
	private final ApiActor apiActor;

	public DisponibilidadController(
			DisponibilidadService disponibilidadService,
			DisponibilidadEfectivaService disponibilidadEfectivaService,
			ApiActor apiActor) {

		this.disponibilidadService = disponibilidadService;
		this.disponibilidadEfectivaService = disponibilidadEfectivaService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listBloquesDisponibilidad",
			summary = "Horario semanal cargado de un profesional en la sede",
			description = """
					Devuelve los bloques ACTIVOS del profesional en esa sede, ordenados por dia y \
					hora (RF-M05-003). Es la lectura de las REGLAS: que horario tiene cargado. La \
					lectura del RESULTADO —que dias y horas concretas atiende— es \
					/disponibilidad/efectiva, y las dos conviven a proposito.

					Exige colaborador:read sobre esa sede, no consultorio:manage: sin eso un \
					profesional no podria ver su propio horario.

					Un profesional DESVINCULADO se sigue pudiendo consultar: RN-M05-003 dice que \
					desvincular no borra bloques ni autoria, y exigir vinculo vigente para leer \
					dejaria al administrador sin poder revisar el horario de quien se fue.

					Los bloques dados de baja NO aparecen. Esta version del contrato no tiene \
					filtro de estado; la fila sobrevive en la base y en la auditoria.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Bloques activos del profesional, ordenados por dia y hora. "
							+ "Lista vacia si no tiene horario cargado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = BloqueResponse.class)))),
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
	public ResponseEntity<List<BloqueResponse>> list(
			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Parameter(description = "Vinculo del profesional", example = "1")
			@PathVariable long membershipId) {

		return ResponseEntity.ok(
				disponibilidadService.listar(apiActor.current(), consultorioId, membershipId)
						.stream()
						.map(BloqueResponse::from)
						.toList());
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createBloqueDisponibilidad",
			summary = "Alta de un bloque recurrente de disponibilidad",
			description = """
					Carga un tramo del horario semanal del profesional en esa sede: "los martes \
					de 09 a 13, desde marzo" (RF-M05-003). Exige consultorio:manage sobre ESA \
					sede.

					RESPONDE 201 O 200, Y UN CLIENTE QUE SOLO CONTEMPLE 201 SE ROMPE. El alta es \
					IDEMPOTENTE sin Idempotency-Key: si el pedido reproduce EXACTAMENTE un bloque \
					activo que ya existe —mismo dia, mismas horas, misma vigencia— la respuesta \
					es 200 con ese bloque, no se crea ninguna fila y no se vuelve a auditar. Es \
					el reintento de red, y el historial tiene que quedar como lo dejo el primer \
					intento. Solo el alta que CREA la fila responde 201, y solo ella trae la \
					cabecera Location.

					Un alta sin vigenciaDesde que reproduce un bloque que YA rige tambien entra \
					por ahi: el pedido "que rija desde ahora" esta genuinamente satisfecho.

					Coincidir NO es lo mismo que solapar. Un bloque que se pisa con otro sin \
					reproducirlo exactamente es 409 bloque-solapado. Y solapar no es ser \
					contiguo: la hora de fin es EXCLUSIVA, asi que 09:00-12:00 y 12:00-15:00 \
					conviven —manana y tarde— sin conflicto.

					Dos temporadas del mismo horario tampoco se solapan: el mismo "martes de 09 a \
					13" cargado para marzo y para septiembre no es un conflicto, es un horario de \
					temporada. Se comparan las tres cosas: dia, horas Y ventana de vigencia.

					La sede tiene que estar ACTIVA: una sede dada de baja no origina hechos \
					nuevos y responde 409 consultorio-inactive. La EDICION y la BAJA si se \
					permiten sobre una sede inactiva, que es con lo que se ordena el horario de \
					un centro que se esta cerrando.

					El profesional tiene que tener un vinculo VIGENTE que lo habilite en esa sede \
					(RN-M05-001), o la respuesta es 409 profesional-no-vinculado.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Bloque creado. La cabecera Location apunta al recurso",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = BloqueResponse.class))),
			@ApiResponse(
					responseCode = "200",
					description = "ALTA IDEMPOTENTE: ya existia un bloque activo identico y se "
							+ "devuelve ese. No se creo ninguna fila y no hay Location",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = BloqueResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Dia fuera de 1..7, horas ausentes, u horario incoherente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Falta consultorio:manage sobre esa sede, o no hay contexto de "
							+ "trabajo seleccionado",
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
					description = "Solapamiento con otro bloque activo (bloque-solapado), sede "
							+ "dada de baja (consultorio-inactive), o profesional sin vinculo "
							+ "vigente en esa sede (profesional-no-vinculado)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<BloqueResponse> create(
			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Parameter(description = "Vinculo del profesional", example = "1")
			@PathVariable long membershipId,

			@Valid @RequestBody CreateBloqueRequest request) {

		// Los ids si se loguean —es lo que permite correlacionar con la fila de auditoria—; el
		// horario no, que es dato operativo del cliente.
		log.info("Alta de bloque de disponibilidad solicitada: consultorioId={} membershipId={}",
				consultorioId, membershipId);

		BloqueView resultado = disponibilidadService.crear(
				apiActor.current(), consultorioId, membershipId,
				new BloqueAltaCommand(
						request.diaSemana(),
						request.horaDesde(),
						request.horaHasta(),
						request.vigenciaDesde(),
						request.vigenciaHasta()));

		BloqueResponse cuerpo = BloqueResponse.from(resultado);
		if (!resultado.nuevo()) {
			// Reintento idempotente: 200 y sin Location. Un 201 aca le mentiria al cliente
			// diciendole que acaba de crear algo, y un Location sobre una fila preexistente
			// invitaria a tratarla como recien creada.
			return ResponseEntity.ok(cuerpo);
		}
		return ResponseEntity.created(URI.create(rutaDe(consultorioId, membershipId, resultado.id())))
				.body(cuerpo);
	}

	@PutMapping(path = "/{bloqueId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateBloqueDisponibilidad",
			summary = "Edicion de un bloque de disponibilidad",
			description = """
					Cambia dia, horas o ventana de vigencia de un bloque vigente (RF-M05-005). \
					Los campos omitidos NO se tocan; para dejar el bloque sin fin de vigencia se \
					manda limpiarVigenciaHasta=true, porque un null no puede expresar la \
					diferencia entre "no toques el fin" y "saca el fin".

					El PROFESIONAL no se puede cambiar: reasignar un bloque a otra persona no es \
					una edicion sino un bloque nuevo (RN-M05-001), y permitirlo dejaria la \
					autoria historica apuntando a quien nunca atendio en esa franja.

					version es obligatoria y se compara ANTES de mutar: si quedo vieja, 409 \
					concurrent-modification y el cliente recarga. Sin eso dos ediciones \
					simultaneas se pisan y el segundo en guardar borra el cambio del primero sin \
					que nadie se entere.

					AVISO AL CLIENTE: la respuesta trae turnosAfectados, y una edicion que QUITA \
					disponibilidad deja en conflicto los turnos que caian ahi (RN-M05-004). El \
					impacto se INFORMA, no bloquea: quien decide que hacer con esos turnos es la \
					pantalla. Hoy ese numero es siempre 0 porque el modulo de agenda no existe; \
					va a dejar de serlo sin cambiar este contrato.

					Un bloque dado de baja no se puede editar: 409 bloque-inactivo.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Bloque actualizado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = BloqueResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Dia fuera de 1..7, version ausente, u horario incoherente",
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
					description = "El bloque no existe, es de otra sede, de otro profesional o "
							+ "de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Bloque dado de baja (bloque-inactivo), solapamiento con otro "
							+ "activo (bloque-solapado), version desactualizada "
							+ "(concurrent-modification), o profesional sin vinculo vigente en "
							+ "esa sede (profesional-no-vinculado)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<BloqueResponse> update(
			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Parameter(description = "Vinculo del profesional", example = "1")
			@PathVariable long membershipId,

			@Parameter(description = "Identificador del bloque", example = "1")
			@PathVariable long bloqueId,

			@Valid @RequestBody UpdateBloqueRequest request) {

		BloqueView actualizado = disponibilidadService.editar(
				apiActor.current(), consultorioId, membershipId, bloqueId,
				new BloqueEdicionCommand(
						request.diaSemana(),
						request.horaDesde(),
						request.horaHasta(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						Boolean.TRUE.equals(request.limpiarVigenciaHasta()),
						request.version()));

		return ResponseEntity.ok(BloqueResponse.from(actualizado));
	}

	@DeleteMapping(path = "/{bloqueId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "deactivateBloqueDisponibilidad",
			summary = "Baja logica de un bloque de disponibilidad",
			description = """
					Da de baja el bloque con MOTIVO OBLIGATORIO en el cuerpo. NO borra nada: la \
					fila queda INACTIVA con su motivo, su autor y su instante (RN-M05-003). Una \
					baja sin motivo no se puede revisar seis meses despues, que es exactamente \
					cuando se la revisa.

					El motivo va en el CUERPO y no en la query string a proposito: un motivo en \
					la URL queda en los logs de acceso de cualquier proxy intermedio.

					Devuelve el bloque dado de baja, no un 204 vacio, porque el cuerpo trae \
					turnosAfectados: la baja QUITA disponibilidad y los turnos que caian ahi \
					quedan en conflicto (RN-M05-004). El impacto se INFORMA, no bloquea —ADR-0011 \
					prohibe decidir en cascada por el usuario—, y hoy vale siempre 0 porque el \
					modulo de agenda no existe.

					Se permite aunque la sede este dada de baja y aunque el profesional ya se \
					haya desvinculado: son las operaciones con las que se ordena el horario de un \
					centro que se esta cerrando, y prohibirlas lo dejarian congelado.

					No hay reactivacion. Un bloque que vuelve es una regla nueva.

					Dar de baja dos veces es 409 bloque-already-inactive: "ya estaba dado de \
					baja" es informacion distinta de "no existe", y es la unica que le sirve al \
					administrador que apreto el boton dos veces.

					Exige consultorio:manage sobre esa sede.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Bloque dado de baja, con el impacto informado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = BloqueResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo de la baja, o el cuerpo no viene",
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
					description = "El bloque no existe, es de otra sede, de otro profesional o "
							+ "de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ya estaba dado de baja (bloque-already-inactive)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<BloqueResponse> deactivate(
			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Parameter(description = "Vinculo del profesional", example = "1")
			@PathVariable long membershipId,

			@Parameter(description = "Identificador del bloque", example = "1")
			@PathVariable long bloqueId,

			@Valid @RequestBody DeactivateBloqueRequest request) {

		BloqueView baja = disponibilidadService.darDeBaja(
				apiActor.current(), consultorioId, membershipId, bloqueId, request.reason());

		return ResponseEntity.ok(BloqueResponse.from(baja));
	}

	@GetMapping("/efectiva")
	@Operation(
			operationId = "getDisponibilidadEfectiva",
			summary = "Disponibilidad efectiva del profesional, dia por dia",
			description = """
					Resuelve que dias y horas CONCRETAS atiende el profesional en esa sede \
					durante la ventana pedida, aplicando todo junto: la vigencia de cada bloque, \
					las excepciones de la sede, las del profesional, la vigencia de su vinculo y \
					la politica de feriados de la sede.

					LEER ESTO ANTES DE ESCRIBIR LA PANTALLA. franja.origen, franja.recortadoPor y \
					dia.razonVacio NO son extras: son el criterio de aceptacion de la etapa —"la \
					disponibilidad efectiva explica que regla la afecta"—. Una pantalla que dibuje \
					las franjas y descarte esos tres campos deja al administrador sin poder saber \
					por que un dia esta en blanco, que es justamente donde no puede adivinarlo.

					razonVacio tiene CUATRO valores y confundir dos de ellos manda a alguien a \
					buscar algo que no existe: FERIADO, CIERRE, VINCULO —el vinculo del \
					profesional no estaba vigente ese dia— y null. "No atiende ese dia" y "ya no \
					trabaja aca" necesitan textos distintos.

					NUNCA se omite un dia de [desde, hasta), ni siquiera si el profesional ya no \
					trabaja en el centro: la pantalla dibuja una grilla de fechas y un dia \
					faltante la correria.

					Las franjas viajan en INSTANTES UTC y no en horas de pared: la hora local ya \
					esta en la lectura de las REGLAS, y lo que consume un resultado de \
					disponibilidad es una agenda, que compara instantes. timezone viaja aparte \
					para poder rotular. Una franja que llega al fin del dia trae el INICIO DEL \
					DIA SIGUIENTE, no las 23:59:59.999999999.

					La ventana es semiabierta: hasta es EXCLUSIVO. hasta == desde es 400, no "un \
					dia". El maximo es de 366 dias —un ano completo, aunque sea bisiesto—: sin \
					tope, una ventana enorme es un scan, porque la disponibilidad efectiva no \
					esta materializada y se resuelve un dia por iteracion. El error de ventana \
					demasiado amplia trae maximoDias en el cuerpo para que la pantalla recorte \
					sola.

					Exige colaborador:read sobre esa sede.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Un dia por cada fecha de la ventana, en orden ascendente",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(
									implementation = DisponibilidadEfectivaResponse.class))),
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
	public ResponseEntity<DisponibilidadEfectivaResponse> efectiva(
			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Parameter(description = "Vinculo del profesional", example = "1")
			@PathVariable long membershipId,

			@Parameter(
					description = "Primer dia de la ventana, fecha local ISO-8601",
					required = true,
					example = "2026-09-01")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,

			@Parameter(
					description = "Fin de la ventana, EXCLUSIVO, fecha local ISO-8601",
					required = true,
					example = "2026-10-01")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {

		return ResponseEntity.ok(DisponibilidadEfectivaResponse.from(
				disponibilidadEfectivaService.efectiva(
						apiActor.current(), consultorioId, membershipId, desde, hasta)));
	}

	private static String rutaDe(long consultorioId, long membershipId, long bloqueId) {
		return "/api/v1/consultorios/" + consultorioId + "/profesionales/" + membershipId
				+ "/disponibilidad/" + bloqueId;
	}
}
