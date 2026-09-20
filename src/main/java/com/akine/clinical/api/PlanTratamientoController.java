package com.akine.clinical.api;

import com.akine.clinical.api.dto.AvanceDelPlanResponse;
import com.akine.clinical.api.dto.CrearPlanTratamientoRequest;
import com.akine.clinical.api.dto.FinalizarPlanTratamientoRequest;
import com.akine.clinical.api.dto.ModificarPlanTratamientoRequest;
import com.akine.clinical.api.dto.PlanItemRequest;
import com.akine.clinical.api.dto.PlanTratamientoResponse;
import com.akine.clinical.api.dto.PlanVersionResponse;
import com.akine.clinical.api.dto.SuspenderPlanTratamientoRequest;
import com.akine.clinical.api.dto.TransicionDePlanRequest;
import com.akine.clinical.application.ContenidoDelPlan;
import com.akine.clinical.application.PlanItemPlanificado;
import com.akine.clinical.application.PlanTratamientoService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Planes de Tratamiento de un Caso Clinico (M11, RF-M11-001..008).
 *
 * <h2>Dos bases de ruta, y por que</h2>
 *
 * <p>El alta y el listado cuelgan del caso —{@code /casos-clinicos/&#123;id&#125;/planes}— porque el
 * Caso es lo que un plan necesita para existir y lo que decide si puede haber otro activo. Las ocho
 * operaciones sobre un plan existente son planas —{@code /planes-tratamiento/&#123;id&#125;}— porque
 * el plan <b>sabe</b> de que caso es, y obligar al cliente a repetirlo abriria la puerta a que los
 * dos ids no coincidan. Mismo reparto que los casos clinicos de 04.03.
 *
 * <h2>Lo que esta API no ofrece, y la ausencia es la decision</h2>
 *
 * <ul>
 *   <li><b>Ningun campo para cargar sesiones realizadas.</b> No hay validacion que lo impida
 *       porque no hay columna donde guardarlo (RN-M11-001). Lo realizado se <b>consulta</b> en
 *       {@code /avance}, que lo deriva de las sesiones cerradas del Caso.</li>
 *   <li><b>Ningun endpoint que agende.</b> El plan propone una frecuencia; los turnos son de la
 *       agenda y esta API no los toca (regla maestra 2).</li>
 *   <li><b>Ningun DELETE.</b> Un plan no se borra: se finaliza, con motivo. Dos formas de que un
 *       plan "no este" es como se construye una consulta que se olvida de una.</li>
 *   <li><b>Ningun endpoint que cierre el caso</b> cuando el avance se completa. El avance avisa y
 *       decide el profesional (RN-M11-004).</li>
 * </ul>
 *
 * <h2>Toda operacion es acceso clinico</h2>
 *
 * <p>Las cuatro lecturas tambien. Cada una exige {@code hc:read} o {@code hc:write} mas relacion
 * asistencial o motivo declarado (DP-03), y cada una deja su evento de auditoria: un plan dice que
 * se le esta haciendo al paciente y por cuanto tiempo.
 */
@RestController
@Tag(name = "Planes de tratamiento",
		description = "Planificacion terapeutica de un Caso Clinico (M11). Lo planificado se "
				+ "escribe; lo realizado se deriva")
public class PlanTratamientoController {

	private static final Logger log = LoggerFactory.getLogger(PlanTratamientoController.class);

	private static final String POR_CASO = "/api/v1/casos-clinicos/{casoClinicoId}";
	private static final String POR_PLAN = "/api/v1/planes-tratamiento/{planId}";

	private final PlanTratamientoService planService;
	private final ClinicalApiActor apiActor;

	public PlanTratamientoController(
			PlanTratamientoService planService, ClinicalApiActor apiActor) {

		this.planService = planService;
		this.apiActor = apiActor;
	}

	// =================================================================================
	// Colgadas del caso
	// =================================================================================

	@PostMapping(path = POR_CASO + "/planes",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "crearPlanTratamiento",
			summary = "Crear un Plan de Tratamiento",
			description = """
					RF-M11-001. Crea el plan con su correlativo dentro del caso, su version 1 y \
					sus practicas, todo en la misma transaccion.

					EL PLAN NACE EN BORRADOR, aunque venga completo. Activar es otra operacion \
					porque tiene un efecto que no se deshace: finaliza el plan que estuviera \
					vigente en ese Caso.

					NO SE CARGAN SESIONES REALIZADAS, y no porque se validen: no existe la columna \
					donde guardarlas (RN-M11-001). El avance se consulta aparte y se DERIVA \
					contando sesiones cerradas del caso por oferta.

					ESTE ENDPOINT NO AGENDA NADA. frecuenciaSemanal y duracionSemanas son una \
					regla de recurrencia PROPUESTA: los turnos son de la agenda, y un plan que \
					crea turnos es la regla maestra 2 rota.

					EL CASO TIENE QUE ESTAR ACTIVO: si no, 409 caso-no-activo, y lo que \
					corresponde es reabrirlo con motivo antes de planificar.

					LAS OFERTAS TIENEN QUE ESTAR HABILITADAS HOY en la sede del contexto: si no, \
					409 oferta-no-habilitada con el ofertaId que fallo. Es 409 y no 404 porque la \
					oferta existe y quien la eligio la esta viendo en una lista.

					Exige hc:write mas relacion asistencial o motivo declarado, y queda auditado \
					como PLAN_TRATAMIENTO_CREATED.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Plan creado, en BORRADOR",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlanTratamientoResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Cuerpo invalido, o una oferta declarada dos veces",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto, sin hc:write, o sin relacion asistencial ni "
							+ "motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El caso no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El caso no esta activo, o una oferta no esta habilitada",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PlanTratamientoResponse> crear(

			@Parameter(description = "Caso en el que se planifica", example = "17")
			@PathVariable long casoClinicoId,

			@Valid @RequestBody CrearPlanTratamientoRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		PlanTratamientoResponse cuerpo = PlanTratamientoResponse.from(planService.crear(
				apiActor.current(),
				casoClinicoId,
				new ContenidoDelPlan(
						request.objetivos(),
						request.indicaciones(),
						request.frecuenciaSemanal(),
						request.duracionSemanas(),
						practicasDe(request.items())),
				justificacion));

		log.debug("Plan de tratamiento creado por API: planId={}", cuerpo.id());
		return ResponseEntity
				.created(URI.create("/api/v1/planes-tratamiento/" + cuerpo.id()))
				.body(cuerpo);
	}

	@GetMapping(path = POR_CASO + "/planes", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "listarPlanesTratamiento",
			summary = "Listar los planes de un Caso Clinico",
			description = """
					Devuelve cada plan con su version VIGENTE, mas recientes primero.

					Por defecto trae TODOS, incluidos los finalizados. Un plan finalizado no es un \
					plan borrado: se sigue leyendo entero, y es lo que hace consultable el \
					recorrido terapeutico del paciente (regla maestra 10). Con soloVigentes se \
					piden los que todavia estan en juego.

					HAY UN SOLO PLAN ACTIVO POR CASO como mucho, pero puede haber varios en \
					BORRADOR y varios FINALIZADOS. Ese es el historico.

					Es lectura clinica: exige hc:read mas relacion asistencial o motivo declarado, \
					y queda auditada.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Planes del caso",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(schema = @Schema(
									implementation = PlanTratamientoResponse.class)))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto, sin hc:read, o sin relacion asistencial ni "
							+ "motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El caso no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public List<PlanTratamientoResponse> listar(

			@Parameter(description = "Caso cuyos planes se piden", example = "17")
			@PathVariable long casoClinicoId,

			@Parameter(description = "Dejar afuera los finalizados")
			@RequestParam(defaultValue = "false") boolean soloVigentes,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return planService
				.listar(apiActor.current(), casoClinicoId, soloVigentes, justificacion)
				.stream()
				.map(PlanTratamientoResponse::from)
				.toList();
	}

	// =================================================================================
	// Sobre un plan existente
	// =================================================================================

	@GetMapping(path = POR_PLAN, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "verPlanTratamiento",
			summary = "Ver un Plan de Tratamiento con su version vigente",
			description = """
					UN PLAN FINALIZADO SE SIGUE VIENDO ENTERO, con 200. Finalizar no es borrar: lo \
					que un plan finalizado no admite son cambios de contenido.

					LA VERSION QUE VIAJA ES LA VIGENTE. El historico completo se lee con la \
					consulta de versiones, que es otra operacion: los items cuelgan de la version, \
					asi que mostrarlas juntas pondria cantidades de hace dos meses al lado de las \
					de hoy sin que se distinga cuales son cuales.

					NO TRAE AVANCE. El avance se deriva de las sesiones del caso y es su propia \
					consulta: si viajara aca, abrir la ficha consultaria sesiones en cada lectura \
					y las dos respuestas podrian discrepar sin que se note cual vale.

					Es lectura clinica y deja su evento de auditoria.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El plan con su version vigente",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlanTratamientoResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:read, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "El plan no existe, es de otro tenant, o su caso no es "
							+ "accesible. Los tres son indistinguibles: distinguirlos permitiria "
							+ "censar por ids los tratamientos de otro centro",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public PlanTratamientoResponse ver(

			@Parameter(description = "Plan pedido", example = "77")
			@PathVariable long planId,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return PlanTratamientoResponse.from(
				planService.ver(apiActor.current(), planId, justificacion));
	}

	@PatchMapping(path = POR_PLAN,
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "modificarPlanTratamiento",
			summary = "Cambiar el contenido de un plan",
			description = """
					RF-M11-004. EL MISMO CUERPO HACE DOS COSAS Y LO DECIDE EL ESTADO DEL PLAN:

					  * BORRADOR: reescribe la version 1 EN EL LUGAR, sin versionar. Un plan que \
					nunca se activo no tiene historia que preservar, y versionar cada tecleo \
					llenaria la tabla de ruido. El motivo no se exige.
					  * ACTIVO o SUSPENDIDO: escribe una VERSION NUEVA con su propio juego de \
					practicas, y las de la version anterior quedan intactas (RN-M11-003). El \
					motivo ES OBLIGATORIO; su ausencia es 400.
					  * FINALIZADO: 409 plan-no-editable. Lo que corresponde es crear un plan \
					nuevo: un plan finalizado no se reabre.

					NO HAY UN CAMPO PARA ELEGIR SI VERSIONAR, y es deliberado: eso dejaria al \
					cliente decidir si preservar historia clinica, que es la decision que la regla \
					no delega.

					REEMPLAZA EL CONTENIDO COMPLETO. No es un parche por campo ausente: las \
					practicas que no vengan no siguen existiendo en la version nueva.

					expectedVersion NO ES numeroVersion. Aquella es el bloqueo optimista del plan; \
					esta es el orden del contenido.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Plan modificado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlanTratamientoResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Cuerpo invalido, o modificacion de un plan vigente sin motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El plan no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El plan esta finalizado, una oferta no esta habilitada, o la "
							+ "version quedo vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public PlanTratamientoResponse modificar(

			@Parameter(description = "Plan que se modifica", example = "77")
			@PathVariable long planId,

			@Valid @RequestBody ModificarPlanTratamientoRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return PlanTratamientoResponse.from(planService.modificar(
				apiActor.current(),
				planId,
				new ContenidoDelPlan(
						request.objetivos(),
						request.indicaciones(),
						request.frecuenciaSemanal(),
						request.duracionSemanas(),
						practicasDe(request.items())),
				request.motivo(),
				request.expectedVersion(),
				justificacion));
	}

	@PostMapping(path = POR_PLAN + "/activacion",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "activarPlanTratamiento",
			summary = "Activar un Plan de Tratamiento",
			description = """
					RF-M11-006. Pone el plan en ACTIVO.

					ACTIVAR UN PLAN NUEVO FINALIZA EL ANTERIOR, en la misma transaccion. Un Caso \
					admite UN solo plan activo porque el Caso ES el problema terapeutico: dos \
					planes activos para el mismo problema significan que en realidad son dos \
					problemas, o sea dos Casos. La finalizacion automatica queda asentada en el \
					historial del plan viejo, con su motivo.

					SOLO DESDE BORRADOR. Un SUSPENDIDO se reanuda, que es otra operacion con otro \
					evento; un FINALIZADO no se reabre. Cualquiera de las dos es 409 \
					plan-transicion-invalida con el estado actual adentro.

					ACTIVAR LO YA ACTIVO DEVUELVE 200 con el plan tal cual: es el mismo pedido, no \
					un conflicto.

					EL CASO TIENE QUE ESTAR ACTIVO: si no, 409 caso-no-activo.

					Si dos activaciones concurrentes llegan juntas, la que pierda recibe 409: no \
					hay ventana en la que queden dos planes vivos.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Plan activo",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlanTratamientoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Cuerpo invalido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El plan no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El plan no sale de ese estado, el caso no esta activo, otra "
							+ "activacion gano la carrera, o la version quedo vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public PlanTratamientoResponse activar(

			@Parameter(description = "Plan que se activa", example = "77")
			@PathVariable long planId,

			@Valid @RequestBody TransicionDePlanRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		log.info("Activacion de plan de tratamiento solicitada: planId={}", planId);
		return PlanTratamientoResponse.from(planService.activar(
				apiActor.current(), planId, request.expectedVersion(), justificacion));
	}

	@PostMapping(path = POR_PLAN + "/suspension",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "suspenderPlanTratamiento",
			summary = "Suspender un Plan de Tratamiento",
			description = """
					RF-M11-006. El tratamiento se discontinua pero NO se cierra.

					EL MOTIVO ES OBLIGATORIO: sin el, "se freno" es indistinguible de "lo \
					abandonaron" y el historial deja de servir para lo unico que sirve. Se rechaza \
					con 400, no con 409: no hay conflicto de estado, falta un dato del pedido.

					SUSPENDER NO LIBERA EL LUGAR DEL PLAN ACTIVO del caso. Es frenar el que hay, \
					no abrir la puerta a otro: quien quiera empezar otro tratamiento finaliza este.

					SOLO DESDE ACTIVO. Suspender un BORRADOR es 409 plan-transicion-invalida.

					SUSPENDER LO YA SUSPENDIDO DEVUELVE 200 con el motivo ORIGINAL intacto: \
					pisarlo con el nuevo perderia el que explica la suspension.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Plan suspendido",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlanTratamientoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Falta el motivo de la suspension",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El plan no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El plan no sale de ese estado, o la version quedo vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public PlanTratamientoResponse suspender(

			@Parameter(description = "Plan que se suspende", example = "77")
			@PathVariable long planId,

			@Valid @RequestBody SuspenderPlanTratamientoRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return PlanTratamientoResponse.from(planService.suspender(apiActor.current(), planId,
				request.motivo(), request.expectedVersion(), justificacion));
	}

	@PostMapping(path = POR_PLAN + "/reanudacion",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "reanudarPlanTratamiento",
			summary = "Reanudar un Plan de Tratamiento suspendido",
			description = """
					RF-M11-006. El plan vuelve a ACTIVO.

					LAS COLUMNAS DE LA SUSPENSION SE LIMPIAN. No se pierde nada: la suspension \
					anterior, con su motivo, queda en el historial del plan, que es append-only. \
					Dejarlas puestas haria que una consulta por "suspendidos" devuelva planes \
					vigentes.

					SOLO DESDE SUSPENDIDO. Reanudar un BORRADOR es 409: lo que corresponde ahi es \
					ACTIVARLO. Reanudar un FINALIZADO tambien es 409: se crea un plan nuevo.

					REANUDAR LO YA ACTIVO DEVUELVE 200 con el plan tal cual.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Plan reanudado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlanTratamientoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Cuerpo invalido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El plan no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El plan no sale de ese estado, o la version quedo vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public PlanTratamientoResponse reanudar(

			@Parameter(description = "Plan que se reanuda", example = "77")
			@PathVariable long planId,

			@Valid @RequestBody TransicionDePlanRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return PlanTratamientoResponse.from(planService.reanudar(
				apiActor.current(), planId, request.expectedVersion(), justificacion));
	}

	@PostMapping(path = POR_PLAN + "/finalizacion",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "finalizarPlanTratamiento",
			summary = "Finalizar un Plan de Tratamiento",
			description = """
					RF-M11-006. EL MOTIVO ES OBLIGATORIO, por lo mismo que en la suspension.

					ES TERMINAL: no se reabre. Si el paciente retoma, se crea un plan nuevo. Y es \
					lo que libera el lugar del plan activo del caso.

					FINALIZAR NO ES BORRAR, y por eso esta operacion no es un DELETE. El plan se \
					sigue leyendo entero con todas sus versiones y su historial.

					FINALIZAR NO CIERRA EL CASO, aunque sea la tentacion obvia: el caso puede \
					seguir abierto con otro plan, o sin ninguno mientras se decide el siguiente. \
					Son dos maquinas de estado distintas.

					COMPLETAR LA CANTIDAD ESTIMADA TAMPOCO FINALIZA NADA (RN-M11-004): el avance \
					avisa y la decision es clinica, que es justamente lo que esta operacion \
					registra.

					FINALIZAR LO YA FINALIZADO DEVUELVE 200 con el motivo original intacto.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Plan finalizado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlanTratamientoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Falta el motivo de la finalizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El plan no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La version enviada quedo vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public PlanTratamientoResponse finalizar(

			@Parameter(description = "Plan que se finaliza", example = "77")
			@PathVariable long planId,

			@Valid @RequestBody FinalizarPlanTratamientoRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		log.info("Finalizacion de plan de tratamiento solicitada: planId={}", planId);
		return PlanTratamientoResponse.from(planService.finalizar(apiActor.current(), planId,
				request.motivo(), request.expectedVersion(), justificacion));
	}

	@GetMapping(path = POR_PLAN + "/versiones", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "listarVersionesDePlanTratamiento",
			summary = "Historico de versiones de un plan",
			description = """
					RF-M11-004. Devuelve todas las versiones, de la mas nueva a la mas vieja, CADA \
					UNA CON SUS PROPIAS PRACTICAS.

					Eso ultimo es el modelo y no una decision de serializacion: modificar un plan \
					vigente crea una version nueva con su propio juego de practicas y las de la \
					anterior quedan intactas (RN-M11-003). Por eso el avance de la version 1 y el \
					de la 2 son numeros distintos y los dos correctos.

					LAS VERSIONES NO SE DAN DE BAJA NI SIQUIERA LOGICAMENTE: una version es un \
					hecho pasado, y darla de baja seria reescribir historia clinica.

					Es su propio acceso clinico y su propio evento de auditoria: recorrer el \
					historico es ver todo lo que se penso hacerle al paciente, y colapsarlo dentro \
					de la lectura normal esconderia un acceso que despues alguien quiere revisar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Versiones del plan",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(schema = @Schema(
									implementation = PlanVersionResponse.class)))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:read, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El plan no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public List<PlanVersionResponse> versiones(

			@Parameter(description = "Plan cuyo historico se pide", example = "77")
			@PathVariable long planId,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return planService.versiones(apiActor.current(), planId, justificacion).stream()
				.map(PlanVersionResponse::from)
				.toList();
	}

	@GetMapping(path = POR_PLAN + "/avance", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "verAvanceDePlanTratamiento",
			summary = "Avance derivado de un plan",
			description = """
					RF-M11-005. TODO LO QUE DEVUELVE ES DERIVADO: las realizadas y las canceladas \
					salen de contar sesiones CERRADAS del caso por oferta, no de ninguna columna. \
					Esa es la etapa entera en una frase — planificado no es realizado \
					(RN-M11-001).

					Como no hay contador que actualizar, una sesion que se esta cerrando mientras \
					alguien mira el avance NO produce lectura sucia: entra o no entra segun haya \
					commiteado, y las dos respuestas son correctas.

					EL NUMERO DE VERSION VIAJA SIEMPRE EN LA RESPUESTA. Los items cuelgan de la \
					version, asi que "8 de 20" en la version 1 y "8 de 24" en la 2 son los dos \
					correctos, y la pantalla tiene que decir de cual habla. Con numeroVersion se \
					pide el avance historico de una version anterior; sin el, el de la vigente.

					UNA OFERTA CON SESIONES QUE NO ESTA PLANIFICADA EN ESA VERSION NO APARECE, y \
					es correcto: se atendio algo fuera del plan, y eso se ve en el timeline del \
					caso. Al reves tambien: una practica planificada que todavia no se atendio \
					resuelve en cero.

					completo ES UN AVISO Y NADA MAS (RN-M11-004): no finaliza el plan, no cierra \
					el caso y no dispara ninguna transicion. La decision es clinica.

					SE CUENTA POR OFERTA, no por practica individual dentro de la sesion: los \
					tratamientos realizados son una etapa posterior.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Avance derivado del plan",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AvanceDelPlanResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Se pidio una version que el plan no tiene",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:read, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El plan no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public AvanceDelPlanResponse avance(

			@Parameter(description = "Plan cuyo avance se pide", example = "77")
			@PathVariable long planId,

			@Parameter(description = "Version contra la que contar. Ausente = la vigente",
					example = "1")
			@RequestParam(required = false) Integer numeroVersion,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return AvanceDelPlanResponse.from(planService.avance(
				apiActor.current(), planId, numeroVersion, justificacion));
	}

	// =================================================================================
	// Internos
	// =================================================================================

	/**
	 * Traduce las practicas del pedido a la forma de la aplicacion.
	 *
	 * <p>Una lista ausente es una lista vacia y no un error: un borrador puede no tener practicas
	 * todavia. Lo que <b>no</b> se traduce, porque no existe en ninguno de los dos lados, es una
	 * cantidad realizada.
	 */
	private static List<PlanItemPlanificado> practicasDe(List<PlanItemRequest> declaradas) {
		if (declaradas == null) {
			return List.of();
		}
		return declaradas.stream()
				.map(item -> new PlanItemPlanificado(
						item.ofertaId(),
						item.cantidadPlanificada() == null ? 0 : item.cantidadPlanificada(),
						item.cantidadAutorizada()))
				.toList();
	}
}
