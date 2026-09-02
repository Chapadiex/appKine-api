package com.akine.contracting.api;

import com.akine.contracting.api.dto.CreateFinanciadorRequest;
import com.akine.contracting.api.dto.DeactivateContractingRequest;
import com.akine.contracting.api.dto.FinanciadorResponse;
import com.akine.contracting.api.dto.UpdateFinanciadorRequest;
import com.akine.contracting.application.EstadoFiltro;
import com.akine.contracting.application.FinanciadorAltaCommand;
import com.akine.contracting.application.FinanciadorBusqueda;
import com.akine.contracting.application.FinanciadorEdicionCommand;
import com.akine.contracting.application.FinanciadorService;
import com.akine.contracting.application.FinanciadorView;
import com.akine.contracting.domain.TipoFinanciador;
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
import java.util.List;

/**
 * Catalogo de financiadores de la organizacion (M15, AKINE-03.03).
 *
 * <h2>Por que la ruta no cuelga de la sede</h2>
 *
 * <p>Porque el financiador es de la <b>organizacion</b>, no del consultorio: con quien trabaja el
 * centro es una decision de la empresa, y sus coberturas y convenios cruzan sedes. Publicarlo bajo
 * {@code /consultorios/{id}/financiadores} afirmaria en la URL algo que la fila niega y dejaria la
 * misma entidad accesible por tantas URLs como sedes tenga la organizacion. Mismo criterio que
 * {@code /api/v1/personas} en M07.
 *
 * <p>La organizacion no viaja en la ruta: sale del contexto que el filtro de tenant ya revalido.
 * Aceptarla por parametro permitiria escribir otro numero y leer el catalogo ajeno.
 *
 * <h2>Autorizacion</h2>
 *
 * <ul>
 *   <li><b>Leer</b>: contexto de organizacion activo. No hay {@code convenio:read} en la matriz y
 *       esta etapa no lo inventa; el hueco que eso deja esta declarado en la matriz §13.4.</li>
 *   <li><b>Mutar</b>: {@code convenio:manage}, evaluado con la <b>sede</b> del contexto. Que lleve
 *       sede aunque el financiador sea de la organizacion no es obvio: sin ella el evaluador deja
 *       afuera al {@code CONSULTORIO_ADMIN}, a quien la matriz §2 le dice "Si". Matriz §13.3.</li>
 * </ul>
 *
 * <h2>Codigos, y por que no son intercambiables</h2>
 *
 * <ul>
 *   <li><b>200 para un financiador INACTIVO.</b> No 404. Las coberturas y convenios que lo
 *       referencian tienen que seguir resolviendo (RN-M15-003).</li>
 *   <li><b>404 cross-tenant</b>, nunca 403: un 403 confirmaria que ese id existe y bastaria
 *       recorrer numeros para averiguar con que obras sociales trabaja cada centro del SaaS.</li>
 *   <li><b>403</b> sin contexto o sin permiso. <b>Nunca 401</b>: el interceptor del frontend borra
 *       el token ante cualquier 401 y dejaria al usuario en un bucle de login.</li>
 *   <li><b>409</b> para los invariantes: codigo, nombre o CUIT repetidos entre los VIGENTES,
 *       financiador ya inactivo, o version desactualizada. El de concurrencia llega con
 *       {@code type} <b>{@code conflict}</b> y no {@code concurrent-modification}: lo emite el
 *       handler global a partir del {@code OptimisticLockingFailureException} plano, y aca no se
 *       repite la inexactitud que arrastran los contratos de 02.02 y 02.05.</li>
 * </ul>
 *
 * <h2>Lo que esta etapa deliberadamente NO trae</h2>
 *
 * <ul>
 *   <li><b>Reactivar un financiador dado de baja.</b> Ningun RF lo pide, y uno que vuelve es un
 *       alta nueva, no una baja deshecha: modelarlo al reves borraria el rastro de que se dejo de
 *       trabajar con el.</li>
 *   <li><b>El catalogo GLOBAL de financiadores de plataforma.</b> La matriz §3 lo menciona al
 *       definir el valor "Catalogo global"; no existe, y por eso el {@code PLATFORM_ADMIN} no
 *       recibe {@code convenio:manage}. Matriz §13.2 y cabecera de V41.</li>
 *   <li><b>Convenios, aranceles y autorizaciones</b> (M16/M17). Son las etapas siguientes; esta
 *       solo construye el catalogo que ellas referencian.</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/financiadores", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Financiadores y planes",
		description = "Catalogo de financiadores y planes de cobertura de la organizacion (M15)")
public class FinanciadorController {

	private static final Logger log = LoggerFactory.getLogger(FinanciadorController.class);

	private final FinanciadorService financiadorService;
	private final ContractingApiActor apiActor;

	public FinanciadorController(
			FinanciadorService financiadorService, ContractingApiActor apiActor) {

		this.financiadorService = financiadorService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listFinanciadores",
			summary = "Buscar financiadores de la organizacion",
			description = """
					Devuelve los financiadores de la organizacion del contexto, ordenados por \
					nombre.

					q busca por nombre, por codigo y por CUIT. Los comodines que el usuario \
					escriba se escapan: un % tecleado busca un % literal y no todo el catalogo.

					estado filtra por ciclo de vida y por defecto trae solo los ACTIVOS. Con el \
					default, un selector nunca ofrece un financiador dado de baja.

					tipo acota la clasificacion. Ausente trae todos.

					Exige contexto de organizacion activo: sin el, 403.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Financiadores de la organizacion, ordenados por nombre",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = FinanciadorResponse.class)))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion o sin contexto de trabajo activo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<FinanciadorResponse>> list(

			@Parameter(
					description = "Texto a buscar en nombre, codigo y CUIT. Vacio trae todo",
					example = "osde")
			@RequestParam(required = false) String q,

			@Parameter(description = "Filtro por ciclo de vida. Si se omite, ACTIVO")
			@RequestParam(required = false) EstadoFiltro estado,

			@Parameter(description = "Filtro por clasificacion. Si se omite, todos")
			@RequestParam(required = false) TipoFinanciador tipo) {

		List<FinanciadorResponse> financiadores =
				financiadorService.buscar(apiActor.current(), new FinanciadorBusqueda(q, estado, tipo))
						.stream()
						.map(FinanciadorResponse::de)
						.toList();

		return ResponseEntity.ok(financiadores);
	}

	@GetMapping(path = "/{financiadorId}")
	@Operation(
			operationId = "getFinanciador",
			summary = "Ver un financiador",
			description = """
					Devuelve el financiador con 200 aunque este dado de baja: RN-M15-003 exige \
					que los historicos que lo referencian sigan resolviendo, y responder "no \
					existe" seria borrar historia por la puerta de atras.

					Un id de otra organizacion responde 404 igual que uno inexistente. Los dos \
					casos son indistinguibles a proposito.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "El financiador, activo o dado de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = FinanciadorResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo activo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El financiador no existe o es de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<FinanciadorResponse> get(

			@Parameter(description = "Identificador del financiador", example = "31")
			@PathVariable long financiadorId) {

		return ResponseEntity.ok(
				FinanciadorResponse.de(financiadorService.ver(apiActor.current(), financiadorId)));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createFinanciador",
			summary = "Dar de alta un financiador",
			description = """
					Exige convenio:manage sobre la sede del contexto. El financiador queda en la \
					organizacion, no en esa sede.

					El codigo es unico entre los financiadores VIGENTES de la organizacion y no \
					se puede cambiar despues: es lo que las coberturas y los convenios guardan. \
					El codigo de uno dado de baja SI se puede reusar. El nombre tambien es unico \
					entre los vigentes.

					El CUIT se normaliza a 11 digitos: los guiones y los puntos se descartan \
					antes de guardar y de comparar. Es unico entre los vigentes cuando esta \
					presente, y varios financiadores SIN CUIT conviven sin chocar.

					No lleva Idempotency-Key y es deliberado: un financiador no consume cupo de \
					ningun plan, asi que lo unico que un reintento podria producir es una fila \
					duplicada, y contra eso el unique de codigo es una garantia mas fuerte que \
					una clave que depende de que el cliente la mande bien.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Financiador creado. La cabecera Location apunta al recurso",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = FinanciadorResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos o faltantes, o un CUIT que no queda en 11 digitos",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo activo o sin convenio:manage",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Codigo (financiador-codigo-taken), nombre "
							+ "(financiador-nombre-taken) o CUIT (financiador-cuit-taken) repetido "
							+ "entre los financiadores vigentes de la organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<FinanciadorResponse> create(
			@Valid @RequestBody CreateFinanciadorRequest request) {

		// El codigo si se loguea y el nombre no hace falta: el codigo es lo que permite
		// correlacionar con la fila de auditoria sin volcar el cuerpo entero al log. El CUIT NO se
		// loguea: es identidad fiscal de un tercero.
		log.info("Alta de financiador solicitada: codigo={}", request.codigo());

		FinanciadorView creado = financiadorService.crear(
				apiActor.current(),
				new FinanciadorAltaCommand(
						request.codigo(),
						request.nombre(),
						request.tipo(),
						request.cuit(),
						request.emailContacto(),
						request.telefonoContacto(),
						request.observaciones()));

		return ResponseEntity
				.created(URI.create("/api/v1/financiadores/" + creado.id()))
				.body(FinanciadorResponse.de(creado));
	}

	@PutMapping(path = "/{financiadorId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateFinanciador",
			summary = "Editar un financiador",
			description = """
					Exige convenio:manage sobre la sede del contexto.

					El codigo NO se puede cambiar y por eso no esta en el cuerpo: es la clave con \
					la que las coberturas y los convenios ya firmados lo referencian, y cambiarlo \
					reescribiria el significado de filas que no participan de esta llamada. \
					Renombrar es cambiar nombre.

					Los campos que llegan en null NO se tocan. Es edicion parcial declarada: \
					obligar a mandar el recurso entero forzaria a la pantalla a releerlo antes de \
					cada guardado para no pisar lo que otro cambio en el medio, y expectedVersion \
					ya resuelve ese problema mejor.

					Un financiador dado de baja no admite ediciones: 409 financiador-inactivo. \
					Sus datos historicos siguen siendo consultables.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Financiador actualizado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = FinanciadorResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo activo o sin convenio:manage",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El financiador no existe o es de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Nombre o CUIT repetido, financiador dado de baja "
							+ "(financiador-inactivo), o version desactualizada (conflict)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<FinanciadorResponse> update(

			@Parameter(description = "Identificador del financiador", example = "31")
			@PathVariable long financiadorId,

			@Valid @RequestBody UpdateFinanciadorRequest request) {

		log.info("Edicion de financiador solicitada: financiadorId={}", financiadorId);

		FinanciadorView actualizado = financiadorService.editar(
				apiActor.current(),
				financiadorId,
				new FinanciadorEdicionCommand(
						request.nombre(),
						request.tipo(),
						request.cuit(),
						request.emailContacto(),
						request.telefonoContacto(),
						request.observaciones(),
						request.expectedVersion()));

		return ResponseEntity.ok(FinanciadorResponse.de(actualizado));
	}

	// PRODUCES EXPLICITO. El 204 no lleva cuerpo, asi que lo unico que esta operacion declara
	// producir es el problem+json de sus errores, y eso es lo que el cliente generado manda en
	// Accept. Sin declarar tambien application/json, el produces de clase lo rechaza con 406 antes
	// de entrar al metodo. Es el mismo defecto que rompio la activacion de cuenta.
	@DeleteMapping(path = "/{financiadorId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE })
	@Operation(
			operationId = "deactivateFinanciador",
			summary = "Dar de baja un financiador",
			description = """
					Baja LOGICA con motivo obligatorio. Exige convenio:manage sobre la sede del \
					contexto.

					LA BAJA NO CASCADEA, y esto es lo mas importante de la operacion. Los planes \
					del financiador conservan sus filas y las coberturas y convenios ya firmados \
					SIGUEN RESOLVIENDO: RN-M15-003 prohibe eliminar historicos. Lo unico que la \
					baja impide es CREAR planes nuevos bajo el —409 financiador-inactivo— y que \
					sus planes se ofrezcan para selecciones nuevas.

					Tener planes activos NO bloquea la baja: obligar a darlos de baja uno por uno \
					para poder dejar de trabajar con una obra social es trabajo burocratico sin \
					ninguna garantia a cambio. Lo que si se hace es contarlos y dejar el numero \
					en la auditoria.

					El motivo es obligatorio: sin el, la auditoria no responde por que seis meses \
					despues.

					No hay reactivacion. Un financiador que vuelve es un alta nueva, no una baja \
					deshecha.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Financiador dado de baja"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo de la baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo activo o sin convenio:manage",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El financiador no existe o es de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El financiador ya estaba dado de baja "
							+ "(financiador-already-inactive)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivate(

			@Parameter(description = "Identificador del financiador", example = "31")
			@PathVariable long financiadorId,

			@Valid @RequestBody DeactivateContractingRequest request) {

		// El motivo NO se loguea: es texto libre que un administrador escribe y puede nombrar a un
		// tercero. Queda en la fila y en la auditoria, que es donde tiene control de acceso.
		log.info("Baja de financiador solicitada: financiadorId={}", financiadorId);

		financiadorService.darDeBaja(apiActor.current(), financiadorId, request.reason());

		return ResponseEntity.noContent().build();
	}
}
