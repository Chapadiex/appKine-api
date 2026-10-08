package com.akine.person.api;

import com.akine.person.api.dto.CoberturaResponse;
import com.akine.person.api.dto.CreateCoberturaRequest;
import com.akine.person.api.dto.DeactivateCoberturaRequest;
import com.akine.person.api.dto.MarcarCoberturaPrincipalRequest;
import com.akine.person.api.dto.SeleccionDeCoberturaResponse;
import com.akine.person.api.dto.UpdateCoberturaRequest;
import com.akine.person.application.CoberturaAltaCommand;
import com.akine.person.application.CoberturaEdicionCommand;
import com.akine.person.application.CoberturaEstadoFiltro;
import com.akine.person.application.CoberturaPacienteService;
import com.akine.person.application.CoberturaView;
import com.akine.person.domain.TipoCobertura;
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
 * Coberturas de un paciente (M08, AKINE-03.04).
 *
 * <h2>Por que la ruta cuelga de la persona</h2>
 *
 * <p>Porque una cobertura sin paciente no existe, y la ruta es el lugar donde esa pertenencia no
 * se puede contradecir: el paciente nunca viaja en el cuerpo, asi que no hay request cuya URL diga
 * una cosa y cuyo cuerpo diga otra. Una cobertura de otro paciente responde <b>404</b> bajo esta
 * ruta aunque exista y sea del mismo tenant — resolverla seria una respuesta que miente sobre de
 * quien es. Mismo criterio que los planes bajo su financiador en M15.
 *
 * <h2>Lo que devuelve es una COPIA, no una lectura del catalogo</h2>
 *
 * <p>El nombre del financiador y del plan que salen de estos endpoints son los que el catalogo
 * decia <b>el dia en que la cobertura se cargo</b>, guardados en columnas propias. Si despues
 * renombraron el plan, esta API sigue diciendo el nombre viejo, y eso es el requisito: una
 * cobertura firmada ayer no cambia porque hoy editen el plan (RN-M08-003).
 *
 * <h2>Cuatro operaciones y cuatro significados que no se pueden mezclar</h2>
 *
 * <pre>
 *   POST                      agregar una cobertura. Congela el plan.
 *   PUT                       editar lo NO historico, y FINALIZAR LA VIGENCIA con vigenciaHasta.
 *                             La cobertura queda ACTIVA.
 *   POST /{id}/principal      elegir la preferida. Invariante ENTRE filas, por eso no es un campo
 *                             del PUT.
 *   DELETE                    baja logica: "nunca debio cargarse". No borra nada.
 * </pre>
 *
 * <h2>Autorizacion</h2>
 *
 * <p>Leer con {@code paciente:read} (AKINE-DU-6, DP-22), igual que el padron: un
 * profesional que va a atender necesita saber con que cobertura viene el paciente—. Mutar con
 * {@code paciente:manage} evaluado sobre la sede del contexto. 404 cross-tenant, 403 sin permiso,
 * 409 para los invariantes y para la version desactualizada.
 */
@RestController
@RequestMapping(
		path = "/api/v1/personas/{personaId}/coberturas",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Coberturas del paciente",
		description = "Coberturas particulares y financiadas de un paciente (M08)")
public class CoberturaPacienteController {

	private static final Logger log = LoggerFactory.getLogger(CoberturaPacienteController.class);

	private final CoberturaPacienteService coberturaService;
	private final PersonApiActor apiActor;

	public CoberturaPacienteController(
			CoberturaPacienteService coberturaService, PersonApiActor apiActor) {

		this.coberturaService = coberturaService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listCoberturasDePaciente",
			summary = "Historial de coberturas de un paciente",
			description = """
					Devuelve TODAS las coberturas del paciente, mas nuevas primero, incluidas las \
					dadas de baja y las de vigencia vencida. Es deliberado: RN-M08-003 y la regla \
					maestra 10 exigen que se pueda explicar con que cobertura se atendio al \
					paciente el mes pasado.

					estado filtra el CICLO DE VIDA y por defecto trae todas. NO filtra por \
					vigencia: una cobertura ACTIVA con la vigencia cerrada es el caso normal de \
					un paciente que cambio de obra social, y se devuelve con vigente = false.

					fecha es el dia contra el que se calculan vigente y credencialVencida. Si se \
					omite, hoy.

					El texto del financiador y del plan es la COPIA congelada al firmar, no una \
					lectura del catalogo de hoy.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Historial de coberturas, mas nuevas primero",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = CoberturaResponse.class)))),
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
	public ResponseEntity<List<CoberturaResponse>> list(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Filtro por ciclo de vida. Si se omite, TODAS")
			@RequestParam(required = false) CoberturaEstadoFiltro estado,

			@Parameter(
					description = "Dia contra el que se calcula vigente. Si se omite, hoy",
					example = "2026-09-02")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		List<CoberturaResponse> coberturas =
				coberturaService.listar(apiActor.current(), personaId, estado, fecha).stream()
						.map(CoberturaResponse::de)
						.toList();

		return ResponseEntity.ok(coberturas);
	}

	@GetMapping("/seleccion")
	@Operation(
			operationId = "resolverCoberturaParaAtencion",
			summary = "Que coberturas se pueden elegir para atender ese dia",
			description = """
					Resuelve la seleccion de RF-M08-004 y RF-M08-005: la cobertura principal \
					vigente ese dia, todas las vigentes, y PARTICULAR, que siempre esta \
					disponible.

					particularSiempreDisponible es SIEMPRE true, y no es un campo inutil: es \
					RN-M08-001 dicho en el contrato. Un paciente con obra social puede pagar \
					particular, que es exactamente RF-M08-005, y si Particular no viajara aca \
					cada pantalla tendria que acordarse de agregarlo.

					La principal es determinista: no puede haber dos, y eso lo hace cumplir un \
					lock y no un desempate arbitrario.

					ESTO NO PERSISTE NADA y no afirma que la prestacion sea facturable a esa \
					cobertura. RN-M08-004: la cobertura del paciente no implica que el \
					consultorio tenga convenio con ese financiador. Resolver la elegibilidad por \
					oferta y convenio es RF-M08-006 y necesita M16 y M17, que no existen.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Coberturas elegibles ese dia. La lista vacia es un estado normal",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = SeleccionDeCoberturaResponse.class))),
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
	public ResponseEntity<SeleccionDeCoberturaResponse> seleccion(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Dia de la atencion. Si se omite, hoy", example = "2026-09-02")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		return ResponseEntity.ok(SeleccionDeCoberturaResponse.de(
				coberturaService.resolverParaAtencion(apiActor.current(), personaId, fecha)));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createCoberturaDePaciente",
			summary = "Agregar una cobertura al paciente",
			description = """
					Exige paciente:manage sobre la sede del contexto.

					LA PERSONA TIENE QUE SER PACIENTE. Una Persona no es un Paciente (RF-M07-010): \
					si no tiene perfil de paciente vigente responde 409 \
					persona-sin-perfil-paciente. Cargarle la obra social a un contacto \
					administrativo no significa nada.

					EL PLAN SE CONGELA, no se referencia. El backend copia a la cobertura el \
					codigo, el nombre, el copago y las exigencias que el plan tenia el dia \
					vigenciaDesde, y no los vuelve a leer nunca. Renombrar el plan manana no \
					cambia esta cobertura.

					Se congela contra vigenciaDesde y no contra hoy: la pregunta es si ese plan se \
					podia elegir el dia en que la cobertura empieza a valer.

					Un plan que no se puede elegir responde 409 plan-no-seleccionable, y las cinco \
					causas —no existe, es ajeno, esta dado de baja, su financiador esta dado de \
					baja, o la fecha cae fuera de su vigencia— responden IGUAL, para no convertir \
					el endpoint en un oraculo del catalogo de otro centro.

					PARTICULAR no lleva plan ni credencial: es la ausencia de plan financiado y \
					siempre esta disponible (RN-M08-001).

					Dos coberturas activas del MISMO plan con vigencias solapadas son un \
					duplicado: 409 cobertura-superpuesta. Dos de financiadores DISTINTOS \
					solapadas son legitimas y no se rechazan.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Cobertura creada. La cabecera Location apunta al recurso",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CoberturaResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, vigencia invertida, tipo desconocido, o "
							+ "falta el numero de afiliado que el plan exige",
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
					description = "La persona no existe o es de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La persona no es paciente (persona-sin-perfil-paciente), esta "
							+ "dada de baja (persona-inactiva), el plan no se puede elegir "
							+ "(plan-no-seleccionable), ya hay una cobertura del mismo plan "
							+ "solapada (cobertura-superpuesta) o ya hay otra principal vigente "
							+ "(cobertura-principal-superpuesta)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CoberturaResponse> create(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Valid @RequestBody CreateCoberturaRequest request) {

		log.info("Alta de cobertura solicitada: personaId={} tipo={}", personaId, request.tipo());

		CoberturaView creada = coberturaService.agregar(
				apiActor.current(),
				personaId,
				new CoberturaAltaCommand(
						tipoDe(request.tipo()),
						request.planId(),
						request.numeroAfiliado(),
						request.credencialVigenciaHasta(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						request.principal(),
						request.observaciones()));

		return ResponseEntity
				.created(URI.create(
						"/api/v1/personas/" + personaId + "/coberturas/" + creada.id()))
				.body(CoberturaResponse.de(creada));
	}

	@PutMapping(path = "/{coberturaId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateCoberturaDePaciente",
			summary = "Editar una cobertura, o finalizar su vigencia",
			description = """
					Exige paciente:manage sobre la sede del contexto. Edicion parcial: lo que no \
					viaja no se toca.

					EL PLAN NO SE PUEDE CAMBIAR, y por eso no esta en el cuerpo. Cambiar de plan \
					es OTRA cobertura: se finaliza la vigente y se agrega la nueva. Editarla en el \
					lugar reescribiria con que cobertura se atendio al paciente el mes pasado \
					(RN-M08-003).

					MANDAR vigenciaHasta ES FINALIZAR LA VIGENCIA (RF-M08-003). La cobertura queda \
					ACTIVA y consultable, y deja de aplicar despues de esa fecha. NO es dar de \
					baja, que es el DELETE y significa otra cosa.

					Una cobertura dada de baja no se edita: 409 cobertura-inactiva. Reabrir la \
					ficha de algo dado de baja reescribiria el historico.

					expectedVersion es obligatorio: una version vieja responde 409 en vez de pisar \
					el cambio ajeno en silencio.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Cobertura actualizada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CoberturaResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos o vigencia invertida",
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
					description = "La cobertura no existe, es de otra organizacion o de otro paciente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Cobertura dada de baja (cobertura-inactiva), version "
							+ "desactualizada (concurrent-modification) o la edicion crea un solapamiento "
							+ "(cobertura-superpuesta)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CoberturaResponse> update(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Identificador de la cobertura", example = "412")
			@PathVariable long coberturaId,

			@Valid @RequestBody UpdateCoberturaRequest request) {

		CoberturaView actualizada = coberturaService.editar(
				apiActor.current(),
				personaId,
				coberturaId,
				new CoberturaEdicionCommand(
						request.numeroAfiliado(),
						request.credencialVigenciaHasta(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						request.observaciones(),
						request.expectedVersion()));

		return ResponseEntity.ok(CoberturaResponse.de(actualizada));
	}

	@PostMapping(path = "/{coberturaId}/principal", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "marcarCoberturaPrincipal",
			summary = "Elegir o quitar la cobertura principal del paciente",
			description = """
					RF-M08-004. Exige paciente:manage sobre la sede del contexto.

					Operacion propia y no un campo del PUT porque es un invariante ENTRE filas y \
					no un dato de esta. Meterlo en la edicion obligaria a que el cliente mande la \
					version de una fila para modificar el estado de otra.

					NO DESMARCA A LA ANTERIOR EN SILENCIO. Si ya hay una principal activa con la \
					vigencia solapada responde 409 cobertura-principal-superpuesta con su id, \
					para que la pantalla ofrezca finalizarla. Un click que cambia dos coberturas \
					deja una que despues nadie puede explicar.

					principal = false desmarca, y es una operacion legitima: un paciente puede \
					tener coberturas sin ninguna preferida.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Cobertura actualizada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CoberturaResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta declarar el valor de principal",
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
					description = "La cobertura no existe, es de otra organizacion o de otro paciente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ya hay otra cobertura principal vigente en ese periodo "
							+ "(cobertura-principal-superpuesta) o la cobertura esta dada de baja "
							+ "(cobertura-inactiva)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CoberturaResponse> marcarPrincipal(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Identificador de la cobertura", example = "412")
			@PathVariable long coberturaId,

			@Valid @RequestBody MarcarCoberturaPrincipalRequest request) {

		CoberturaView actualizada = coberturaService.marcarPrincipal(
				apiActor.current(), personaId, coberturaId, request.principal());

		return ResponseEntity.ok(CoberturaResponse.de(actualizada));
	}

	@DeleteMapping(path = "/{coberturaId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "deactivateCoberturaDePaciente",
			summary = "Dar de baja una cobertura",
			description = """
					Baja LOGICA con motivo obligatorio. Exige paciente:manage sobre la sede del \
					contexto.

					NO ES LO MISMO QUE FINALIZAR LA VIGENCIA. Finalizar es "el paciente cambio de \
					obra social" y se hace con el PUT: la cobertura queda ACTIVA y sigue \
					explicando el pasado. Dar de baja es "esta cobertura nunca debio cargarse".

					No borra nada y no toca ningun hecho ya registrado: RN-M08-003 y regla \
					maestra 10. La cobertura sigue siendo legible con estado INACTIVA.

					La baja tambien desmarca la principal: dejar como preferida una cobertura dada \
					de baja haria que la seleccion del dia siguiente apunte a algo que ya no \
					existe operativamente.

					No hay reactivacion. Una cobertura que vuelve es un alta nueva.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Cobertura dada de baja"),
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
					description = "La cobertura no existe, es de otra organizacion o de otro paciente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La cobertura ya estaba dada de baja (cobertura-already-inactive)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivate(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Identificador de la cobertura", example = "412")
			@PathVariable long coberturaId,

			@Valid @RequestBody DeactivateCoberturaRequest request) {

		coberturaService.darDeBaja(apiActor.current(), personaId, coberturaId, request.reason());
		return ResponseEntity.noContent().build();
	}

	/**
	 * El tipo declarado, o 400.
	 *
	 * <p>El DTO lo recibe como {@code String} y no como el enum de dominio: si lo recibiera tipado,
	 * un valor desconocido saldria como el error generico de deserializacion de Jackson, que dice
	 * "no se pudo leer el cuerpo" y no cual campo esta mal.
	 */
	private static TipoCobertura tipoDe(String tipo) {
		try {
			return TipoCobertura.valueOf(tipo.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException desconocido) {
			throw new IllegalArgumentException(
					"El tipo de cobertura tiene que ser PARTICULAR o FINANCIADA");
		}
	}
}
