package com.akine.resource.api;

import com.akine.resource.api.dto.CatalogoConceptoPageResponse;
import com.akine.resource.api.dto.CatalogoConceptoResponse;
import com.akine.resource.api.dto.CreateCatalogoConceptoRequest;
import com.akine.resource.api.dto.CreateVigenciaRequest;
import com.akine.resource.api.dto.DeactivateCatalogoRequest;
import com.akine.resource.api.dto.UpdateCatalogoConceptoRequest;
import com.akine.resource.application.CatalogoAltaCommand;
import com.akine.resource.application.CatalogoBusqueda;
import com.akine.resource.application.CatalogoConceptoView;
import com.akine.resource.application.CatalogoEdicionCommand;
import com.akine.resource.application.CatalogoService;
import com.akine.resource.application.VigenciaAltaCommand;
import com.akine.resource.domain.CatalogoAlcanceFiltro;
import com.akine.resource.domain.CatalogoEstadoFiltro;
import com.akine.resource.domain.CatalogoTipo;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Catalogo clinico: especialidades, practicas, nomencladores y vigencias (M06).
 *
 * <h2>Por que la ruta NO cuelga de la organizacion</h2>
 *
 * <p>Todo el resto de la API de negocio vive bajo {@code /api/v1/organizations/{orgId}/...}, y
 * aca deliberadamente no. El motivo es la mitad de la etapa: <b>un concepto global no pertenece
 * a ninguna organizacion</b>. Publicar la especialidad "Kinesiologia" —que es de plataforma y la
 * ven los cientos de tenants del SaaS— bajo
 * {@code /organizations/7/especialidades/3} seria afirmar en la URL algo que la fila niega, y
 * dejaria la misma entidad accesible por tantas URLs como tenants existan.
 *
 * <p>El tenant sale del <b>contexto validado</b> del request, como en {@code /api/v1/me}: es el
 * unico que {@code TenantContextFilter} revalido contra la base en esta llamada. Un cliente no
 * puede pedir el catalogo de otra organizacion porque no hay ningun lugar donde nombrarla.
 *
 * <h2>Un solo arbol de endpoints para los tres conceptos</h2>
 *
 * <p>{@code tipo} es un segmento de ruta —{@code especialidades}, {@code practicas},
 * {@code nomencladores}— y no tres controllers. Las cinco operaciones son identicas en los tres
 * casos: triplicarlas habria triplicado el contrato, el cliente generado y esta documentacion
 * sin agregar ninguna capacidad. Lo unico que varia es {@code especialidadId}, obligatorio al
 * crear una practica.
 *
 * <p>Del mismo modo, <b>el alcance viaja en el cuerpo y no en la ruta</b>: no hay
 * {@code /platform/catalogos/...} separado. La ruta dice QUE se toca, el cuerpo dice PARA QUIEN,
 * y quien puede hacerlo lo decide el servidor contra el rol del actor — que es una verificacion
 * que hay que hacer igual, tenga la URL el prefijo que tenga.
 *
 * <h2>Autorizacion</h2>
 *
 * <ul>
 *   <li><b>Leer</b>: cualquier membership vigente en la organizacion del contexto. Devuelve el
 *       catalogo de plataforma MAS el propio del centro.</li>
 *   <li><b>Mutar un concepto CONTEXTUAL</b>: exige {@code consultorio:manage} sobre la sede del
 *       contexto. Pasan {@code ORG_ADMIN} y {@code CONSULTORIO_ADMIN}.</li>
 *   <li><b>Mutar un concepto GLOBAL</b>: exige rol de plataforma. Un administrador de tenant lo
 *       VE y no lo puede tocar: recibe 403, y su camino es
 *       {@code POST /api/v1/catalogo-solicitudes}.</li>
 *   <li><b>La administracion de plataforma no ve ni muta conceptos de un tenant.</b> No es un
 *       olvido: el catalogo propio de un centro es informacion comercial suya y no existe
 *       ninguna operacion de rescate que exija tocarlo.</li>
 * </ul>
 *
 * <p>Los dos codigos de lectura y escritura que M06 necesita —{@code catalogo:read} y
 * {@code catalogo:manage}— <b>no existen todavia en el catalogo de permisos</b>. Esta etapa no
 * los inventa: quedan propuestos en {@code docs/seguridad/matriz-permisos-minima.md} §11 y
 * mientras tanto se autoriza como se describe arriba. <b>Los codigos HTTP no van a cambiar
 * cuando se aprueben</b>; lo que puede cambiar es que un {@code PACIENTE}, que hoy lee el
 * catalogo por pertenencia, pase a recibir 403.
 *
 * <h2>Codigos, y por que no son intercambiables</h2>
 *
 * <ul>
 *   <li><b>404</b> para un concepto de otro tenant o inexistente. Un 403 confirmaria que ese id
 *       existe y bastaria recorrer numeros para averiguar que practicas propias tiene cada
 *       centro del SaaS.</li>
 *   <li><b>403</b> cuando el actor ve el concepto y le falta el permiso, o cuando no eligio
 *       contexto de trabajo. <b>Nunca 401</b>: el interceptor del frontend borra el token ante
 *       cualquier 401 y dejaria al usuario en un bucle de login.</li>
 *   <li><b>409</b> para los invariantes de estado: codigo o nombre repetidos, concepto ya
 *       inactivo, version desactualizada, vigencias solapadas, alcance incompatible o
 *       referencias vigentes.</li>
 * </ul>
 *
 * <p>Un concepto INACTIVO se lee con <b>200</b>, no con 404: RN-M06-001 y RN-M06-002 exigen que
 * los catalogos historicos sigan resolviendo, y responder "no existe" seria borrar historia por
 * la puerta de atras.
 *
 * <h2>Lo que esta etapa deliberadamente NO trae</h2>
 *
 * <ul>
 *   <li><b>Reactivar un concepto dado de baja.</b> Ningun RF de M06 lo pide, y un concepto que
 *       vuelve es una vigencia nueva —no una baja deshecha—: modelarlo al reves borraria el
 *       rastro de que dejo de ofrecerse.</li>
 *   <li><b>Importacion masiva de un nomenclador.</b> El caso borde "importacion duplicada" queda
 *       cubierto por el unique de codigo, pero el endpoint de carga masiva no existe.</li>
 *   <li><b>Aranceles.</b> {@code valorReferencia} es lo que el nomenclador publica; lo que un
 *       centro cobra lo fija el convenio (M16).</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/catalogos", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Catalogo clinico",
		description = "Especialidades, practicas, nomencladores y sus vigencias (M06)")
public class CatalogoController {

	private static final Logger log = LoggerFactory.getLogger(CatalogoController.class);

	private final CatalogoService catalogoService;
	private final ApiActor apiActor;

	public CatalogoController(CatalogoService catalogoService, ApiActor apiActor) {
		this.catalogoService = catalogoService;
		this.apiActor = apiActor;
	}

	// =================================================================================
	// Conceptos
	// =================================================================================

	@PostMapping(path = "/{tipo}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createCatalogoConcepto",
			summary = "Alta de un concepto del catalogo",
			description = """
					Crea una especialidad, una practica o un nomenclador (RF-M06-001..003).

					El campo alcance del cuerpo decide el duenio y quien puede crearlo: \
					ORGANIZACION exige consultorio:manage sobre la sede del contexto —pasan \
					ORG_ADMIN y CONSULTORIO_ADMIN—; GLOBAL exige rol de plataforma. Si se omite, \
					ORGANIZACION.

					especialidadId es obligatorio al crear una practica. Una practica GLOBAL \
					solo puede colgar de una especialidad GLOBAL: al reves seria dejar que un \
					centro decida el destino de un concepto que ven todos los demas, y responde \
					409 catalogo-scope-mismatch.

					El codigo es unico entre los conceptos VIGENTES del mismo duenio, y el \
					nombre tambien —comparado sin distinguir mayusculas ni acentos—. El codigo \
					de uno dado de baja SI se puede reusar.

					No lleva Idempotency-Key y es deliberado: un concepto no consume cupo de \
					ningun plan, asi que lo unico que un reintento podria producir es una fila \
					duplicada, y contra eso el unique de codigo es una garantia mas fuerte que \
					una clave que depende de que el cliente la mande bien. El reintento responde \
					409 y no crea nada; despues de un timeout de red hay que releer el listado.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Concepto creado. La cabecera Location apunta al recurso",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CatalogoConceptoResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Tipo de ruta desconocido, campos invalidos, falta "
							+ "especialidadId en una practica, o ventana de vigencia incoherente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, sin consultorio:manage para un "
							+ "concepto contextual, o sin rol de plataforma para uno global",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La especialidad referenciada no existe o es de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Codigo repetido (catalogo-code-taken), nombre repetido "
							+ "(catalogo-name-taken), especialidad dada de baja "
							+ "(catalogo-reference-inactive), o alcance incompatible "
							+ "(catalogo-scope-mismatch)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CatalogoConceptoResponse> create(
			@Parameter(
					description = "Coleccion del catalogo",
					example = "practicas",
					schema = @Schema(allowableValues = {
							"especialidades", "practicas", "nomencladores"}))
			@PathVariable String tipo,

			@Valid @RequestBody CreateCatalogoConceptoRequest request) {

		CatalogoTipo concepto = CatalogoTipo.desdeRuta(tipo);

		// El nombre del concepto no se loguea: es dato comercial del cliente cuando el concepto
		// es contextual. El tipo y el alcance si, que es lo que permite correlacionar con la
		// fila de auditoria.
		log.info("Alta de concepto de catalogo solicitada: tipo={} alcance={}",
				concepto, request.alcance());

		CatalogoConceptoView creado = catalogoService.crear(
				apiActor.current(),
				concepto,
				new CatalogoAltaCommand(
						request.alcance(),
						request.codigo(),
						request.name(),
						request.descripcion(),
						request.especialidadId(),
						null,
						request.validFrom(),
						request.validUntil()));

		return ResponseEntity
				.created(URI.create("/api/v1/catalogos/" + concepto.segmento() + "/" + creado.id()))
				.body(CatalogoConceptoResponse.from(creado));
	}

	@GetMapping("/{tipo}")
	@Operation(
			operationId = "searchCatalogo",
			summary = "Busqueda incremental en el catalogo",
			description = """
					Devuelve los conceptos que el tenant del contexto puede ver: los del \
					catalogo de PLATAFORMA mas los PROPIOS, en la misma lista (RF-M06-004). Es \
					la consulta de todo selector clinico y economico.

					El texto q se compara contra el nombre Y el codigo, sin distinguir \
					mayusculas ni acentos —lo resuelve la collation de la base, no una columna \
					normalizada—. Los comodines del cliente se escapan, asi que tipear un % no \
					devuelve el catalogo entero.

					estado vale ACTIVO por defecto, y eso es lo que hace que un selector NUNCA \
					ofrezca un concepto dado de baja. Los inactivos hay que pedirlos \
					explicitamente, y la pantalla de administracion los necesita para explicar \
					por que un codigo esta tomado o libre.

					alcance vale TODOS por defecto, que es lo que un formulario necesita: al \
					elegir una practica no importa si es de plataforma o del centro. GLOBAL y \
					ORGANIZACION existen para la pantalla de administracion, que si tiene que \
					separarlas porque lo global no lo puede editar el tenant.

					especialidadId solo aplica a practicas y se ignora en los otros tipos.

					El tamano de pagina se acota a 100: pedir mas devuelve ese maximo, no un \
					error.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Pagina de conceptos. Contenido vacio si la pagina quedo fuera "
							+ "de rango",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(
									implementation = CatalogoConceptoPageResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Tipo de ruta desconocido, o valor de estado o alcance fuera "
							+ "del catalogo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada o no hay contexto de trabajo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CatalogoConceptoPageResponse> search(
			@Parameter(description = "Coleccion del catalogo", example = "practicas",
					schema = @Schema(allowableValues = {
							"especialidades", "practicas", "nomencladores"}))
			@PathVariable String tipo,

			@Parameter(description = "Texto a buscar en el nombre o en el codigo",
					example = "kinesio")
			@RequestParam(required = false) String q,

			@Parameter(description = "Que conceptos devolver segun su ciclo de vida",
					example = "ACTIVO")
			@RequestParam(defaultValue = "ACTIVO") CatalogoEstadoFiltro estado,

			@Parameter(description = "Que conceptos devolver segun su duenio", example = "TODOS")
			@RequestParam(defaultValue = "TODOS") CatalogoAlcanceFiltro alcance,

			@Parameter(description = "Solo practicas de esa especialidad. Ignorado en los otros "
					+ "tipos", example = "1")
			@RequestParam(required = false) Long especialidadId,

			@Parameter(description = "Numero de pagina, base cero", example = "0")
			@RequestParam(defaultValue = ApiPaging.PAGINA_POR_DEFECTO) int page,

			@Parameter(description = "Elementos por pagina, acotado a 100", example = "20")
			@RequestParam(defaultValue = ApiPaging.TAMANO_POR_DEFECTO) int size) {

		List<CatalogoConceptoView> encontrados = catalogoService.buscar(
				apiActor.current(),
				CatalogoTipo.desdeRuta(tipo),
				new CatalogoBusqueda(q, estado, alcance, especialidadId));

		return ResponseEntity.ok(CatalogoConceptoPageResponse.of(
				encontrados, ApiPaging.pagina(page), ApiPaging.tamano(size)));
	}

	@GetMapping("/{tipo}/{conceptoId}")
	@Operation(
			operationId = "getCatalogoConcepto",
			summary = "Datos de un concepto del catalogo",
			description = """
					Devuelve el concepto con su alcance, su vigencia y la version que hay que \
					reenviar para editarlo.

					Devuelve tambien los conceptos INACTIVOS, con 200 y no con 404: RN-M06-001 y \
					RN-M06-002 exigen que los catalogos usados historicamente sigan resolviendo \
					con su nombre y su estado. Lo que un concepto inactivo rechaza son las \
					operaciones nuevas, y eso se responde con 409.

					estado y vigente son dos cosas distintas: el primero es el ciclo de vida \
					administrativo y el segundo dice si el concepto se puede ELEGIR ahora, lo \
					que ademas exige estar dentro de la ventana de vigencia. Una practica ACTIVA \
					que entra en vigor el mes que viene tiene estado=ACTIVO y vigente=false.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "El concepto, activo o dado de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CatalogoConceptoResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada o no hay contexto de trabajo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "No existe, o es un concepto contextual de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CatalogoConceptoResponse> find(
			@Parameter(description = "Coleccion del catalogo", example = "practicas",
					schema = @Schema(allowableValues = {
							"especialidades", "practicas", "nomencladores"}))
			@PathVariable String tipo,

			@Parameter(description = "Identificador del concepto", example = "1")
			@PathVariable long conceptoId) {

		return ResponseEntity.ok(CatalogoConceptoResponse.from(
				catalogoService.find(
						apiActor.current(), CatalogoTipo.desdeRuta(tipo), conceptoId)));
	}

	@PatchMapping(path = "/{tipo}/{conceptoId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateCatalogoConcepto",
			summary = "Edicion de un concepto del catalogo",
			description = """
					Cambia nombre, descripcion y ventana de vigencia. Los campos omitidos no se \
					tocan; para borrar la descripcion se manda cadena vacia, y para dejar el \
					concepto sin fin de vigencia se manda clearValidUntil=true.

					El CODIGO no se puede cambiar: es la clave estable con la que los convenios \
					y las sesiones referencian el concepto, y mutarlo haria que un historico de \
					2024 apunte a algo que hoy significa otra cosa. Renombrar es cambiar name.

					El ALCANCE tampoco: promover un concepto propio a global lo haria visible \
					para todos los tenants sin que ninguno lo haya pedido. El camino es \
					POST /api/v1/catalogo-solicitudes.

					version es obligatoria y se compara antes de mutar: si quedo vieja, 409 \
					concurrent-modification y el cliente recarga. Sin eso, dos ediciones \
					simultaneas se pisan y el segundo en guardar borra el cambio del primero.

					Un concepto INACTIVO no se puede editar: 409 catalogo-inactive.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Concepto actualizado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CatalogoConceptoResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos o ventana de vigencia incoherente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto, sin consultorio:manage sobre un concepto "
							+ "propio, o sin rol de plataforma sobre uno global",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "No existe, o es un concepto contextual de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Concepto dado de baja (catalogo-inactive), nombre repetido "
							+ "(catalogo-name-taken), o version desactualizada "
							+ "(concurrent-modification)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CatalogoConceptoResponse> update(
			@Parameter(description = "Coleccion del catalogo", example = "practicas",
					schema = @Schema(allowableValues = {
							"especialidades", "practicas", "nomencladores"}))
			@PathVariable String tipo,

			@Parameter(description = "Identificador del concepto", example = "1")
			@PathVariable long conceptoId,

			@Valid @RequestBody UpdateCatalogoConceptoRequest request) {

		CatalogoConceptoView actualizado = catalogoService.editar(
				apiActor.current(),
				CatalogoTipo.desdeRuta(tipo),
				conceptoId,
				new CatalogoEdicionCommand(
						request.name(),
						request.descripcion(),
						request.validFrom(),
						request.validUntil(),
						Boolean.TRUE.equals(request.clearValidUntil()),
						request.valorReferencia(),
						request.version()));

		return ResponseEntity.ok(CatalogoConceptoResponse.from(actualizado));
	}

	@PostMapping(path = "/{tipo}/{conceptoId}/deactivate",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "deactivateCatalogoConcepto",
			summary = "Baja logica de un concepto del catalogo",
			description = """
					Da de baja el concepto con motivo obligatorio (RN-M06-001). NO borra nada: \
					queda INACTIVO, sigue siendo legible por id y conserva su codigo y su \
					nombre, asi que toda sesion, convenio o presentacion que lo referencie sigue \
					resolviendo (RN-M06-002). Deja de ofrecerse para selecciones nuevas y libera \
					su codigo y su nombre para un concepto nuevo del mismo duenio.

					No hay reactivacion: ningun RF de M06 la pide, y un concepto que vuelve es \
					una vigencia nueva, no una baja deshecha. Si lo que se quiere es sacarlo de \
					circulacion temporalmente, el camino es editar validUntil.

					Una ESPECIALIDAD con practicas vigentes colgando NO se puede dar de baja: \
					409 catalogo-has-active-references, con el conteo en el cuerpo. Un \
					NOMENCLADOR con vigencias abiertas, tampoco. Ojo con la distincion: estar \
					usado HISTORICAMENTE no bloquea la baja —eso es justamente lo que RN-M06-001 \
					pide que sobreviva—; lo que bloquea es dejar colgando algo que todavia se \
					ofrece.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Concepto dado de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CatalogoConceptoResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo de la baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto, sin consultorio:manage sobre un concepto "
							+ "propio, o sin rol de plataforma sobre uno global",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "No existe, o es un concepto contextual de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ya estaba dado de baja (catalogo-already-inactive), o tiene "
							+ "conceptos vigentes colgando (catalogo-has-active-references)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CatalogoConceptoResponse> deactivate(
			@Parameter(description = "Coleccion del catalogo", example = "practicas",
					schema = @Schema(allowableValues = {
							"especialidades", "practicas", "nomencladores"}))
			@PathVariable String tipo,

			@Parameter(description = "Identificador del concepto", example = "1")
			@PathVariable long conceptoId,

			@Valid @RequestBody DeactivateCatalogoRequest request) {

		CatalogoConceptoView baja = catalogoService.darDeBaja(
				apiActor.current(), CatalogoTipo.desdeRuta(tipo), conceptoId, request.reason());

		return ResponseEntity.ok(CatalogoConceptoResponse.from(baja));
	}

	// =================================================================================
	// Vigencias de un nomenclador
	// =================================================================================

	@PostMapping(path = "/nomencladores/{nomencladorId}/items",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createNomencladorVigencia",
			summary = "Nueva vigencia de un codigo del nomenclador",
			description = """
					Define que dice un codigo del nomenclador durante una ventana de tiempo, y \
					con que valor de referencia (RF-M06-003, RN-M06-003).

					LEER ESTO ANTES DE ESCRIBIR LA PANTALLA. Actualizar un codigo NO es editar \
					su vigencia anterior: es cerrar la vieja —PATCH con validUntil— y crear una \
					nueva desde ese mismo instante. Editar la vieja borraria lo que ese codigo \
					decia cuando se uso, y un convenio de 2024 dejaria de poder explicarse. Como \
					el fin de vigencia es EXCLUSIVO, cerrar en T y abrir en T no se solapa.

					Dos vigencias del mismo codigo que se pisan responden 409 \
					nomenclador-vigencia-overlap, con la ventana en conflicto en el cuerpo para \
					que la pantalla pueda decir contra que choco.

					La vigencia HEREDA el duenio del nomenclador y no lo puede elegir: por eso \
					no hay campo alcance. Un nomenclador GLOBAL solo puede codificar practicas \
					GLOBALES (409 catalogo-scope-mismatch).

					Exige poder mutar el nomenclador: consultorio:manage si es del tenant, rol \
					de plataforma si es global.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Vigencia creada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CatalogoConceptoResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, valor negativo o ventana incoherente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto, o sin permiso para mutar ese nomenclador",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El nomenclador o la practica no existen, o son de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Vigencias solapadas (nomenclador-vigencia-overlap), "
							+ "nomenclador o practica dados de baja "
							+ "(catalogo-reference-inactive), o alcance incompatible "
							+ "(catalogo-scope-mismatch)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CatalogoConceptoResponse> createVigencia(
			@Parameter(description = "Identificador del nomenclador", example = "1")
			@PathVariable long nomencladorId,

			@Valid @RequestBody CreateVigenciaRequest request) {

		CatalogoConceptoView creada = catalogoService.crearVigencia(
				apiActor.current(),
				nomencladorId,
				new VigenciaAltaCommand(
						request.practicaId(),
						request.codigo(),
						request.name(),
						request.descripcion(),
						request.valorReferencia(),
						request.validFrom(),
						request.validUntil()));

		return ResponseEntity
				.created(URI.create("/api/v1/catalogos/nomencladores/" + nomencladorId
						+ "/items/" + creada.id()))
				.body(CatalogoConceptoResponse.from(creada));
	}

	@GetMapping("/nomencladores/{nomencladorId}/items")
	@Operation(
			operationId = "listNomencladorVigencias",
			summary = "Vigencias de un nomenclador",
			description = """
					Devuelve las vigencias de los codigos del nomenclador, ordenadas por codigo \
					y, dentro de cada codigo, de la mas nueva a la mas vieja: es el orden en el \
					que la pantalla las necesita, porque lo primero que se consulta es que rige \
					hoy y despues que regia antes.

					estado vale ACTIVO por defecto. practicaId y codigo son filtros opcionales: \
					codigo es exacto —no una busqueda— porque un codigo de nomenclador se \
					conoce entero o no se conoce.

					Esta consulta es el historial completo. La resolucion puntual —que decia \
					este codigo el dia D— la usan los modulos de convenios y sesiones por el \
					puerto interno del modulo, no por HTTP.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Vigencias del nomenclador. Lista vacia si no hay ninguna",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(schema = @Schema(
									implementation = CatalogoConceptoResponse.class)))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada o no hay contexto de trabajo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El nomenclador no existe o es de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<CatalogoConceptoResponse>> listVigencias(
			@Parameter(description = "Identificador del nomenclador", example = "1")
			@PathVariable long nomencladorId,

			@Parameter(description = "Que vigencias devolver segun su ciclo de vida",
					example = "ACTIVO")
			@RequestParam(defaultValue = "ACTIVO") CatalogoEstadoFiltro estado,

			@Parameter(description = "Solo las vigencias que codifican esa practica",
					example = "1")
			@RequestParam(required = false) Long practicaId,

			@Parameter(description = "Solo las vigencias de ese codigo exacto",
					example = "27.01.01")
			@RequestParam(required = false) String codigo) {

		return ResponseEntity.ok(catalogoService.listarVigencias(
						apiActor.current(),
						nomencladorId,
						new CatalogoBusqueda(null, estado, null, practicaId),
						codigo)
				.stream()
				.map(CatalogoConceptoResponse::from)
				.toList());
	}

	@PostMapping(path = "/nomencladores/{nomencladorId}/items/{itemId}/deactivate",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "deactivateNomencladorVigencia",
			summary = "Baja logica de una vigencia",
			description = """
					Da de baja una vigencia cargada por error, con motivo obligatorio.

					NO es el camino para cerrar una vigencia que termino: eso se hace poniendole \
					validUntil con PATCH. Dar de baja la saca de las selecciones nuevas pero \
					deja intacto lo que ya resolvio, porque la resolucion historica de un codigo \
					no filtra por estado —justamente para que un convenio viejo siga diciendo lo \
					que decia—.

					Exige poder mutar el nomenclador al que pertenece.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Vigencia dada de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CatalogoConceptoResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo de la baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto, o sin permiso para mutar ese nomenclador",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El nomenclador o la vigencia no existen, son de otro tenant, "
							+ "o la vigencia pertenece a otro nomenclador",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ya estaba dada de baja (catalogo-already-inactive)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CatalogoConceptoResponse> deactivateVigencia(
			@Parameter(description = "Identificador del nomenclador", example = "1")
			@PathVariable long nomencladorId,

			@Parameter(description = "Identificador de la vigencia", example = "1")
			@PathVariable long itemId,

			@Valid @RequestBody DeactivateCatalogoRequest request) {

		CatalogoConceptoView baja = catalogoService.darDeBajaVigencia(
				apiActor.current(), nomencladorId, itemId, request.reason());

		return ResponseEntity.ok(CatalogoConceptoResponse.from(baja));
	}
}
