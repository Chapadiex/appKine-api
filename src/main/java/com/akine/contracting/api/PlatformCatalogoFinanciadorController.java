package com.akine.contracting.api;

import com.akine.contracting.api.dto.CatalogoFinanciadorResponse;
import com.akine.contracting.api.dto.CreateFinanciadorRequest;
import com.akine.contracting.api.dto.CreatePlanCoberturaRequest;
import com.akine.contracting.api.dto.DeactivateContractingRequest;
import com.akine.contracting.api.dto.PlanCatalogoResponse;
import com.akine.contracting.api.dto.UpdateFinanciadorRequest;
import com.akine.contracting.api.dto.UpdatePlanCoberturaRequest;
import com.akine.contracting.application.CatalogoDeFinanciadoresService;
import com.akine.contracting.application.EstadoFiltro;
import com.akine.contracting.application.FinanciadorAltaCommand;
import com.akine.contracting.application.FinanciadorBusqueda;
import com.akine.contracting.application.FinanciadorCatalogoView;
import com.akine.contracting.application.FinanciadorEdicionCommand;
import com.akine.contracting.application.PlanAltaCommand;
import com.akine.contracting.application.PlanCatalogoView;
import com.akine.contracting.application.PlanEdicionCommand;
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
 * Consola de plataforma del catalogo global de financiadores y planes (M15, DP-43).
 *
 * <p>Solo {@code PLATFORM_ADMIN}: cualquier otra cuenta recibe 403, nunca 404 —el catalogo es
 * visible para todos los centros, asi que ocultarlo no protegeria nada—. Cuelga de
 * {@code /api/v1/platform} como el resto de la consola: el filtro de tenant deja pasar al rol de
 * plataforma sin contexto, que es como opera.
 *
 * <p>Las validaciones, los codigos inmutables, la baja logica con motivo y los 409 por codigo,
 * nombre o CUIT repetidos son los mismos que en el catalogo de un centro, con el mismo
 * {@code type}: la unicidad es entre las entradas vigentes del catalogo global. <b>Nada de lo que
 * se hace aca toca las copias que los centros ya adoptaron.</b>
 */
@RestController
@RequestMapping(path = "/api/v1/platform/catalogo-financiadores",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Catalogo global de financiadores",
		description = "Financiadores y planes que la plataforma ofrece para que los centros adopten (DP-43)")
public class PlatformCatalogoFinanciadorController {

	private static final String SIN_ROL = "La cuenta no tiene rol de plataforma vigente";

	private final CatalogoDeFinanciadoresService service;
	private final ContractingApiActor apiActor;

	public PlatformCatalogoFinanciadorController(
			CatalogoDeFinanciadoresService service, ContractingApiActor apiActor) {

		this.service = service;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listCatalogoFinanciadoresDePlataforma",
			summary = "Listar el catalogo global de financiadores",
			description = """
					Consola de plataforma. Devuelve las entradas del catalogo global con TODOS sus \
					planes, activos y dados de baja, ordenadas por nombre. q busca por nombre, \
					codigo y CUIT; estado filtra el ciclo de vida (por defecto ACTIVO). \
					financiadorAdoptadoId viaja siempre null y actualizacionDisponible false: son \
					de la lectura de un centro.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Entradas del catalogo",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(schema = @Schema(
									implementation = CatalogoFinanciadorResponse.class)))),
			@ApiResponse(responseCode = "403", description = SIN_ROL,
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<CatalogoFinanciadorResponse>> list(
			@Parameter(description = "Texto a buscar en nombre, codigo y CUIT") @RequestParam(required = false) String q,
			@Parameter(description = "Filtro por ciclo de vida. Si se omite, ACTIVO") @RequestParam(required = false) EstadoFiltro estado,
			@Parameter(description = "Filtro por clasificacion") @RequestParam(required = false) TipoFinanciador tipo) {

		return ResponseEntity.ok(service
				.buscarEnPlataforma(apiActor.current(), new FinanciadorBusqueda(q, estado, tipo))
				.stream()
				.map(CatalogoFinanciadorResponse::de)
				.toList());
	}

	@GetMapping(path = "/{catalogoId}")
	@Operation(
			operationId = "getCatalogoFinanciadorDePlataforma",
			summary = "Ver una entrada del catalogo global de financiadores",
			description = "Con 200 aunque este dada de baja, y con todos sus planes.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La entrada del catalogo",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CatalogoFinanciadorResponse.class))),
			@ApiResponse(responseCode = "403", description = SIN_ROL,
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La entrada no existe",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CatalogoFinanciadorResponse> get(
			@Parameter(description = "Identificador en el catalogo", example = "7") @PathVariable long catalogoId) {

		return ResponseEntity.ok(CatalogoFinanciadorResponse.de(
				service.verEnPlataforma(apiActor.current(), catalogoId)));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createCatalogoFinanciador",
			summary = "Dar de alta un financiador en el catalogo global",
			description = """
					El codigo es unico entre las entradas VIGENTES del catalogo y no se puede \
					cambiar despues: es el codigo que recibe la copia de cada centro que lo adopte. \
					El nombre y el CUIT (normalizado a 11 digitos) tambien son unicos entre las \
					vigentes.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Entrada creada",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CatalogoFinanciadorResponse.class))),
			@ApiResponse(responseCode = "400", description = "Campos invalidos o faltantes",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = SIN_ROL,
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Codigo (financiador-codigo-taken), "
					+ "nombre (financiador-nombre-taken) o CUIT (financiador-cuit-taken) repetido "
					+ "entre las entradas vigentes del catalogo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CatalogoFinanciadorResponse> create(
			@Valid @RequestBody CreateFinanciadorRequest request) {

		FinanciadorCatalogoView creado = service.crear(apiActor.current(), new FinanciadorAltaCommand(
				request.codigo(),
				request.nombre(),
				request.tipo(),
				request.cuit(),
				request.emailContacto(),
				request.telefonoContacto(),
				request.observaciones()));
		return ResponseEntity
				.created(URI.create("/api/v1/platform/catalogo-financiadores/" + creado.id()))
				.body(CatalogoFinanciadorResponse.de(creado));
	}

	@PutMapping(path = "/{catalogoId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateCatalogoFinanciador",
			summary = "Editar una entrada del catalogo global de financiadores",
			description = """
					Edicion parcial: los campos en null no se tocan. El codigo no se edita. NO \
					propaga a las copias que los centros ya adoptaron: esos centros ven \
					actualizacionDisponible = true en su lectura del catalogo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Entrada actualizada",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CatalogoFinanciadorResponse.class))),
			@ApiResponse(responseCode = "400", description = "Campos invalidos",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = SIN_ROL,
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La entrada no existe",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Nombre o CUIT repetido, entrada dada "
					+ "de baja (financiador-inactivo) o version desactualizada (concurrent-modification)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CatalogoFinanciadorResponse> update(
			@Parameter(description = "Identificador en el catalogo", example = "7") @PathVariable long catalogoId,
			@Valid @RequestBody UpdateFinanciadorRequest request) {

		return ResponseEntity.ok(CatalogoFinanciadorResponse.de(service.editar(
				apiActor.current(),
				catalogoId,
				new FinanciadorEdicionCommand(
						request.nombre(),
						request.tipo(),
						request.cuit(),
						request.emailContacto(),
						request.telefonoContacto(),
						request.observaciones(),
						request.expectedVersion()))));
	}

	// PRODUCES EXPLICITO: el 204 no lleva cuerpo y sin application/json el cliente generado, que
	// manda Accept: application/problem+json, recibiria 406 (regla 15 del CLAUDE.md).
	@DeleteMapping(path = "/{catalogoId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE })
	@Operation(
			operationId = "deactivateCatalogoFinanciador",
			summary = "Dar de baja una entrada del catalogo global de financiadores",
			description = """
					Baja LOGICA con motivo obligatorio. La entrada deja de ofrecerse para adoptar. \
					No cascadea: sus planes conservan su estado y las copias que los centros ya \
					adoptaron no se tocan.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Entrada dada de baja"),
			@ApiResponse(responseCode = "400", description = "Falta el motivo de la baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = SIN_ROL,
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La entrada no existe",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Ya estaba dada de baja "
					+ "(financiador-already-inactive)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivate(
			@Parameter(description = "Identificador en el catalogo", example = "7") @PathVariable long catalogoId,
			@Valid @RequestBody DeactivateContractingRequest request) {

		service.darDeBaja(apiActor.current(), catalogoId, request.reason());
		return ResponseEntity.noContent().build();
	}

	@PostMapping(path = "/{catalogoId}/planes", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createCatalogoPlan",
			summary = "Dar de alta un plan en una entrada del catalogo global",
			description = """
					Mismas reglas que el alta de un plan del centro: codigo inmutable y unico entre \
					los planes vigentes de esa entrada, vigencia hasta INCLUSIVA, copago y moneda \
					juntos. Una entrada dada de baja no admite planes nuevos.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Plan creado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlanCatalogoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Campos invalidos o faltantes",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = SIN_ROL,
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La entrada no existe",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Codigo o nombre repetido "
					+ "(plan-cobertura-codigo-taken, plan-cobertura-nombre-taken) o entrada dada de "
					+ "baja (financiador-inactivo)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PlanCatalogoResponse> createPlan(
			@Parameter(description = "Identificador en el catalogo", example = "7") @PathVariable long catalogoId,
			@Valid @RequestBody CreatePlanCoberturaRequest request) {

		PlanCatalogoView creado = service.crearPlan(apiActor.current(), catalogoId, new PlanAltaCommand(
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
				.created(URI.create("/api/v1/platform/catalogo-financiadores/" + catalogoId
						+ "/planes/" + creado.id()))
				.body(PlanCatalogoResponse.de(creado));
	}

	@PutMapping(path = "/{catalogoId}/planes/{planId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateCatalogoPlan",
			summary = "Editar un plan del catalogo global",
			description = "Edicion parcial, incluido el cierre de vigencia. No propaga a las copias.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Plan actualizado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PlanCatalogoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Campos invalidos",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = SIN_ROL,
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El plan no existe o es de otra entrada",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Nombre repetido, plan dado de baja "
					+ "(plan-cobertura-inactivo) o version desactualizada (concurrent-modification)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PlanCatalogoResponse> updatePlan(
			@Parameter(description = "Identificador en el catalogo", example = "7") @PathVariable long catalogoId,
			@Parameter(description = "Identificador del plan", example = "12") @PathVariable long planId,
			@Valid @RequestBody UpdatePlanCoberturaRequest request) {

		return ResponseEntity.ok(PlanCatalogoResponse.de(service.editarPlan(
				apiActor.current(),
				catalogoId,
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
						request.expectedVersion()))));
	}

	@DeleteMapping(path = "/{catalogoId}/planes/{planId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE })
	@Operation(
			operationId = "deactivateCatalogoPlan",
			summary = "Dar de baja un plan del catalogo global",
			description = "Baja LOGICA con motivo. Deja de copiarse en adopciones nuevas; las copias "
					+ "ya hechas no se tocan.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Plan dado de baja"),
			@ApiResponse(responseCode = "400", description = "Falta el motivo de la baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = SIN_ROL,
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El plan no existe o es de otra entrada",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "Ya estaba dado de baja "
					+ "(plan-cobertura-already-inactive)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivatePlan(
			@Parameter(description = "Identificador en el catalogo", example = "7") @PathVariable long catalogoId,
			@Parameter(description = "Identificador del plan", example = "12") @PathVariable long planId,
			@Valid @RequestBody DeactivateContractingRequest request) {

		service.darDeBajaPlan(apiActor.current(), catalogoId, planId, request.reason());
		return ResponseEntity.noContent().build();
	}
}
