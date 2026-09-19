package com.akine.clinical.api;

import com.akine.clinical.api.dto.BajaDeEntradaClinicaRequest;
import com.akine.clinical.api.dto.EnmendarEntradaClinicaRequest;
import com.akine.clinical.api.dto.EntradaClinicaResponse;
import com.akine.clinical.api.dto.EntradaClinicaVersionResponse;
import com.akine.clinical.api.dto.RegistrarEntradaClinicaRequest;
import com.akine.clinical.application.EntradaClinicaService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Entradas clinicas versionadas de una Historia Clinica (M09, RF-M09-006).
 *
 * <h2>Dos bases de ruta, y por que</h2>
 *
 * <p>Las operaciones que nombran la historia cuelgan de ella
 * —{@code /historias-clinicas/&#123;id&#125;/entradas}— porque la historia es la ficha cuya
 * autorizacion clinica se evalua: con ella en la ruta no hay forma de registrar ni listar sin
 * nombrarla. Las que operan sobre una entrada ya existente son planas
 * —{@code /entradas-clinicas/&#123;id&#125;}— porque la entrada <b>sabe</b> de que historia es, y
 * obligar al cliente a repetirla abriria la puerta a que los dos ids no coincidan: un pedido que
 * nombra la historia A y la entrada B habria que rechazarlo, y ese es un caso que directamente no
 * existe si el id no viaja.
 *
 * <h2>Enmendar no es editar</h2>
 *
 * <p>Una enmienda escribe una version <b>nueva</b> y deja la anterior intacta y consultable. Es
 * la diferencia entre corregir la historia clinica y reescribirla, y ADR-0011 solo admite la
 * primera. Por eso no hay ningun {@code PUT} ni {@code PATCH} sobre el cuerpo de una entrada.
 *
 * <h2>Toda operacion es acceso clinico</h2>
 *
 * <p>Las tres lecturas tambien. Cada una exige {@code hc:read} o {@code hc:write} mas relacion
 * asistencial o motivo declarado (DP-03), y cada una deja su evento de auditoria — incluidas las
 * lecturas, que es lo que distingue este modulo del resto del backend.
 */
@RestController
@Tag(name = "Entradas clinicas",
		description = "Hechos clinicos versionados de una Historia Clinica (M09)")
public class EntradaClinicaController {

	private static final Logger log = LoggerFactory.getLogger(EntradaClinicaController.class);

	private static final String POR_HISTORIA = "/api/v1/historias-clinicas/{historiaClinicaId}";
	private static final String POR_ENTRADA = "/api/v1/entradas-clinicas/{entradaClinicaId}";

	private final EntradaClinicaService entradaService;
	private final ClinicalApiActor apiActor;

	public EntradaClinicaController(
			EntradaClinicaService entradaService, ClinicalApiActor apiActor) {

		this.entradaService = entradaService;
		this.apiActor = apiActor;
	}

	// =================================================================================
	// Colgadas de la historia
	// =================================================================================

	@PostMapping(path = POR_HISTORIA + "/entradas",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "registrarEntradaClinica",
			summary = "Registrar una entrada clinica",
			description = """
					RF-M09-006. Crea la entrada y su VERSION 1 en el mismo acto y en la misma \
					transaccion. Una cabecera sin contenido no es un estado que exista: seria una \
					fila que el timeline indexa y que al abrirla no dice nada.

					LA ENTRADA CUELGA DE LA HISTORIA, NO DE UN CASO. El Caso Clinico llega en \
					04.03; inventar un "caso por defecto" para poder agrupar seria un Caso mal \
					hecho que despues hay que desarmar, con todas las entradas colgando de un \
					caso fantasma (regla maestra 1).

					ocurrioEn PUEDE SER PASADO y nunca futuro. Una evolucion se escribe al final \
					del dia y eso es normal; una entrada que declara haber ocurrido mañana \
					desordena el timeline y no hay lectura clinica que la justifique: 400.

					Exige hc:write mas relacion asistencial o motivo declarado, y queda \
					auditada como ENTRADA_CLINICA_REGISTERED.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Entrada registrada con su version 1",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EntradaClinicaResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Cuerpo invalido, o la entrada declara un instante futuro",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto, sin hc:write, o sin relacion asistencial ni "
							+ "motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La historia no existe, esta dada de baja, o es de otra "
							+ "organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EntradaClinicaResponse> registrar(

			@Parameter(description = "Historia clinica en la que se registra", example = "88")
			@PathVariable long historiaClinicaId,

			@Valid @RequestBody RegistrarEntradaClinicaRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		EntradaClinicaResponse cuerpo = EntradaClinicaResponse.from(entradaService.registrar(
				apiActor.current(),
				historiaClinicaId,
				request.tipo(),
				request.cuerpo(),
				request.ocurrioEn(),
				justificacion));

		log.debug("Entrada clinica creada por API: entradaClinicaId={}", cuerpo.id());
		return ResponseEntity
				.created(URI.create("/api/v1/entradas-clinicas/" + cuerpo.id()))
				.body(cuerpo);
	}

	@GetMapping(path = POR_HISTORIA + "/entradas", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "listarEntradasClinicas",
			summary = "Listar las entradas de una Historia Clinica",
			description = """
					Devuelve cada entrada con su version VIGENTE, mas recientes primero. El \
					historico completo de una entrada tiene su propia operacion y su propio \
					evento de auditoria: consultarlo es un acceso mas amplio, porque muestra lo \
					que la historia decia antes y por que se corrigio.

					Por defecto trae solo las VIGENTES. Las dadas de baja se piden con \
					incluirDadasDeBaja y siguen resolviendo, que es la regla maestra 10 aplicada \
					a la entrada: una entrada de baja sale del timeline y sigue siendo \
					consultable, que es lo que distingue "no lo muestres" de "no existio".

					Es lectura clinica: exige hc:read mas relacion asistencial o motivo \
					declarado, y queda auditada.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Entradas de la historia",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(schema = @Schema(
									implementation = EntradaClinicaResponse.class)))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto, sin hc:read, o sin relacion asistencial ni "
							+ "motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La historia no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public List<EntradaClinicaResponse> listar(

			@Parameter(description = "Historia clinica cuyas entradas se piden", example = "88")
			@PathVariable long historiaClinicaId,

			@Parameter(description = "Incluir tambien las dadas de baja")
			@RequestParam(defaultValue = "false") boolean incluirDadasDeBaja,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return entradaService
				.listar(apiActor.current(), historiaClinicaId, !incluirDadasDeBaja, justificacion)
				.stream()
				.map(EntradaClinicaResponse::from)
				.toList();
	}

	// =================================================================================
	// Sobre una entrada existente
	// =================================================================================

	@GetMapping(path = POR_ENTRADA, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "verEntradaClinica",
			summary = "Ver una entrada clinica con su version vigente",
			description = """
					UNA ENTRADA DADA DE BAJA SE SIGUE VIENDO POR SU ID, con 200. Sale del \
					timeline y del listado por defecto, no del sistema: negarla convertiria la \
					baja logica en un borrado con otro nombre (regla maestra 10).

					Es lectura clinica y deja su evento de auditoria.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La entrada con su version vigente",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EntradaClinicaResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:read, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La entrada no existe, es de otro tenant, o es de otra "
							+ "historia. Los tres son indistinguibles: distinguirlos permitiria "
							+ "censar por ids las entradas de otro centro",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public EntradaClinicaResponse ver(

			@Parameter(description = "Entrada clinica pedida", example = "312")
			@PathVariable long entradaClinicaId,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return EntradaClinicaResponse.from(
				entradaService.ver(apiActor.current(), entradaClinicaId, justificacion));
	}

	@GetMapping(path = POR_ENTRADA + "/versiones", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "verVersionesDeEntradaClinica",
			summary = "Ver el historico de versiones de una entrada",
			description = """
					RF-M09-006. De la version mas nueva a la mas vieja. La 1 es el original y \
					NUNCA se sobrescribe: una enmienda agrega una fila, no reemplaza el texto.

					LAS VERSIONES NO SE DAN DE BAJA, NI SIQUIERA LOGICAMENTE. Una version es un \
					hecho pasado y desactivarla seria reescribir historia clinica, que ADR-0011 \
					prohibe. Por eso ninguna trae campos de ciclo de vida.

					ES SU PROPIA OPERACION Y SU PROPIO EVENTO DE AUDITORIA. Leer todas las \
					versiones es un acceso mas amplio que leer la vigente —muestra lo que la \
					historia decia antes y por que se corrigio— y colapsarlo dentro de la lectura \
					normal esconderia justamente el acceso que despues alguien quiere revisar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Historico de versiones",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(schema = @Schema(
									implementation = EntradaClinicaVersionResponse.class)))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:read, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La entrada no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public List<EntradaClinicaVersionResponse> versiones(

			@Parameter(description = "Entrada clinica cuyo historico se pide", example = "312")
			@PathVariable long entradaClinicaId,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return entradaService
				.versiones(apiActor.current(), entradaClinicaId, justificacion)
				.stream()
				.map(EntradaClinicaVersionResponse::from)
				.toList();
	}

	@PostMapping(path = POR_ENTRADA + "/enmiendas",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "enmendarEntradaClinica",
			summary = "Enmendar una entrada clinica",
			description = """
					RF-M09-006. Escribe una version NUEVA y deja la anterior intacta. Es la \
					diferencia entre corregir la historia clinica y reescribirla, y por eso la \
					operacion es un POST sobre /enmiendas y no un PUT sobre la entrada.

					EL MOTIVO ES OBLIGATORIO. Sin el, una enmienda es indistinguible de una \
					correccion de tipeo y el historial deja de servir para lo unico que sirve, \
					que es entender por que cambio el texto (RN-M09-004). Se rechaza con 400 \
					enmienda-sin-motivo, no con 409: no hay conflicto de estado, falta un dato \
					del pedido, y reintentar sin motivo falla exactamente igual.

					expectedVersion ES LA DE LA CABECERA, no el numero de contenido. Si alguien \
					enmendo o dio de baja la entrada en el medio, esto responde 409 en vez de \
					apilar una version sobre un texto que el autor nunca vio. Dos enmiendas \
					simultaneas NO se pisan —las dos son versiones nuevas— pero no pueden \
					commitear las dos: la segunda recibe 409 y reintenta con el numero siguiente.

					UNA ENTRADA DADA DE BAJA RESPONDE 409. Produciria una version que nadie va a \
					leer, porque la entrada ya salio del timeline. Para dejar constancia se \
					registra una entrada nueva.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Entrada enmendada, con su version "
					+ "nueva como vigente",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EntradaClinicaResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Cuerpo invalido, o enmienda sin motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La entrada no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "La entrada esta dada de baja, o la version quedo vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public EntradaClinicaResponse enmendar(

			@Parameter(description = "Entrada clinica que se enmienda", example = "312")
			@PathVariable long entradaClinicaId,

			@Valid @RequestBody EnmendarEntradaClinicaRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return EntradaClinicaResponse.from(entradaService.enmendar(
				apiActor.current(),
				entradaClinicaId,
				request.cuerpo(),
				request.motivo(),
				request.expectedVersion(),
				justificacion));
	}

	@DeleteMapping(path = POR_ENTRADA,
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "darDeBajaEntradaClinica",
			summary = "Dar de baja una entrada clinica",
			description = """
					Baja LOGICA con motivo obligatorio. NINGUNA VERSION SE BORRA: la entrada sale \
					del timeline y del listado por defecto, y sigue siendo consultable por su id \
					con todo su historico. Eso es lo que distingue "no lo muestres" de "no \
					existio" (regla maestra 10).

					REPETIR LA BAJA NO ES UN CONFLICTO: es el mismo pedido, y se responde 200 con \
					la entrada tal como quedo, con su motivo ORIGINAL intacto. Pisarlo con el \
					nuevo perderia el que explica la baja.

					Devuelve 200 con la entrada y no 204: quien da de baja necesita ver como \
					quedo —motivo, instante y version nueva— sin tener que pedirla de nuevo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Entrada dada de baja",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EntradaClinicaResponse.class))),
			@ApiResponse(responseCode = "400", description = "Falta el motivo de la baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La entrada no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La version enviada quedo vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public EntradaClinicaResponse darDeBaja(

			@Parameter(description = "Entrada clinica que se da de baja", example = "312")
			@PathVariable long entradaClinicaId,

			@Valid @RequestBody BajaDeEntradaClinicaRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		log.info("Baja de entrada clinica solicitada: entradaClinicaId={}", entradaClinicaId);
		return EntradaClinicaResponse.from(entradaService.darDeBaja(
				apiActor.current(),
				entradaClinicaId,
				request.motivo(),
				request.expectedVersion(),
				justificacion));
	}
}
