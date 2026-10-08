package com.akine.person.api;

import com.akine.person.api.dto.CreateOrdenRequest;
import com.akine.person.api.dto.DeactivateDocumentoRequest;
import com.akine.person.api.dto.OrdenResponse;
import com.akine.person.api.dto.UpdateOrdenRequest;
import com.akine.person.api.dto.VincularDocumentoRequest;
import com.akine.person.application.DocumentoEstadoFiltro;
import com.akine.person.application.OrdenAltaCommand;
import com.akine.person.application.OrdenEdicionCommand;
import com.akine.person.application.OrdenMedicaService;
import com.akine.person.application.OrdenView;
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
 * Ordenes medicas de un paciente (M17, AKINE-03.06).
 *
 * <h2>Por que la ruta cuelga de la persona</h2>
 *
 * <p>Porque una orden sin paciente no existe, y la ruta es el lugar donde esa pertenencia no se
 * puede contradecir: el paciente nunca viaja en el cuerpo, asi que no hay request cuya URL diga una
 * cosa y cuyo cuerpo diga otra. Una orden de otro paciente responde <b>404</b> bajo esta ruta
 * aunque exista y sea del mismo tenant. Mismo criterio que las coberturas en 03.04.
 *
 * <h2>Cuatro operaciones y cuatro significados que no se pueden mezclar</h2>
 *
 * <pre>
 *   POST                      registrar la orden que el paciente presento.
 *   PUT                       corregir sus datos. Una orden VENCIDA si se edita —arreglar la
 *                             fecha mal tipeada es el caso normal—; una dada de baja no.
 *   POST /{id}/documento      vincular el escaneo ya subido. NO sube nada: RF-M17-002.
 *   DELETE                    baja logica: "esta orden nunca debio cargarse". No borra nada.
 * </pre>
 *
 * <p><b>Vencer no es ninguna de las cuatro.</b> El vencimiento se calcula al leer, la fila no se
 * toca, y la orden se sigue listando: "un documento vencido no desaparece".
 *
 * <h2>Autorizacion</h2>
 *
 * <p>Leer con contexto de organizacion —por pertenencia, igual que el padron en 03.01: el
 * profesional que va a atender necesita saber si el paciente trajo la orden—. Mutar con
 * {@code paciente:manage} evaluado sobre la sede del contexto. 404 cross-tenant, 403 sin permiso,
 * 409 para el ciclo de vida y para la version desactualizada.
 */
@RestController
@RequestMapping(
		path = "/api/v1/personas/{personaId}/ordenes",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Ordenes medicas",
		description = "Ordenes medicas presentadas por un paciente (M17). Documentacion "
				+ "ADMINISTRATIVA: no reemplaza el registro clinico (RN-M17-004)")
public class OrdenMedicaController {

	private static final Logger log = LoggerFactory.getLogger(OrdenMedicaController.class);

	private final OrdenMedicaService ordenService;
	private final PersonApiActor apiActor;

	public OrdenMedicaController(OrdenMedicaService ordenService, PersonApiActor apiActor) {
		this.ordenService = ordenService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listOrdenesDePaciente",
			summary = "Historial de ordenes medicas de un paciente",
			description = """
					Devuelve TODAS las ordenes del paciente, mas nuevas primero, incluidas las \
					vencidas y las dadas de baja. Es deliberado: "un documento vencido no \
					desaparece" es requisito de M17, y sin el historial no se puede explicar con \
					que papel se atendio al paciente el mes pasado.

					estado filtra el CICLO DE VIDA y por defecto trae todas. NO filtra por \
					vigencia: una orden ACTIVA vencida es el caso normal.

					fecha es el dia contra el que se calculan vigente, vencida y diasParaVencer. \
					Si se omite, hoy. diasParaVencer es la alerta de RF-M17-006 y se calcula al \
					leer: no existe ningun job que mueva estados.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Historial de ordenes, mas nuevas primero",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = OrdenResponse.class)))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo activo, o sin paciente:read",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La persona no existe o es de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<OrdenResponse>> list(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Filtro por ciclo de vida. Si se omite, TODAS")
			@RequestParam(required = false) DocumentoEstadoFiltro estado,

			@Parameter(
					description = "Dia contra el que se calcula la vigencia. Si se omite, hoy",
					example = "2026-09-03")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		List<OrdenResponse> ordenes =
				ordenService.listar(apiActor.current(), personaId, estado, fecha).stream()
						.map(OrdenResponse::de)
						.toList();

		return ResponseEntity.ok(ordenes);
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createOrdenMedica",
			summary = "Registrar una orden medica",
			description = """
					RF-M17-001. Exige paciente:manage sobre la sede del contexto.

					LA PERSONA TIENE QUE SER PACIENTE. Una Persona no es un Paciente (RF-M07-010): \
					si no tiene perfil vigente responde 409 persona-sin-perfil-paciente.

					EL EMISOR ES TEXTO LIBRE. El medico que firma es externo al centro y no esta \
					en ningun catalogo del tenant: exigir que exista como fila dejaria al \
					mostrador sin poder cargar la orden que el paciente trajo hoy. La matricula es \
					opcional, y eso resuelve el caso borde del documento ilegible sin rechazar la \
					carga.

					LA COBERTURA ES OPCIONAL. Una prescripcion la firma un medico, no un \
					financiador: sin coberturaId la orden vale para cualquiera, y la elegibilidad \
					la acepta para todas.

					vigenciaDesde omitido = la fecha de emision. Nunca puede ser anterior a ella: \
					una orden no puede valer antes de haberse escrito, y eso lo exige tambien la \
					base.

					indicacion es la TRANSCRIPCION administrativa de lo que dice el papel. NO es \
					registro clinico (RN-M17-004) y no reemplaza nada de la historia clinica.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Orden registrada. La cabecera Location apunta al recurso",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OrdenResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, vigencia invertida, o vigencia anterior a la "
							+ "fecha de emision",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo activo o sin paciente:manage",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La persona o la cobertura no existen, o son de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La persona no es paciente (persona-sin-perfil-paciente), esta "
							+ "dada de baja (persona-inactiva), o ya hay una orden vigente con ese "
							+ "numero (documento-numero-taken)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<OrdenResponse> create(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Valid @RequestBody CreateOrdenRequest request) {

		log.info("Alta de orden medica solicitada: personaId={}", personaId);

		OrdenView creada = ordenService.registrar(
				apiActor.current(),
				personaId,
				new OrdenAltaCommand(
						request.coberturaId(),
						request.numero(),
						request.profesionalEmisor(),
						request.matriculaEmisor(),
						request.fechaEmision(),
						request.indicacion(),
						request.sesionesPrescriptas(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						request.observaciones()));

		return ResponseEntity
				.created(URI.create("/api/v1/personas/" + personaId + "/ordenes/" + creada.id()))
				.body(OrdenResponse.de(creada));
	}

	@PutMapping(path = "/{ordenId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateOrdenMedica",
			summary = "Corregir una orden medica",
			description = """
					Exige paciente:manage sobre la sede del contexto. Edicion parcial: lo que no \
					viaja no se toca.

					LA PERSONA Y LA COBERTURA NO SE PUEDEN CAMBIAR, y por eso no estan en el \
					cuerpo. Mudar una orden de paciente reescribiria quien presento que papel.

					UNA ORDEN VENCIDA SI SE EDITA. Corregir la fecha de vigencia mal tipeada es el \
					caso normal, y bloquearlo obligaria a dar de baja y recargar. Una orden dada de \
					baja no se edita: 409 orden-inactiva.

					expectedVersion es obligatorio: una version vieja responde 409 en vez de pisar \
					el cambio ajeno en silencio.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Orden actualizada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OrdenResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, vigencia invertida o anterior a la emision",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo activo o sin paciente:manage",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La orden no existe, es de otra organizacion o de otro paciente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Orden dada de baja (orden-inactiva), version desactualizada "
							+ "(concurrent-modification) o numero en uso (documento-numero-taken)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<OrdenResponse> update(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Identificador de la orden", example = "51")
			@PathVariable long ordenId,

			@Valid @RequestBody UpdateOrdenRequest request) {

		OrdenView actualizada = ordenService.editar(
				apiActor.current(),
				personaId,
				ordenId,
				new OrdenEdicionCommand(
						request.numero(),
						request.profesionalEmisor(),
						request.matriculaEmisor(),
						request.fechaEmision(),
						request.indicacion(),
						request.sesionesPrescriptas(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						request.observaciones(),
						request.expectedVersion()));

		return ResponseEntity.ok(OrdenResponse.de(actualizada));
	}

	@PostMapping(path = "/{ordenId}/documento", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "vincularDocumentoDeOrden",
			summary = "Vincular el escaneo de la orden",
			description = """
					RF-M17-002 y RF-M25-006. Exige paciente:manage sobre la sede del contexto.

					ACA NO SE SUBE NADA. El archivo lo sube POST /personas/{personaId}/adjuntos, \
					que ya valida tipo y tamano, genera una clave de almacenamiento opaca que \
					nunca sale del backend (RN-M25-002) y autoriza cada descarga. Este endpoint \
					solo guarda el vinculo: un segundo mecanismo de carga significaria una segunda \
					validacion, una segunda ruta de descarga y una segunda superficie de path \
					traversal.

					EL ADJUNTO TIENE QUE SER DE ESTA PERSONA. Vincular el de otro paciente lo \
					volveria descargable desde esta ruta y evadiria el control de acceso que el \
					adjunto hereda de su persona (RN-M25-003).

					adjuntoId null DESVINCULA, y es legitimo: el operador subio el escaneo \
					equivocado y lo saca sin dar de baja la orden entera.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Vinculo actualizado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OrdenResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo activo o sin paciente:manage",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La orden o el adjunto no existen, o son de otro paciente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La orden o el adjunto estan dados de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<OrdenResponse> vincularDocumento(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Identificador de la orden", example = "51")
			@PathVariable long ordenId,

			@Valid @RequestBody VincularDocumentoRequest request) {

		return ResponseEntity.ok(OrdenResponse.de(ordenService.vincularDocumento(
				apiActor.current(), personaId, ordenId, request.adjuntoId())));
	}

	@DeleteMapping(path = "/{ordenId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "deactivateOrdenMedica",
			summary = "Dar de baja una orden medica",
			description = """
					Baja LOGICA con motivo obligatorio. Exige paciente:manage sobre la sede del \
					contexto.

					NO ES LO MISMO QUE VENCER. Una orden vencida sigue activa, se sigue listando y \
					se sigue pudiendo corregir: "un documento vencido no desaparece". Dar de baja \
					significa "esta orden nunca debio cargarse".

					No borra nada y no toca ningun hecho ya registrado (regla maestra 10). La \
					orden sigue siendo legible con estado INACTIVA, y las autorizaciones que la \
					referencian quedan intactas.

					No hay reactivacion. Una orden que vuelve es un alta nueva.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Orden dada de baja"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo de la baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo activo o sin paciente:manage",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La orden no existe, es de otra organizacion o de otro paciente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La orden ya estaba dada de baja (orden-already-inactive)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivate(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Identificador de la orden", example = "51")
			@PathVariable long ordenId,

			@Valid @RequestBody DeactivateDocumentoRequest request) {

		ordenService.darDeBaja(apiActor.current(), personaId, ordenId, request.reason());
		return ResponseEntity.noContent().build();
	}
}
