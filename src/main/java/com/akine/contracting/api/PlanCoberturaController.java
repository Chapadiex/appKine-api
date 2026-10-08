package com.akine.contracting.api;

import com.akine.contracting.api.dto.CreatePlanCoberturaRequest;
import com.akine.contracting.api.dto.DeactivateContractingRequest;
import com.akine.contracting.api.dto.PlanCoberturaResponse;
import com.akine.contracting.api.dto.UpdatePlanCoberturaRequest;
import com.akine.contracting.application.EstadoFiltro;
import com.akine.contracting.application.PlanAltaCommand;
import com.akine.contracting.application.PlanCoberturaService;
import com.akine.contracting.application.PlanCoberturaView;
import com.akine.contracting.application.PlanEdicionCommand;
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
 * Planes de cobertura de un financiador (M15, AKINE-03.03).
 *
 * <h2>Por que la ruta cuelga del financiador</h2>
 *
 * <p>Porque RN-M15-001 dice que un plan pertenece a un financiador, y la ruta es el lugar donde
 * esa pertenencia no se puede contradecir: el financiador nunca viaja en el cuerpo, asi que no
 * existe el request cuya URL dice una cosa y cuyo cuerpo dice otra. Un plan de otro financiador
 * responde <b>404</b> bajo esta ruta aunque exista y sea del mismo tenant — resolverlo seria una
 * respuesta que miente sobre a quien pertenece.
 *
 * <h2>Estado y vigencia son dos cosas, y la API las devuelve por separado</h2>
 *
 * <p>{@code estado} es el ciclo de vida administrativo (ACTIVO/INACTIVO) y {@code vigente} dice si
 * la fecha consultada cae dentro de la ventana del plan. Un plan ACTIVO con {@code vigente = false}
 * es el caso borde de la etapa —"plan sin nuevas altas pero con pacientes vigentes"— y colapsarlos
 * dejaria a la pantalla sin poder explicar por que ese plan no se ofrece.
 *
 * <p>Por eso <b>cerrar la vigencia no es dar de baja</b>: se cierra mandando {@code vigenciaHasta}
 * en el PUT, el plan queda ACTIVO y consultable, y lo unico que cambia es que deja de ofrecerse
 * para selecciones posteriores a esa fecha. RF-M15-005 pide las dos operaciones y son distintas.
 *
 * <h2>Autorizacion y codigos</h2>
 *
 * <p>Los mismos que {@link FinanciadorController}: leer con contexto de organizacion, mutar con
 * {@code convenio:manage} evaluado sobre la sede del contexto; 404 cross-tenant, 403 sin permiso,
 * 409 para los invariantes y para la version desactualizada.
 */
@RestController
@RequestMapping(
		path = "/api/v1/financiadores/{financiadorId}/planes",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Financiadores y planes",
		description = "Catalogo de financiadores y planes de cobertura de la organizacion (M15)")
public class PlanCoberturaController {

	private static final Logger log = LoggerFactory.getLogger(PlanCoberturaController.class);

	private final PlanCoberturaService planService;
	private final ContractingApiActor apiActor;

	public PlanCoberturaController(
			PlanCoberturaService planService, ContractingApiActor apiActor) {

		this.planService = planService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listPlanesDeCobertura",
			summary = "Listar los planes de un financiador",
			description = """
					Devuelve los planes del financiador, ordenados por nombre.

					estado filtra el CICLO DE VIDA y por defecto trae solo los ACTIVOS. NO filtra \
					por vigencia: un plan activo con la vigencia vencida sigue siendo ACTIVO y se \
					devuelve, con vigente = false. Son dos cosas distintas y la pantalla necesita \
					las dos para explicar por que un plan no se ofrece.

					fecha es el dia contra el que se calcula vigente. Si se omite, hoy.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Planes del financiador, ordenados por nombre",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = PlanCoberturaResponse.class)))),
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
	public ResponseEntity<List<PlanCoberturaResponse>> list(

			@Parameter(description = "Identificador del financiador", example = "31")
			@PathVariable long financiadorId,

			@Parameter(description = "Filtro por ciclo de vida. Si se omite, ACTIVO")
			@RequestParam(required = false) EstadoFiltro estado,

			@Parameter(
					description = "Dia contra el que se calcula vigente. Si se omite, hoy",
					example = "2026-09-02")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		List<PlanCoberturaResponse> planes =
				planService.listar(apiActor.current(), financiadorId, estado, fecha).stream()
						.map(PlanCoberturaResponse::de)
						.toList();

		return ResponseEntity.ok(planes);
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createPlanDeCobertura",
			summary = "Dar de alta un plan bajo un financiador",
			description = """
					Exige convenio:manage sobre la sede del contexto.

					UN FINANCIADOR DADO DE BAJA NO ADMITE PLANES NUEVOS: 409 \
					financiador-inactivo. Es la contracara de que la baja no cascadee — lo que se \
					impide es lo nuevo, no lo que ya existe.

					El codigo es unico entre los planes VIGENTES de ESE financiador y no se puede \
					cambiar despues. El alcance es el financiador y no la organizacion: dos \
					financiadores distintos pueden tener los dos un plan 210, y obligar a que no \
					se repita entre ellos forzaria a inventar codigos que el financiador real no \
					usa.

					vigenciaHasta es el ULTIMO dia INCLUSIVE y puede coincidir con vigenciaDesde: \
					un plan que vale un solo dia es un estado real. Null significa sin fin \
					previsto.

					copago y moneda viajan juntos o no viajan: un importe sin moneda no es un \
					importe. Copago null significa "sin copago declarado", que NO es lo mismo que \
					cero.

					No lleva Idempotency-Key, con el mismo criterio que el alta de financiador.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Plan creado. La cabecera Location apunta al recurso",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlanCoberturaResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, vigencia invertida, o copago sin moneda",
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
					description = "Codigo (plan-cobertura-codigo-taken) o nombre "
							+ "(plan-cobertura-nombre-taken) repetido entre los planes vigentes de "
							+ "ese financiador, o financiador dado de baja (financiador-inactivo)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PlanCoberturaResponse> create(

			@Parameter(description = "Identificador del financiador", example = "31")
			@PathVariable long financiadorId,

			@Valid @RequestBody CreatePlanCoberturaRequest request) {

		log.info("Alta de plan de cobertura solicitada: financiadorId={} codigo={}",
				financiadorId, request.codigo());

		PlanCoberturaView creado = planService.crear(
				apiActor.current(),
				financiadorId,
				new PlanAltaCommand(
						request.codigo(),
						request.nombre(),
						request.descripcion(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						request.requiereAutorizacion(),
						request.requiereCredencial(),
						request.copago(),
						request.moneda()));

		return ResponseEntity
				.created(URI.create(
						"/api/v1/financiadores/" + financiadorId + "/planes/" + creado.id()))
				.body(PlanCoberturaResponse.de(creado));
	}

	@PutMapping(path = "/{planId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updatePlanDeCobertura",
			summary = "Editar un plan, o cerrar su vigencia",
			description = """
					Exige convenio:manage sobre la sede del contexto.

					CERRAR LA VIGENCIA ES ESTA OPERACION, mandando vigenciaHasta. RF-M15-005 dice \
					"actualizar o cerrar vigencia" y son la misma escritura. Cerrar la vigencia NO \
					es dar de baja: el plan queda ACTIVO y consultable, y lo unico que cambia es \
					que deja de ofrecerse para selecciones posteriores a esa fecha.

					Ni el codigo ni el financiador se pueden cambiar y por eso no estan en el \
					cuerpo: son la identidad del plan, y las coberturas ya firmadas la \
					referencian.

					Los campos que llegan en null NO se tocan. La vigencia se valida como par \
					aunque llegue de a una: mandar solo vigenciaHasta se compara contra el \
					vigenciaDesde guardado.

					Un plan dado de baja no admite ediciones: 409 plan-cobertura-inactivo. Un \
					plan de un financiador dado de baja SI se puede editar: dejar de trabajar con \
					una obra social no puede tener como efecto que sus planes queden congelados \
					con un error de tipeo.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Plan actualizado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlanCoberturaResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, vigencia invertida, o copago sin moneda",
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
					description = "El plan no existe, es de otra organizacion, o es de otro "
							+ "financiador que el de la ruta",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Nombre repetido (plan-cobertura-nombre-taken), plan dado de baja "
							+ "(plan-cobertura-inactivo), o version desactualizada (concurrent-modification)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PlanCoberturaResponse> update(

			@Parameter(description = "Identificador del financiador", example = "31")
			@PathVariable long financiadorId,

			@Parameter(description = "Identificador del plan", example = "88")
			@PathVariable long planId,

			@Valid @RequestBody UpdatePlanCoberturaRequest request) {

		log.info("Edicion de plan de cobertura solicitada: planId={}", planId);

		PlanCoberturaView actualizado = planService.editar(
				apiActor.current(),
				financiadorId,
				planId,
				new PlanEdicionCommand(
						request.nombre(),
						request.descripcion(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						request.requiereAutorizacion(),
						request.requiereCredencial(),
						request.copago(),
						request.moneda(),
						request.expectedVersion()));

		return ResponseEntity.ok(PlanCoberturaResponse.de(actualizado));
	}

	// PRODUCES EXPLICITO por el 406 del Accept problem+json. Ver el mismo comentario, mas largo,
	// en FinanciadorController.deactivate.
	@DeleteMapping(path = "/{planId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE })
	@Operation(
			operationId = "deactivatePlanDeCobertura",
			summary = "Dar de baja un plan de cobertura",
			description = """
					Baja LOGICA con motivo obligatorio. Exige convenio:manage sobre la sede del \
					contexto.

					LAS COBERTURAS YA FIRMADAS BAJO ESTE PLAN NO SE TOCAN. Siguen resolviendo con \
					la copia congelada que guardaron al firmarse: RN-M15-003 prohibe eliminar \
					historicos. Lo unico que la baja impide es que el plan se ofrezca para \
					selecciones nuevas (RN-M15-002).

					Libera el codigo y el nombre para un plan nuevo del mismo financiador.

					Si lo que se quiere es que el plan deje de ofrecerse a partir de una fecha \
					pero siga siendo un plan vigente hasta entonces, la operacion NO es esta: es \
					cerrar la vigencia con el PUT.

					No hay reactivacion.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Plan dado de baja"),
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
					description = "El plan no existe, es de otra organizacion, o es de otro "
							+ "financiador que el de la ruta",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El plan ya estaba dado de baja "
							+ "(plan-cobertura-already-inactive)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivate(

			@Parameter(description = "Identificador del financiador", example = "31")
			@PathVariable long financiadorId,

			@Parameter(description = "Identificador del plan", example = "88")
			@PathVariable long planId,

			@Valid @RequestBody DeactivateContractingRequest request) {

		log.info("Baja de plan de cobertura solicitada: planId={}", planId);

		planService.darDeBaja(apiActor.current(), financiadorId, planId, request.reason());

		return ResponseEntity.noContent().build();
	}
}
