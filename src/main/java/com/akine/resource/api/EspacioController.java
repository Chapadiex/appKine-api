package com.akine.resource.api;

import com.akine.resource.api.dto.CreateEspacioRequest;
import com.akine.resource.api.dto.DeactivateEspacioRequest;
import com.akine.resource.api.dto.EspacioAvailabilityResponse;
import com.akine.resource.api.dto.EspacioPageResponse;
import com.akine.resource.api.dto.EspacioResponse;
import com.akine.resource.api.dto.UpdateEspacioRequest;
import com.akine.resource.application.EspacioAltaCommand;
import com.akine.resource.application.EspacioEdicionCommand;
import com.akine.resource.application.EspacioEstadoFiltro;
import com.akine.resource.application.EspacioService;
import com.akine.resource.application.EspacioView;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
 * Administracion de los espacios fisicos de una sede (M04).
 *
 * <h2>Por que la ruta cuelga de la sede</h2>
 *
 * <p>RN-M04-001: todo espacio pertenece a un consultorio. Una ruta plana
 * {@code /espacios/{id}} obligaria a resolver la sede desde el cuerpo o desde el contexto, y en
 * los dos casos el {@code consultorioId} de la URL dejaria de ser verificable contra el
 * contexto validado. Con la sede en la ruta, la comparacion es explicita y esta en un solo
 * lugar.
 *
 * <h2>Codigos, y por que no son intercambiables</h2>
 *
 * <ul>
 *   <li><b>404</b> para un espacio o una sede de otro tenant, de otra sede, o inexistente. Un
 *       403 confirmaria que ese id existe y bastaria recorrer numeros para saber cuantos boxes
 *       tiene cada centro del SaaS.</li>
 *   <li><b>403</b> cuando el actor esta dentro de su alcance y le falta el permiso, o cuando no
 *       eligio contexto de trabajo. <b>Nunca 401</b>: el interceptor del frontend borra el token
 *       ante cualquier 401 y dejaria al usuario en un bucle de login.</li>
 *   <li><b>409</b> para los invariantes de estado —nombre repetido, espacio ya inactivo, sede
 *       dada de baja, version desactualizada, capacidad por debajo de la ocupacion—. El actor
 *       tiene el permiso; lo que no admite la operacion es el estado.</li>
 * </ul>
 *
 * <p>Un espacio INACTIVO se lee con 200, no con 404: RN-M04-003 exige que los historicos
 * conserven su nombre y su estado, y responder "no existe" seria borrar historia por la puerta
 * de atras.
 *
 * <h2>Autorizacion: mutar y leer no piden lo mismo</h2>
 *
 * <ul>
 *   <li><b>Mutar</b> exige {@code consultorio:manage} sobre esa sede: pasan {@code ORG_ADMIN} y
 *       el {@code CONSULTORIO_ADMIN} de esa sede.</li>
 *   <li><b>Leer</b> exige ser miembro vigente de la organizacion, con cualquier rol. Es lo que
 *       la etapa pide —"profesionales/administrativos solo consulta"— y es la enmienda 9.1 de
 *       la matriz aplicada al mismo caso que el listado de sedes: sin esa lectura, un
 *       profesional no puede ver en que box atiende.</li>
 * </ul>
 *
 * <p><b>El catalogo de la matriz no tiene un codigo de lectura de espacios.</b>
 * {@code espacio:read} queda PROPUESTO en la seccion 9.8 de la matriz y sin implementar: el
 * catalogo es vinculante y agregarle una fila es una decision de la matriz, no de una etapa.
 *
 * <h2>Lo que esta etapa deliberadamente NO trae</h2>
 *
 * <ul>
 *   <li><b>Asignar un espacio a un turno</b> (RF-M04-004) y <b>a una sesion</b> (RF-M04-005):
 *       son hechos de {@code scheduling} y {@code clinical}, y llegan en F5. El plan asigna a
 *       02.02 unicamente RF-M04-001..003, 006 y 007.</li>
 *   <li><b>Habilitar espacios por Oferta de Servicio</b> (RF-M04-008): modulo
 *       {@code offering}.</li>
 *   <li><b>Reactivacion de un espacio dado de baja</b>: ningun RF de M04 la pide. Un recurso que
 *       vuelve de una refaccion es una ventana operativa nueva, no una baja deshecha.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/consultorios/{consultorioId}/espacios")
@Tag(name = "Espacios",
		description = "Alta, edicion, baja y disponibilidad de los espacios fisicos de una sede")
public class EspacioController {

	private static final Logger log = LoggerFactory.getLogger(EspacioController.class);

	private final EspacioService espacioService;
	private final ApiActor apiActor;

	public EspacioController(EspacioService espacioService, ApiActor apiActor) {
		this.espacioService = espacioService;
		this.apiActor = apiActor;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createEspacio",
			summary = "Alta de un espacio",
			description = """
					Crea un box, gimnasio, gabinete, sala grupal u otro recurso fisico en la \
					sede (RF-M04-001, RF-M04-007).

					Exige consultorio:manage sobre ESA sede, asi que pasan un ORG_ADMIN de la \
					organizacion y el CONSULTORIO_ADMIN de esa sede, y no un PROFESIONAL ni un \
					ADMINISTRATIVO.

					Solo name es obligatorio. Los defaults son tipo=BOX, capacidad=1, \
					validFrom=ahora y sin fin de vigencia, que es el caso mas frecuente.

					No lleva Idempotency-Key, a diferencia del alta de una sede, y es \
					deliberado: un espacio no consume cupo de ningun plan, asi que lo unico que \
					un reintento podria producir es una fila duplicada, y contra eso el unique \
					de nombre entre los espacios vigentes es una garantia mas fuerte que una \
					clave —no depende de que el cliente la mande ni de que la reuse bien—. El \
					reintento responde 409 espacio-name-taken y no crea nada. La contrapartida: \
					despues de un timeout de red hay que releer el listado para saber si el alta \
					original entro.

					La sede tiene que estar ACTIVA: una sede dada de baja no origina hechos \
					nuevos y responde 409 consultorio-inactive.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Espacio creado. La cabecera Location apunta al recurso",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EspacioResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, capacidad fuera de rango, tipo que no "
							+ "pertenece al catalogo, o ventana de vigencia incoherente",
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
					description = "La sede no existe o pertenece a otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Nombre repetido entre los espacios vigentes de la sede "
							+ "(espacio-name-taken), sede dada de baja (consultorio-inactive), o "
							+ "suscripcion suspendida (subscription-suspended)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EspacioResponse> create(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Valid @RequestBody CreateEspacioRequest request) {

		// El nombre del espacio no se loguea: es dato comercial del cliente. Los ids si, que es
		// lo que permite correlacionar con la fila de auditoria.
		log.info("Alta de espacio solicitada: organizationId={} consultorioId={}",
				orgId, consultorioId);

		EspacioView creado = espacioService.create(apiActor.current(), orgId, consultorioId,
				new EspacioAltaCommand(
						request.name(),
						request.tipo(),
						request.capacidad(),
						request.notes(),
						request.validFrom(),
						request.validUntil()));

		return ResponseEntity
				.created(URI.create("/api/v1/organizations/" + orgId + "/consultorios/"
						+ consultorioId + "/espacios/" + creado.id()))
				.body(EspacioResponse.from(creado));
	}

	@GetMapping
	@Operation(
			operationId = "listEspacios",
			summary = "Espacios de la sede",
			description = """
					Listado paginado de los espacios de la sede. Requiere ser miembro vigente de \
					la organizacion, con cualquier rol, y NO consultorio:manage: es la lectura \
					que necesita un profesional para saber en que box atiende, asi que \
					restringirla al administrador dejaria a la mitad del equipo sin poder \
					consultarla.

					El parametro estado por defecto vale ACTIVO, y eso es lo que hace que un \
					selector de reserva NUNCA ofrezca un espacio dado de baja (RN-M04-002). Los \
					inactivos hay que pedirlos explicitamente, y la pantalla de administracion \
					los necesita para explicar por que un nombre esta tomado o libre.

					El tamano de pagina se acota a 100: pedir mas devuelve ese maximo, no un \
					error.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Pagina de espacios. Contenido vacio si la pagina quedo fuera "
							+ "de rango",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EspacioPageResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "El valor de estado no pertenece al catalogo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada o no hay contexto de trabajo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe, o no se es miembro de esa organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EspacioPageResponse> list(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Parameter(
					description = "Que espacios devolver. ACTIVO son los vigentes, INACTIVO los "
							+ "dados de baja, TODOS ambos.",
					example = "ACTIVO")
			@RequestParam(defaultValue = "ACTIVO") EspacioEstadoFiltro estado,

			@Parameter(description = "Numero de pagina, base cero", example = "0")
			@RequestParam(defaultValue = ApiPaging.PAGINA_POR_DEFECTO) int page,

			@Parameter(description = "Elementos por pagina, acotado a 100", example = "20")
			@RequestParam(defaultValue = ApiPaging.TAMANO_POR_DEFECTO) int size) {

		List<EspacioView> espacios =
				espacioService.list(apiActor.current(), orgId, consultorioId, estado);

		return ResponseEntity.ok(
				EspacioPageResponse.of(espacios, ApiPaging.pagina(page), ApiPaging.tamano(size)));
	}

	@GetMapping("/{espacioId}")
	@Operation(
			operationId = "getEspacio",
			summary = "Datos de un espacio",
			description = """
					Devuelve el espacio con su configuracion y la version que hay que reenviar \
					para editarlo.

					Devuelve tambien los espacios INACTIVOS, con 200 y no con 404: RN-M04-003 \
					exige que los historicos conserven su nombre y su estado. Lo que un espacio \
					inactivo rechaza son las operaciones nuevas, y eso se responde con 409.

					estado y enServicio son dos cosas distintas: el primero es el ciclo de vida \
					administrativo y el segundo dice si el recurso se ofrece AHORA, lo que ademas \
					exige estar dentro de la ventana de vigencia. Un box ACTIVO que entra en \
					servicio el mes que viene tiene estado=ACTIVO y enServicio=false.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "El espacio, activo o dado de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EspacioResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada o no hay contexto de trabajo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "No existe, es de otra sede, o pertenece a otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EspacioResponse> find(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador del espacio", example = "1")
			@PathVariable long espacioId) {

		return ResponseEntity.ok(EspacioResponse.from(
				espacioService.find(apiActor.current(), orgId, consultorioId, espacioId)));
	}

	@PatchMapping(path = "/{espacioId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateEspacio",
			summary = "Edicion de un espacio",
			description = """
					Cambia nombre, tipo, capacidad, observacion y ventana de vigencia \
					(RF-M04-002, RF-M04-007). Los campos omitidos no se tocan; para borrar la \
					observacion se manda cadena vacia, y para dejar el espacio sin fin de \
					vigencia se manda clearValidUntil=true.

					version es obligatoria y se compara antes de mutar: si quedo vieja, la \
					respuesta es 409 concurrent-modification y el cliente recarga. Sin eso, dos \
					ediciones simultaneas se pisan y el segundo en guardar borra el cambio del \
					primero sin que nadie se entere.

					REDUCIR la capacidad pasa por una comprobacion adicional contra la ocupacion \
					ya comprometida, dentro de una transaccion que bloquea la fila del espacio. \
					Si no alcanza, 409 espacio-capacity-below-occupancy, con los dos numeros en \
					el cuerpo para que la pantalla pueda decir a cuanto SI se puede bajar. AVISO \
					AL CLIENTE: mientras no exista el modulo de agenda esa ocupacion es siempre \
					cero y toda reduccion procede; el codigo esta publicado desde ya para que su \
					aparicion no sea un cambio de comportamiento sorpresivo.

					La sede del espacio NO se puede cambiar: mover un box de sede cambiaria el \
					significado de todos los hechos historicos que lo referencian. Un espacio que \
					se muda es uno que se da de baja y otro que se crea.

					Un espacio INACTIVO no se puede editar: 409 espacio-inactive.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Espacio actualizado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EspacioResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, capacidad fuera de rango o ventana de "
							+ "vigencia incoherente",
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
					description = "No existe, es de otra sede, o pertenece a otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Espacio dado de baja (espacio-inactive), nombre repetido "
							+ "(espacio-name-taken), version desactualizada "
							+ "(concurrent-modification), o capacidad por debajo de la ocupacion "
							+ "comprometida (espacio-capacity-below-occupancy)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EspacioResponse> update(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador del espacio", example = "1")
			@PathVariable long espacioId,

			@Valid @RequestBody UpdateEspacioRequest request) {

		EspacioView actualizado = espacioService.update(
				apiActor.current(), orgId, consultorioId, espacioId,
				new EspacioEdicionCommand(
						request.name(),
						request.tipo(),
						request.capacidad(),
						request.notes(),
						request.validFrom(),
						request.validUntil(),
						Boolean.TRUE.equals(request.clearValidUntil()),
						request.version()));

		return ResponseEntity.ok(EspacioResponse.from(actualizado));
	}

	@PostMapping(path = "/{espacioId}/deactivate", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "deactivateEspacio",
			summary = "Baja logica de un espacio",
			description = """
					Da de baja el espacio con motivo obligatorio (RF-M04-006). NO borra nada: el \
					espacio queda INACTIVO, sigue siendo legible por id y conserva su nombre y su \
					estado (RN-M04-003). Deja de ofrecerse para reservas nuevas (RN-M04-002) y \
					libera su nombre para un espacio nuevo de la misma sede.

					No hay reactivacion: ningun RF de M04 la pide, y un recurso que vuelve de una \
					refaccion es una ventana de vigencia nueva, no una baja deshecha. Si lo que \
					se quiere es sacarlo de servicio temporalmente, el camino es editar \
					validUntil, no dar de baja.

					El codigo espacio-has-active-references esta RESERVADO: hoy no lo emite \
					nadie; lo emitira el modulo de agenda cuando existan turnos futuros sobre el \
					espacio. La pantalla de confirmacion de baja deberia estar preparada para \
					mostrarlo.

					Exige consultorio:manage sobre esa sede.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Espacio dado de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EspacioResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo de la baja",
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
					description = "No existe, es de otra sede, o pertenece a otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ya estaba dado de baja (espacio-already-inactive), o tiene "
							+ "referencias vigentes (espacio-has-active-references)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EspacioResponse> deactivate(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador del espacio", example = "1")
			@PathVariable long espacioId,

			@Valid @RequestBody DeactivateEspacioRequest request) {

		EspacioView baja = espacioService.deactivate(
				apiActor.current(), orgId, consultorioId, espacioId, request.reason());

		return ResponseEntity.ok(EspacioResponse.from(baja));
	}

	@GetMapping("/availability")
	@Operation(
			operationId = "checkEspaciosAvailability",
			summary = "Disponibilidad base de los espacios de la sede",
			description = """
					Devuelve los espacios de la sede que estan EN SERVICIO durante toda la \
					ventana pedida (RF-M04-003): activos y con su vigencia cubriendo el \
					intervalo completo. Los dados de baja y los que estan fuera de su ventana \
					operativa NO aparecen, que es el criterio de aceptacion de la etapa.

					LEER ESTO ANTES DE ESCRIBIR LA PANTALLA. Esta consulta responde si el \
					recurso esta en servicio, NO si esta libre de reservas. Los turnos y las \
					inscripciones son de modulos que todavia no existen, asi que \
					lugaresComprometidos es SIEMPRE 0 en esta version del contrato y \
					lugaresDisponibles siempre igual a capacidad. Cuando la agenda exista, esos \
					dos numeros van a cambiar solos y este contrato no cambia. Rotular \
					disponible=true como "el box esta libre" va a ser mentira en cuanto exista la \
					agenda.

					La ventana es semiabierta: hasta es exclusivo. Se exige hasta posterior a \
					desde y un maximo de 31 dias, que es la unidad natural de una agenda; sin \
					tope, una ventana de diez años sobre un centro grande es un scan.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Espacios en servicio para la ventana, ordenados por nombre. "
							+ "Lista vacia si ninguno lo esta",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(
											implementation = EspacioAvailabilityResponse.class)))),
			@ApiResponse(
					responseCode = "400",
					description = "Ventana ausente, invertida, o de mas de 31 dias",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada o no hay contexto de trabajo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe, o no se es miembro de esa organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<EspacioAvailabilityResponse>> availability(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Parameter(
					description = "Inicio de la ventana, instante UTC en formato ISO-8601",
					required = true,
					example = "2026-09-01T13:00:00Z")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,

			@Parameter(
					description = "Fin de la ventana, EXCLUSIVO, instante UTC en formato ISO-8601",
					required = true,
					example = "2026-09-01T14:00:00Z")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta) {

		return ResponseEntity.ok(
				espacioService.disponibilidad(apiActor.current(), orgId, consultorioId, desde, hasta)
						.stream()
						.map(EspacioAvailabilityResponse::from)
						.toList());
	}
}
