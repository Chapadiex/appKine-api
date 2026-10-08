package com.akine.offering.api;

import com.akine.offering.api.dto.CreateServicioRequest;
import com.akine.offering.api.dto.DeactivateOfferingRequest;
import com.akine.offering.api.dto.ServicioResponse;
import com.akine.offering.api.dto.UpdateServicioRequest;
import com.akine.offering.application.ServicioAltaCommand;
import com.akine.offering.application.ServicioBusqueda;
import com.akine.offering.application.ServicioEdicionCommand;
import com.akine.offering.application.ServicioEstadoFiltro;
import com.akine.offering.application.ServicioService;
import com.akine.offering.application.ServicioView;
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
 * Catalogo GLOBAL de servicios de la plataforma (M27, AKINE-02.06).
 *
 * <h2>Por que la ruta no cuelga de la organizacion</h2>
 *
 * <p>Por lo mismo que el catalogo clinico de M06: <b>un servicio no pertenece a ningun
 * tenant</b>. Publicar "Sesion de kinesiologia" bajo {@code /organizations/7/servicios/12} seria
 * afirmar en la URL algo que la fila niega —{@code servicio} no tiene {@code organization_id},
 * excepcion declarada en ADR-0023— y dejaria la misma entidad accesible por tantas URLs como
 * tenants tenga el SaaS.
 *
 * <p>Lo que SI pertenece a un centro es la decision de ofrecerlo y en que condiciones, y eso es
 * {@link OfertaController}, que si cuelga de la sede.
 *
 * <h2>Autorizacion</h2>
 *
 * <ul>
 *   <li><b>Leer</b>: cualquier usuario autenticado. No hace falta contexto de trabajo elegido,
 *       porque no hay ninguna fila que acotar: el catalogo global es el mismo para todos.</li>
 *   <li><b>Mutar</b>: rol de plataforma. Un administrador de tenant lo VE completo y no lo puede
 *       tocar.</li>
 * </ul>
 *
 * <p><b>Esta etapa no crea permisos nuevos</b> (diseno §1). Las mutaciones se autorizan por rol
 * de plataforma, que es la misma verificacion que ya usan los tres endpoints de
 * {@code /api/v1/platform}. Cuando exista la consola de plataforma esto no cambia de codigo
 * HTTP; lo que cambia es que la pantalla va a poder saber de antemano si mostrar los botones,
 * que hoy no puede.
 *
 * <h2>Codigos, y por que no son intercambiables</h2>
 *
 * <ul>
 *   <li><b>200 para un servicio INACTIVO.</b> No 404. Las ofertas de todos los centros que lo
 *       referencian tienen que seguir resolviendo (RN-M03-006): responder "no existe" seria
 *       borrar historia por la puerta de atras.</li>
 *   <li><b>403</b> cuando falta el rol de plataforma. <b>Nunca 401</b>: el interceptor del
 *       frontend borra el token ante cualquier 401 y dejaria al usuario en un bucle de login.</li>
 *   <li><b>409</b> para los invariantes: codigo o nombre repetidos entre los VIGENTES, servicio
 *       ya inactivo, o version desactualizada. El de concurrencia llega con {@code type}
 *       {@code concurrent-modification} (DP-21).</li>
 * </ul>
 *
 * <h2>Lo que esta etapa deliberadamente NO trae</h2>
 *
 * <ul>
 *   <li><b>Reactivar un servicio dado de baja.</b> Ningun RF lo pide, y un servicio que vuelve
 *       es un alta nueva, no una baja deshecha.</li>
 *   <li><b>Saber cuantas ofertas activas quedan colgando de un servicio que se da de baja.</b>
 *       Es una consulta cross-tenant que solo el rol de plataforma podria hacer, y decidir si
 *       ademas se avisa a esos centros es una decision de producto que el diseno deja abierta
 *       (§7 punto 8). La baja NO cascadea: lo unico que impide es crear ofertas nuevas.</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/servicios", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Servicios y ofertas",
		description = "Catalogo global de servicios y como cada sede los presta (M27)")
public class ServicioController {

	private static final Logger log = LoggerFactory.getLogger(ServicioController.class);

	private final ServicioService servicioService;
	private final OfferingApiActor apiActor;

	public ServicioController(ServicioService servicioService, OfferingApiActor apiActor) {
		this.servicioService = servicioService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listServicios",
			summary = "Listar el catalogo global de servicios",
			description = """
					Devuelve los servicios de la plataforma ordenados por nombre.

					q busca por nombre y por codigo, sin distinguir mayusculas ni acentos. Los \
					comodines que el usuario escriba se escapan: un % tecleado busca un % \
					literal y no todo el catalogo.

					estado filtra por ciclo de vida y por defecto trae solo los ACTIVOS. Con el \
					default, un selector de alta de oferta nunca ofrece un servicio dado de \
					baja, que es lo que evita que el 409 servicio-inactivo tenga que aparecer en \
					esa pantalla.

					No exige contexto de trabajo elegido: el catalogo global es el mismo para \
					todos los centros y no hay ninguna fila que acotar por tenant.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Servicios del catalogo, ordenados por nombre",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = ServicioResponse.class)))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin sesion autenticada",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<ServicioResponse>> list(

			@Parameter(
					description = "Texto a buscar en el nombre y en el codigo. Vacio trae todo",
					example = "kinesio")
			@RequestParam(required = false) String q,

			@Parameter(description = "Filtro por ciclo de vida. Si se omite, ACTIVO")
			@RequestParam(required = false) ServicioEstadoFiltro estado) {

		List<ServicioResponse> servicios =
				servicioService.buscar(new ServicioBusqueda(q, estado)).stream()
						.map(ServicioResponse::de)
						.toList();

		return ResponseEntity.ok(servicios);
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createServicio",
			summary = "Dar de alta un servicio del catalogo global",
			description = """
					Exige rol de plataforma: lo que se crea aca lo ven todos los centros del \
					SaaS.

					El codigo es unico entre los servicios VIGENTES y no se puede cambiar \
					despues: es lo que las ofertas guardan. El codigo de un servicio dado de \
					baja SI se puede reusar. El nombre tambien es unico entre los vigentes, \
					comparado sin distinguir mayusculas ni acentos.

					modalidadDefault, requiereCasoClinicoDefault y generaRegistroClinicoDefault \
					son DEFAULTS que la oferta hereda si no dice otra cosa. No son \
					restricciones: una sede puede ofrecer en grupal un servicio cuyo default es \
					individual.

					No lleva Idempotency-Key y es deliberado: un servicio no consume cupo de \
					ningun plan, asi que lo unico que un reintento podria producir es una fila \
					duplicada, y contra eso el unique de codigo es una garantia mas fuerte que \
					una clave que depende de que el cliente la mande bien.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Servicio creado. La cabecera Location apunta al recurso",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ServicioResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos o faltantes",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin rol de plataforma",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Codigo repetido (servicio-codigo-taken) o nombre repetido "
							+ "(servicio-nombre-taken) entre los servicios vigentes",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ServicioResponse> create(
			@Valid @RequestBody CreateServicioRequest request) {

		// El codigo si se loguea y el nombre no hace falta: el catalogo global no es dato
		// comercial de ningun cliente, pero el codigo es lo que permite correlacionar con la
		// fila de auditoria sin volcar el cuerpo entero al log.
		log.info("Alta de servicio global solicitada: codigo={}", request.codigo());

		ServicioView creado = servicioService.crear(
				apiActor.current(),
				new ServicioAltaCommand(
						request.codigo(),
						request.nombre(),
						request.descripcion(),
						request.naturaleza(),
						request.modalidadDefault(),
						request.requiereCasoClinicoDefault(),
						request.generaRegistroClinicoDefault()));

		return ResponseEntity
				.created(URI.create("/api/v1/servicios/" + creado.id()))
				.body(ServicioResponse.de(creado));
	}

	@PutMapping(path = "/{servicioId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateServicio",
			summary = "Editar un servicio del catalogo global",
			description = """
					Exige rol de plataforma.

					El codigo NO se puede cambiar y por eso no esta en el cuerpo: es la clave \
					con la que las ofertas de todos los centros lo referencian, y cambiarlo \
					seria reescribir el significado de filas que ya existen en tenants que no \
					participan de esta llamada. Renombrar es cambiar nombre.

					Los campos que llegan en null NO se tocan. Es edicion parcial declarada: \
					obligar a mandar el recurso entero forzaria a la pantalla a releerlo antes \
					de cada guardado para no pisar lo que otro cambio en el medio, y \
					expectedVersion ya resuelve ese problema mejor.

					Un servicio dado de baja no admite ediciones: 409 servicio-inactivo. Sus \
					datos historicos siguen siendo consultables.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Servicio actualizado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ServicioResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin rol de plataforma",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El servicio no existe",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Nombre repetido (servicio-nombre-taken), servicio dado de baja "
							+ "(servicio-inactivo), o version desactualizada (concurrent-modification)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ServicioResponse> update(

			@Parameter(description = "Identificador del servicio", example = "12")
			@PathVariable long servicioId,

			@Valid @RequestBody UpdateServicioRequest request) {

		log.info("Edicion de servicio global solicitada: servicioId={}", servicioId);

		ServicioView actualizado = servicioService.editar(
				apiActor.current(),
				servicioId,
				new ServicioEdicionCommand(
						request.nombre(),
						request.descripcion(),
						request.naturaleza(),
						request.modalidadDefault(),
						request.requiereCasoClinicoDefault(),
						request.generaRegistroClinicoDefault(),
						request.expectedVersion()));

		return ResponseEntity.ok(ServicioResponse.de(actualizado));
	}

	// PRODUCES EXPLICITO. El 204 no lleva cuerpo, asi que lo unico que esta operacion declara
	// producir es el problem+json de sus errores, y eso es lo que el cliente generado manda en
	// Accept. Sin declarar tambien application/json, el produces de clase lo rechaza con 406
	// antes de entrar al metodo. Es el mismo defecto que rompio la activacion de cuenta.
	@DeleteMapping(path = "/{servicioId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE })
	@Operation(
			operationId = "deactivateServicio",
			summary = "Dar de baja un servicio del catalogo global",
			description = """
					Baja LOGICA con motivo obligatorio. Exige rol de plataforma.

					LA BAJA NO CASCADEA, y esto es lo mas importante de la operacion. Las \
					ofertas activas de los centros que ya referencian este servicio SIGUEN \
					OPERANDO: RF-M27-002 prohibe el borrado fisico cuando hay referencias y \
					RN-M03-006 prohibe afectar historicos. Lo unico que la baja impide es CREAR \
					ofertas nuevas sobre el, que responden 409 servicio-inactivo.

					El motivo es obligatorio: sin el, la auditoria no responde por que seis \
					meses despues.

					No hay reactivacion. Un servicio que vuelve es un alta nueva, no una baja \
					deshecha: modelarlo al reves borraria el rastro de que dejo de ofrecerse.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "204",
					description = "Servicio dado de baja"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo de la baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin rol de plataforma",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El servicio no existe",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El servicio ya estaba dado de baja "
							+ "(servicio-already-inactive)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivate(

			@Parameter(description = "Identificador del servicio", example = "12")
			@PathVariable long servicioId,

			@Valid @RequestBody DeactivateOfferingRequest request) {

		// El motivo NO se loguea: es texto libre que un administrador de plataforma escribe y
		// puede nombrar a un centro concreto. Queda en la fila y en la auditoria, que es donde
		// tiene control de acceso.
		log.info("Baja de servicio global solicitada: servicioId={}", servicioId);

		servicioService.darDeBaja(apiActor.current(), servicioId, request.reason());

		return ResponseEntity.noContent().build();
	}
}
