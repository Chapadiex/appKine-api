package com.akine.contracting.api;

import com.akine.contracting.api.dto.ConvenioResponse;
import com.akine.contracting.api.dto.CreateConvenioRequest;
import com.akine.contracting.api.dto.DeactivateContractingRequest;
import com.akine.contracting.api.dto.UpdateConvenioRequest;
import com.akine.contracting.application.ConvenioAltaCommand;
import com.akine.contracting.application.ConvenioEdicionCommand;
import com.akine.contracting.application.ConvenioService;
import com.akine.contracting.application.ConvenioView;
import com.akine.contracting.application.EstadoFiltro;
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
 * Convenios de una sede con planes de financiadores (M16, AKINE-03.05).
 *
 * <h2>Por que la ruta cuelga del consultorio</h2>
 *
 * <p>Porque RN-M16-001 dice que el convenio es contextual al consultorio, y la ruta es el lugar
 * donde esa pertenencia no se puede contradecir: la sede nunca viaja en el cuerpo, asi que no
 * existe el request cuya URL dice una cosa y cuyo cuerpo dice otra. Un convenio de otra sede
 * responde <b>404</b> bajo esta ruta aunque exista y sea del mismo tenant.
 *
 * <p><b>El permiso se evalua sobre la sede de la RUTA</b>, no sobre la del contexto. Es lo que
 * impide que alguien con {@code convenio:manage} en la sede A modifique los convenios de la B: ver
 * {@code AutorizacionDeCatalogo.exigirGestionDeLaSede}.
 *
 * <h2>El 409 que caracteriza a estos endpoints</h2>
 *
 * <p>{@code convenio-solapado}. Dos convenios de la misma sede con el mismo financiador y el mismo
 * plan no pueden compartir ni un dia (RN-M16-002). Lo produce el alta <b>y tambien el PUT</b>:
 * estirar la vigencia de un convenio hasta pisar al siguiente es exactamente lo mismo. El cuerpo
 * del problema trae {@code convenioExistenteId} y {@code periodoExistente}, para que la pantalla
 * pueda mostrar cual es sin obligar a buscarlo a mano.
 *
 * <h2>Estado y vigencia son dos cosas, y la API las devuelve por separado</h2>
 *
 * <p>Cerrar la vigencia se hace con el PUT, mandando {@code vigenciaHasta}: el convenio queda
 * ACTIVO y consultable y solo deja de aplicarse despues de esa fecha (RF-M16-003). Dar de baja es
 * el DELETE, exige motivo y no tiene vuelta atras.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/convenios",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Convenios y aranceles",
		description = "Convenios de la sede con planes de financiadores, y sus aranceles (M16)")
public class ConvenioController {

	private static final Logger log = LoggerFactory.getLogger(ConvenioController.class);

	private final ConvenioService convenioService;
	private final ContractingApiActor apiActor;

	public ConvenioController(ConvenioService convenioService, ContractingApiActor apiActor) {
		this.convenioService = convenioService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listConvenios",
			summary = "Listar los convenios de una sede",
			description = """
					Devuelve los convenios de la sede, ordenados por nombre.

					estado filtra el CICLO DE VIDA y por defecto trae solo los ACTIVOS. NO filtra \
					por vigencia: un convenio activo con la vigencia vencida sigue siendo ACTIVO y \
					se devuelve, con vigente = false. Son dos cosas distintas y la grilla necesita \
					las dos para explicar por que un convenio no resuelve.

					fecha es el dia contra el que se calcula vigente. Si se omite, hoy.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Convenios de la sede",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = ConvenioResponse.class)))),
			@ApiResponse(responseCode = "403", description = "Sin contexto de trabajo activo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La sede no existe o es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<ConvenioResponse>> list(

			@Parameter(description = "Identificador de la sede", example = "20")
			@PathVariable long consultorioId,

			@Parameter(description = "Filtro por ciclo de vida. Si se omite, ACTIVO")
			@RequestParam(required = false) EstadoFiltro estado,

			@Parameter(description = "Dia contra el que se calcula vigente. Si se omite, hoy",
					example = "2026-09-02")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		return ResponseEntity.ok(
				convenioService.listar(apiActor.current(), consultorioId, estado, fecha).stream()
						.map(ConvenioResponse::de)
						.toList());
	}

	@GetMapping("/{convenioId}")
	@Operation(
			operationId = "getConvenio",
			summary = "Ver un convenio",
			description = """
					Un convenio INACTIVO se lee con 200 y no con 404: lo que ya se liquido bajo el \
					tiene que seguir siendo explicable (§38).

					fecha es el dia contra el que se calcula vigente. Si se omite, hoy.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El convenio",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ConvenioResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto de trabajo activo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "El convenio no existe, es de otra organizacion, o es de otra sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ConvenioResponse> get(

			@Parameter(description = "Identificador de la sede", example = "20")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador del convenio", example = "140")
			@PathVariable long convenioId,

			@Parameter(description = "Dia contra el que se calcula vigente. Si se omite, hoy")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		return ResponseEntity.ok(ConvenioResponse.de(
				convenioService.ver(apiActor.current(), consultorioId, convenioId, fecha)));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createConvenio",
			summary = "Dar de alta un convenio",
			description = """
					Exige convenio:manage sobre LA SEDE DE LA RUTA.

					DOS CONVENIOS DE LA MISMA SEDE CON EL MISMO FINANCIADOR Y EL MISMO PLAN NO \
					PUEDEN SOLAPARSE EN EL TIEMPO (RN-M16-002): 409 convenio-solapado, con el id y \
					el periodo del que choca en el cuerpo del problema. Si lo que se quiere es \
					renovar, primero se cierra la vigencia del que esta.

					El plan es obligatorio y tiene que ser del financiador declarado. Es lo que \
					hace que la resolucion del arancel tenga como mucho una candidata para una \
					fecha, sin necesidad de ninguna regla de prioridad.

					Un financiador o un plan dados de baja no admiten convenios nuevos: 409.

					vigenciaHasta es el ULTIMO dia INCLUSIVE y puede coincidir con vigenciaDesde. \
					Null significa sin fin previsto.

					El codigo es unico entre los convenios VIGENTES de esa sede y no se puede \
					cambiar despues.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201",
					description = "Convenio creado. La cabecera Location apunta al recurso",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ConvenioResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Campos invalidos, vigencia invertida o tope mensual en cero",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo o sin convenio:manage en esa sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La sede, el financiador o el plan no existen o son de otro tenant. "
							+ "Un plan que no pertenece al financiador declarado tambien es 404",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "Periodo solapado (convenio-solapado), codigo repetido "
							+ "(convenio-codigo-taken), financiador dado de baja "
							+ "(financiador-inactivo) o plan dado de baja (plan-cobertura-inactivo)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ConvenioResponse> create(

			@Parameter(description = "Identificador de la sede", example = "20")
			@PathVariable long consultorioId,

			@Valid @RequestBody CreateConvenioRequest request) {

		log.info("Alta de convenio solicitada: consultorioId={} codigo={}",
				consultorioId, request.codigo());

		ConvenioView creado = convenioService.crear(
				apiActor.current(),
				consultorioId,
				new ConvenioAltaCommand(
						request.financiadorId(),
						request.planId(),
						request.codigo(),
						request.nombre(),
						request.modalidad(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						request.moneda(),
						request.requiereOrden(),
						request.requiereAutorizacion(),
						request.requiereCredencial(),
						request.limiteSesionesMensual(),
						request.documentacionRequerida(),
						request.observaciones()));

		return ResponseEntity
				.created(URI.create(
						"/api/v1/consultorios/" + consultorioId + "/convenios/" + creado.id()))
				.body(ConvenioResponse.de(creado));
	}

	@PutMapping(path = "/{convenioId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateConvenio",
			summary = "Editar un convenio, o cerrar su vigencia",
			description = """
					Exige convenio:manage sobre la sede de la ruta.

					CERRAR LA VIGENCIA ES ESTA OPERACION, mandando vigenciaHasta (RF-M16-003). \
					Cerrar la vigencia NO es dar de baja: el convenio queda ACTIVO y consultable, y \
					lo unico que cambia es que deja de aplicarse despues de esa fecha.

					MOVER LA VIGENCIA PUEDE PRODUCIR 409 convenio-solapado, igual que el alta: \
					estirar el fin de un convenio hasta pisar al siguiente es exactamente lo que \
					RN-M16-002 prohibe.

					Ni el codigo, ni la sede, ni el financiador, ni el plan se pueden cambiar y por \
					eso no estan en el cuerpo: son la identidad del convenio, y lo que ya se liquido \
					bajo el los referencia.

					Los campos que llegan en null NO se tocan. La vigencia se valida como par \
					aunque llegue de a una.

					Un convenio dado de baja no admite ediciones: 409 convenio-inactivo. Uno cuyo \
					financiador o plan estan dados de baja SI se puede editar: dejar de trabajar \
					con una obra social no puede tener como efecto que su convenio quede congelado \
					con un error de tipeo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Convenio actualizado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ConvenioResponse.class))),
			@ApiResponse(responseCode = "400", description = "Campos invalidos o vigencia invertida",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo o sin convenio:manage en esa sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "El convenio no existe, es de otra organizacion, o es de otra sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "Periodo solapado (convenio-solapado), convenio dado de baja "
							+ "(convenio-inactivo) o version desactualizada (conflict)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ConvenioResponse> update(

			@Parameter(description = "Identificador de la sede", example = "20")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador del convenio", example = "140")
			@PathVariable long convenioId,

			@Valid @RequestBody UpdateConvenioRequest request) {

		log.info("Edicion de convenio solicitada: convenioId={}", convenioId);

		ConvenioView actualizado = convenioService.editar(
				apiActor.current(),
				consultorioId,
				convenioId,
				new ConvenioEdicionCommand(
						request.nombre(),
						request.modalidad(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						request.requiereOrden(),
						request.requiereAutorizacion(),
						request.requiereCredencial(),
						request.limiteSesionesMensual(),
						request.documentacionRequerida(),
						request.observaciones(),
						request.expectedVersion()));

		return ResponseEntity.ok(ConvenioResponse.de(actualizado));
	}

	// PRODUCES EXPLICITO por el 406 del Accept problem+json: una operacion 204 cuyo unico produces
	// declarado sea application/problem+json —o cuyo produces de clase no lo incluya— se corta con
	// 406 antes de entrar al metodo cuando el cliente generado manda ese Accept. Es el defecto que
	// rompio la activacion de cuenta hasta be14ba5. Mismo comentario en FinanciadorController.
	@DeleteMapping(path = "/{convenioId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "deactivateConvenio",
			summary = "Dar de baja un convenio",
			description = """
					Baja LOGICA con motivo obligatorio. Exige convenio:manage sobre la sede de la \
					ruta.

					LO YA LIQUIDADO BAJO ESTE CONVENIO NO SE TOCA. Sigue explicandose con la copia \
					congelada que guardo al devengar: RN-M16-003 prohibe recalcular historicos.

					NO CASCADEA a los aranceles y no se bloquea por tenerlos: dejan de resolver \
					porque su convenio dejo de resolver. El numero de aranceles activos queda en la \
					auditoria.

					Libera el codigo Y EL PERIODO para un convenio nuevo de la misma sede: el \
					no-solapamiento solo mira los activos.

					Si lo que se quiere es que el convenio deje de aplicarse a partir de una fecha \
					pero siga siendo un convenio vigente hasta entonces, la operacion NO es esta: \
					es cerrar la vigencia con el PUT.

					No hay reactivacion.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Convenio dado de baja"),
			@ApiResponse(responseCode = "400", description = "Falta el motivo de la baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo o sin convenio:manage en esa sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "El convenio no existe, es de otra organizacion, o es de otra sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El convenio ya estaba dado de baja (convenio-already-inactive)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivate(

			@Parameter(description = "Identificador de la sede", example = "20")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador del convenio", example = "140")
			@PathVariable long convenioId,

			@Valid @RequestBody DeactivateContractingRequest request) {

		log.info("Baja de convenio solicitada: convenioId={}", convenioId);

		convenioService.darDeBaja(apiActor.current(), consultorioId, convenioId, request.reason());

		return ResponseEntity.noContent().build();
	}
}
