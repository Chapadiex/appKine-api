package com.akine.resource.api;

import com.akine.resource.api.dto.CreateMedicionDefinicionRequest;
import com.akine.resource.api.dto.DeactivateMedicionDefinicionRequest;
import com.akine.resource.api.dto.MedicionDefinicionPageResponse;
import com.akine.resource.api.dto.MedicionDefinicionResponse;
import com.akine.resource.api.dto.UpdateMedicionDefinicionRequest;
import com.akine.resource.application.CatalogoBusqueda;
import com.akine.resource.application.MedicionDefinicionAltaCommand;
import com.akine.resource.application.MedicionDefinicionEdicionCommand;
import com.akine.resource.application.MedicionDefinicionService;
import com.akine.resource.application.MedicionDefinicionView;
import com.akine.resource.domain.CatalogoAlcanceFiltro;
import com.akine.resource.domain.CatalogoEstadoFiltro;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * Catalogo de definiciones de medida del examen fisico (M06, al servicio de RF-M14-004).
 *
 * <h2>Por que este catalogo esta en {@code resource} y no en {@code encounter}</h2>
 *
 * <p>La intuicion lo pone en la evaluacion y no es ahi: una <b>definicion</b> de medida es un
 * catalogo —vive mas que cualquier sesion, la comparten todas las especialidades y la plataforma
 * siembra las universales—. {@code resource} ya es el duenio de {@code especialidad},
 * {@code practica} y los nomencladores; meter el sexto catalogo en otro modulo daria dos duenios
 * de la misma clase de cosa.
 *
 * <h2>La ruta NO cuelga de la organizacion</h2>
 *
 * <p>Misma razon que en {@code CatalogoController}: una definicion GLOBAL no pertenece a ninguna
 * organizacion, y publicarla bajo {@code /organizations/7/...} afirmaria en la URL algo que la
 * fila niega. El tenant sale del <b>contexto validado</b> del request, que es lo unico que
 * {@code TenantContextFilter} revalido contra la base, y por eso un cliente no puede pedir el
 * catalogo de otro: no hay donde nombrarlo.
 *
 * <h2>Autorizacion</h2>
 *
 * <ul>
 *   <li><b>Leer</b>: cualquier membership vigente. Devuelve lo global MAS lo propio del centro.</li>
 *   <li><b>Mutar una definicion propia</b>: {@code consultorio:manage} sobre la sede del
 *       contexto.</li>
 *   <li><b>Mutar una global</b>: rol de plataforma. Un administrador de tenant la VE y no la
 *       toca.</li>
 * </ul>
 *
 * <p>Sin permisos nuevos: son los mismos de M06 que 02.05 fijo, con la misma politica interina
 * —{@code catalogo:read} y {@code catalogo:manage} siguen propuestos en
 * {@code docs/seguridad/matriz-permisos-minima.md} §11—. <b>Los codigos HTTP no cambian cuando se
 * aprueben.</b>
 *
 * <h2>Codigos</h2>
 *
 * <ul>
 *   <li><b>404</b> para una definicion inexistente o de otro tenant. Un 403 confirmaria que ese id
 *       existe y bastaria recorrer numeros para censar que tests propios tiene cargados cada
 *       centro del SaaS, que es informacion comercial de sus clientes (ADR-0018).</li>
 *   <li><b>403</b> cuando el actor la ve y le falta el permiso, o cuando no eligio contexto.
 *       <b>Nunca 401</b>: el interceptor del frontend borra el token ante cualquier 401 y dejaria
 *       al usuario en un bucle de login.</li>
 *   <li><b>409</b> para los invariantes de estado: codigo o nombre repetidos, definicion ya
 *       inactiva, version desactualizada.</li>
 * </ul>
 *
 * <h2>Lo que la baja hace y lo que NO</h2>
 *
 * <p>Dar de baja un test discontinuado deja <b>intactas</b> las mediciones que ya lo usaban: se
 * siguen leyendo y se siguen comparando. Lo unico que se impide es registrar nuevas. La baja
 * <b>no cascadea</b>, que es exactamente la regla que 02.06 fijo para la baja de un servicio.
 */
@RestController
@RequestMapping(path = "/api/v1/mediciones/definiciones",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Definiciones de medicion",
		description = "Catalogo de que se mide en el examen fisico, en que unidad y con que "
				+ "rango (M06, RF-M14-004)")
public class MedicionDefinicionController {

	private static final Logger log =
			LoggerFactory.getLogger(MedicionDefinicionController.class);

	private final MedicionDefinicionService definicionService;
	private final ApiActor apiActor;

	public MedicionDefinicionController(
			MedicionDefinicionService definicionService, ApiActor apiActor) {

		this.definicionService = definicionService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "searchMedicionDefiniciones",
			summary = "Listado del catalogo de medidas",
			description = """
					Devuelve las definiciones que el tenant del contexto puede ver: las de \
					PLATAFORMA mas las PROPIAS, en la misma lista. Es la consulta del selector \
					de tests del examen fisico.

					El texto q se compara contra el nombre Y el codigo, sin distinguir \
					mayusculas ni acentos —lo resuelve la collation de la base—. Los comodines \
					del cliente se escapan, asi que tipear un % no devuelve el catalogo entero.

					estado vale ACTIVO por defecto, y eso es lo que hace que un formulario de \
					examen NUNCA ofrezca un test dado de baja sin que el frontend tenga que \
					acordarse. Los inactivos hay que pedirlos explicitamente, y la pantalla de \
					administracion los necesita para explicar por que un codigo esta tomado.

					alcance vale TODOS por defecto: al elegir una medida no importa si la sembro \
					la plataforma o el centro. GLOBAL y ORGANIZACION existen para la pantalla de \
					administracion, que si tiene que separarlas porque lo global no lo puede \
					editar el tenant.

					El tamano de pagina se acota a 100: pedir mas devuelve ese maximo, no un \
					error.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Pagina de definiciones. Contenido vacio si la pagina quedo "
							+ "fuera de rango",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(
									implementation = MedicionDefinicionPageResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Valor de estado o de alcance fuera del catalogo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada o no hay contexto de trabajo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MedicionDefinicionPageResponse> search(

			@Parameter(description = "Texto a buscar en el nombre o en el codigo",
					example = "rodilla")
			@RequestParam(required = false) String q,

			@Parameter(description = "Que definiciones devolver segun su ciclo de vida",
					example = "ACTIVO")
			@RequestParam(defaultValue = "ACTIVO") CatalogoEstadoFiltro estado,

			@Parameter(description = "Que definiciones devolver segun su duenio",
					example = "TODOS")
			@RequestParam(defaultValue = "TODOS") CatalogoAlcanceFiltro alcance,

			@Parameter(description = "Numero de pagina, base cero", example = "0")
			@RequestParam(defaultValue = ApiPaging.PAGINA_POR_DEFECTO) int page,

			@Parameter(description = "Elementos por pagina, acotado a 100", example = "20")
			@RequestParam(defaultValue = ApiPaging.TAMANO_POR_DEFECTO) int size) {

		List<MedicionDefinicionView> encontradas = definicionService.listar(
				apiActor.current(), new CatalogoBusqueda(q, estado, alcance, null));

		return ResponseEntity.ok(MedicionDefinicionPageResponse.of(
				encontradas, ApiPaging.pagina(page), ApiPaging.tamano(size)));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createMedicionDefinicion",
			summary = "Alta de una definicion de medida",
			description = """
					Declara que se mide, en que unidad y con que rango.

					El campo alcance del cuerpo decide el duenio y quien puede crearla: \
					ORGANIZACION exige consultorio:manage sobre la sede del contexto —pasan \
					ORG_ADMIN y CONSULTORIO_ADMIN—; GLOBAL exige rol de plataforma. Si se omite, \
					ORGANIZACION.

					El TIPO decide todo lo demas y es inmutable. NUMERICO y ESCALA exigen unidad \
					y admiten rango; TEXTO y BOOLEANO no llevan ni una ni otro —un minimo sobre \
					un texto no significa nada—. Un tipo incoherente con la unidad o el rango es \
					400.

					La UNIDAD importa mas de lo que parece: se copia en cada medicion tomada, \
					asi que cambiarla despues no reescribe el pasado. Declararla mal desde el \
					principio si deja mediciones que dicen otra cosa.

					El codigo es unico entre las definiciones VIGENTES del mismo duenio, y el \
					nombre tambien —comparado sin distinguir mayusculas ni acentos—. El codigo \
					de una dada de baja SI se puede reusar.

					No lleva Idempotency-Key y es deliberado: una definicion no consume cupo de \
					ningun plan, asi que lo unico que un reintento podria producir es una fila \
					duplicada, y contra eso el unique de codigo es una garantia mas fuerte que \
					una clave que depende de que el cliente la mande bien. El reintento responde \
					409 y no crea nada.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Definicion creada. La cabecera Location apunta al recurso",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(
									implementation = MedicionDefinicionResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, o tipo incoherente con la unidad o el rango",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, sin consultorio:manage para una "
							+ "definicion contextual, o sin rol de plataforma para una global",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Codigo repetido (catalogo-code-taken) o nombre repetido "
							+ "(catalogo-name-taken) entre las vigentes del mismo duenio",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MedicionDefinicionResponse> create(
			@Valid @RequestBody CreateMedicionDefinicionRequest request) {

		// Ni el codigo ni el nombre se loguean: son dato comercial del cliente cuando la
		// definicion es contextual. El alcance y el tipo si, que es lo que permite correlacionar
		// con la fila de auditoria.
		log.info("Alta de definicion de medicion solicitada: alcance={} tipo={}",
				request.alcance(), request.tipo());

		MedicionDefinicionView creada = definicionService.crear(
				apiActor.current(),
				new MedicionDefinicionAltaCommand(
						request.alcance(),
						request.codigo(),
						request.name(),
						request.descripcion(),
						request.tipo(),
						request.unidad(),
						request.minimo(),
						request.maximo()));

		return ResponseEntity
				.created(URI.create("/api/v1/mediciones/definiciones/" + creada.id()))
				.body(MedicionDefinicionResponse.from(creada));
	}

	@PatchMapping(path = "/{definicionId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateMedicionDefinicion",
			summary = "Edicion de una definicion de medida",
			description = """
					Cambia nombre, descripcion, unidad y rango. Los campos omitidos no se tocan; \
					para borrar la descripcion se manda cadena vacia, y para dejar la definicion \
					sin rango se manda clearRango=true.

					EL CODIGO, EL ALCANCE Y EL TIPO NO SE PUEDEN CAMBIAR. El codigo porque es lo \
					que cada medicion tomada copio en su fila; el alcance porque promover una \
					definicion propia a global la haria visible para todos los tenants sin que \
					ninguno lo haya pedido; el tipo porque dejaria mediciones cuyo valor vive en \
					una columna que el tipo nuevo no admite.

					ESTRECHAR EL RANGO NO REVALIDA NADA HACIA ATRAS. Las mediciones ya tomadas \
					no se vuelven invalidas: fueron validas contra la version vigente en su \
					momento, y esa version quedo copiada en su fila. El rango se evalua al \
					registrar y jamas al leer.

					Cambiar la unidad tampoco reescribe el pasado: cada medicion guarda la que \
					regia cuando se tomo, y la comparacion entre sesiones se NIEGA a restar dos \
					valores cuyas unidades no coinciden.

					version es obligatoria y se compara antes de mutar: si quedo vieja, 409 \
					concurrent-modification y el cliente recarga.

					Una definicion INACTIVA no se puede editar: 409 \
					medicion-definicion-inactiva.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Definicion actualizada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(
									implementation = MedicionDefinicionResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, o unidad o rango incoherentes con el tipo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto, sin consultorio:manage sobre una definicion "
							+ "propia, o sin rol de plataforma sobre una global",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "No existe, o es una definicion contextual de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Definicion dada de baja (medicion-definicion-inactiva), "
							+ "nombre repetido (catalogo-name-taken), o version desactualizada "
							+ "(concurrent-modification)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MedicionDefinicionResponse> update(

			@Parameter(description = "Identificador de la definicion", example = "12")
			@PathVariable long definicionId,

			@Valid @RequestBody UpdateMedicionDefinicionRequest request) {

		MedicionDefinicionView actualizada = definicionService.editar(
				apiActor.current(),
				definicionId,
				new MedicionDefinicionEdicionCommand(
						request.name(),
						request.descripcion(),
						request.unidad(),
						request.minimo(),
						request.maximo(),
						Boolean.TRUE.equals(request.clearRango()),
						request.version()));

		return ResponseEntity.ok(MedicionDefinicionResponse.from(actualizada));
	}

	// El 204 declara application/json ADEMAS de problem+json a proposito: el cliente generado
	// manda Accept: application/problem+json y el produces de clase lo cortaria con 406 antes de
	// entrar al metodo. Ya rompio la activacion de cuenta y la recuperacion de contrasena, y
	// ningun test lo agarro.
	@DeleteMapping(path = "/{definicionId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = {MediaType.APPLICATION_JSON_VALUE,
					MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "deactivateMedicionDefinicion",
			summary = "Dar de baja una definicion de medida",
			description = """
					Baja LOGICA con motivo obligatorio (RN-M06-001). Es como se discontinua un \
					test.

					NO BORRA NADA Y NO CASCADEA. Las mediciones que ya usaban esta definicion \
					siguen legibles, siguen diciendo lo que decian —nombre, unidad y tipo estan \
					copiados en cada fila— y siguen entrando en la comparacion entre sesiones. \
					Lo unico que se impide es registrar mediciones NUEVAS con ella, con 409 \
					medicion-definicion-inactiva.

					La definicion en si se sigue leyendo con 200, no con 404: responder "no \
					existe" justo cuando alguien quiere entender una medicion vieja seria borrar \
					historia por la puerta de atras.

					A diferencia de la baja de una especialidad, NO se bloquea por referencias: \
					una medicion tomada no es algo que quede colgando, es un hecho ocurrido, y \
					RN-M06-001 pide que sobreviva a la baja, no que la impida.

					No hay reactivacion: una definicion que vuelve es una definicion nueva. \
					Modelarlo como baja deshecha borraria el rastro de que el test dejo de \
					usarse alguna vez.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Definicion dada de baja"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo de la baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto, sin consultorio:manage sobre una definicion "
							+ "propia, o sin rol de plataforma sobre una global",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "No existe, o es una definicion contextual de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ya estaba dada de baja (medicion-definicion-inactiva)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivate(

			@Parameter(description = "Identificador de la definicion", example = "12")
			@PathVariable long definicionId,

			@Valid @RequestBody DeactivateMedicionDefinicionRequest request) {

		// El motivo no se loguea: es texto libre del cliente.
		log.info("Baja de definicion de medicion solicitada: definicionId={}", definicionId);

		definicionService.darDeBaja(apiActor.current(), definicionId, request.reason());

		return ResponseEntity.noContent().build();
	}
}
