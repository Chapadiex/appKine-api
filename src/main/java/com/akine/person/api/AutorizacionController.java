package com.akine.person.api;

import com.akine.person.api.dto.AutorizacionElegibleResponse;
import com.akine.person.api.dto.AutorizacionResponse;
import com.akine.person.api.dto.CreateAutorizacionRequest;
import com.akine.person.api.dto.DeactivateDocumentoRequest;
import com.akine.person.api.dto.HistorialDeAutorizacionResponse;
import com.akine.person.api.dto.ResolverAutorizacionRequest;
import com.akine.person.api.dto.UpdateAutorizacionRequest;
import com.akine.person.api.dto.VincularDocumentoRequest;
import com.akine.person.application.AutorizacionAltaCommand;
import com.akine.person.application.AutorizacionEdicionCommand;
import com.akine.person.application.AutorizacionService;
import com.akine.person.application.AutorizacionView;
import com.akine.person.application.ConsumoDeAutorizacionService;
import com.akine.person.application.DocumentoEstadoFiltro;
import com.akine.person.application.ResolucionDeAutorizacionCommand;
import com.akine.person.domain.AccionSobreAutorizacion;
import com.akine.person.domain.EstadoAutorizacion;
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
import java.util.Locale;

/**
 * Autorizaciones de un financiador para un paciente (M17, AKINE-03.06).
 *
 * <h2>Autorizado no es consumido (RN-M17-001)</h2>
 *
 * <p>Estos endpoints REGISTRAN lo que el financiador otorgo y devuelven el saldo. <b>Ninguno
 * consume nada</b>, y eso sigue siendo cierto despues de AKINE-04.05: lo que cambio es que
 * {@code cantidadConsumida} <b>ya no vale siempre cero</b>. Quien la mueve es el cierre de una
 * sesion, por {@code person.spi.ConsumoDeAutorizaciones}, y el ledger que lo explica se lee en
 * {@code GET /api/v1/autorizaciones/&#123;id&#125;/movimientos}.
 *
 * <h2>El estado se mueve con una ACCION, nunca por asignacion</h2>
 *
 * <pre>
 *   POST                      registrar. Nace PENDIENTE, o APROBADA si el financiador ya
 *                             respondio por telefono.
 *   PUT                       corregir datos. NO cambia el estado ni la cobertura ni la practica.
 *   POST /{id}/estado         aplicar la respuesta: APROBAR, OBSERVAR o RECHAZAR.
 *   POST /{id}/documento      vincular el comprobante ya subido.
 *   DELETE                    baja logica: "nunca debio cargarse". No es rechazar.
 * </pre>
 *
 * <p>APROBADA y RECHAZADA son terminales. Es tambien lo que hace segura la <b>aprobacion
 * concurrente</b>: el segundo hilo encuentra la autorizacion ya resuelta y recibe 409.
 *
 * <h2>Autorizacion</h2>
 *
 * <p>Leer con {@code paciente:read} (DP-22); mutar con {@code paciente:manage} sobre la sede del contexto. 404
 * cross-tenant, 403 sin permiso, 409 para los invariantes, las transiciones y la version vieja.
 */
@RestController
@RequestMapping(
		path = "/api/v1/personas/{personaId}/autorizaciones",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Autorizaciones",
		description = "Autorizaciones de financiador para un paciente (M17). Registran y "
				+ "habilitan; el consumo clinico es una integracion posterior")
public class AutorizacionController {

	private static final Logger log = LoggerFactory.getLogger(AutorizacionController.class);

	private static final int HISTORIAL_TAMANO_MAXIMO = 100;

	private final AutorizacionService autorizacionService;
	private final ConsumoDeAutorizacionService consumoService;
	private final PersonApiActor apiActor;

	public AutorizacionController(
			AutorizacionService autorizacionService,
			ConsumoDeAutorizacionService consumoService,
			PersonApiActor apiActor) {

		this.autorizacionService = autorizacionService;
		this.consumoService = consumoService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listAutorizacionesDePaciente",
			summary = "Historial de autorizaciones de un paciente",
			description = """
					RF-M17-003 y RF-M17-006. Devuelve TODAS, mas nuevas primero, incluidas las \
					vencidas, las agotadas, las rechazadas y las dadas de baja.

					Cada fila trae saldo, vencida, agotada, habilita y diasParaVencer CALCULADOS \
					contra la fecha que se pregunta. Es lo que le permite al panel administrativo \
					mostrar los vencimientos sin que exista ningun job que mueva estados: \
					materializarlos dejaria autorizaciones vencidas que el sistema cree vigentes \
					el dia que el job no corra.

					estado filtra el CICLO DE VIDA (ACTIVA/INACTIVA), no el estado de la \
					autorizacion ni su vigencia. Por defecto trae todas.

					cantidadConsumida YA SE MUEVE desde AKINE-04.05: la descuenta el cierre de una \
					sesion (RF-M17-004). El ledger que explica cada movimiento se lee en \
					GET /api/v1/autorizaciones/{autorizacionId}/movimientos.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Historial de autorizaciones, mas nuevas primero",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = AutorizacionResponse.class)))),
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
	public ResponseEntity<List<AutorizacionResponse>> list(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Filtro por ciclo de vida. Si se omite, TODAS")
			@RequestParam(required = false) DocumentoEstadoFiltro estado,

			@Parameter(description = "Dia contra el que se calcula todo. Si se omite, hoy",
					example = "2026-09-03")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		List<AutorizacionResponse> autorizaciones =
				autorizacionService.listar(apiActor.current(), personaId, estado, fecha).stream()
						.map(AutorizacionResponse::de)
						.toList();

		return ResponseEntity.ok(autorizaciones);
	}

	@GetMapping("/{autorizacionId}")
	@Operation(
			operationId = "getAutorizacion",
			summary = "Saldo y estado de una autorizacion",
			description = """
					RF-M17-003: devuelve autorizadas, consumidas y restantes.

					cantidadConsumida YA NO vale siempre cero: desde AKINE-04.05 la descuenta el \
					cierre de una sesion (RF-M17-004). RN-M17-001 sigue separando autorizado de \
					consumido, y consultar este endpoint no descuenta nada. Para ver QUE gasto \
					cada unidad esta el ledger en \
					GET /api/v1/autorizaciones/{autorizacionId}/movimientos.

					habilita es el veredicto completo —activa, APROBADA, vigente y con saldo— y \
					consultarlo NO consume nada.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Autorizacion con su saldo",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AutorizacionResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo activo, o sin paciente:read",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La autorizacion no existe, es de otra organizacion o de otro "
							+ "paciente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AutorizacionResponse> get(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Identificador de la autorizacion", example = "77")
			@PathVariable long autorizacionId,

			@Parameter(description = "Dia contra el que se calcula todo. Si se omite, hoy",
					example = "2026-09-03")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		return ResponseEntity.ok(AutorizacionResponse.de(
				autorizacionService.ver(apiActor.current(), personaId, autorizacionId, fecha)));
	}

	@GetMapping("/{autorizacionId}/historial")
	@Operation(
			operationId = "getHistorialDeAutorizacion",
			summary = "Historial de estados de una autorizacion",
			description = """
					DP-23. Los hechos de la autorizacion, del mas viejo al mas nuevo: ALTA, \
					APROBACION, OBSERVACION, RECHAZO, MODIFICACION, DOCUMENTO, CONSUMO, \
					REVERSION_DE_CONSUMO y ANULACION, cada uno con estado anterior y nuevo, \
					actor, momento y motivo. Vive en una tabla propia append-only, escrita en la \
					misma transaccion que cada mutacion: no se arma leyendo la auditoria.

					EL VENCIMIENTO NO ES UN EVENTO. Vencer es funcion del reloj y nada lo \
					escribe: viaja CALCULADO contra fecha en vencida y vencidaDesde.

					Las autorizaciones cargadas antes del historial (V85) traen un solo ALTA \
					reconstruido con el estado que tenian ese dia, declarado en detalle.

					Mismo permiso que leer la autorizacion: pertenencia al tenant.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Pagina del historial",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(
									implementation = HistorialDeAutorizacionResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo activo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La autorizacion no existe, es de otra organizacion o de otro "
							+ "paciente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<HistorialDeAutorizacionResponse> historial(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Identificador de la autorizacion", example = "77")
			@PathVariable long autorizacionId,

			@Parameter(description = "Pagina, base cero")
			@RequestParam(defaultValue = "0") int page,

			@Parameter(description = "Tamano de pagina, acotado a " + HISTORIAL_TAMANO_MAXIMO)
			@RequestParam(defaultValue = "50") int size,

			@Parameter(description = "Dia contra el que se calcula el vencimiento. Si se omite, "
					+ "hoy", example = "2026-09-03")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		int pagina = Math.max(page, 0);
		int tamano = Math.min(Math.max(size, 1), HISTORIAL_TAMANO_MAXIMO);

		return ResponseEntity.ok(HistorialDeAutorizacionResponse.of(
				autorizacionService.historial(
						apiActor.current(), personaId, autorizacionId, pagina, tamano, fecha),
				pagina,
				tamano));
	}

	@GetMapping("/elegibles")
	@Operation(
			operationId = "listAutorizacionesElegibles",
			summary = "Que autorizaciones sirven hoy, y por que las otras no",
			description = """
					RF-M17-007, AKINE-04.05. Es el selector EXPLICABLE: devuelve TODAS las \
					autorizaciones activas del paciente con su veredicto, no solo las que sirven.

					POR QUE TODAS: decirle al mostrador "no hay ninguna" sin decirle que una \
					vencio anteayer y otra se agoto lo deja sin nada que hacer. Cada fila trae \
					motivoNoElegible —VENCIDA, AGOTADA, AUN_NO_VIGENTE o NO_APROBADA— que es null \
					exactamente cuando la autorizacion sirve.

					ORDEN: primero las que habilitan y, dentro de cada grupo, la que vence antes. \
					Es el orden en que hay que gastarlas, porque la que vence antes es la que se \
					pierde antes, y es el mismo criterio con el que el cierre de sesion elige a \
					cual descontarle.

					NO FILTRA POR PRACTICA, y hay que saberlo: una sesion declara su OFERTA y una \
					autorizacion es por PRACTICA. No existe ninguna tabla puente entre las dos —la \
					migracion de M27 la dejo afuera a proposito—, asi que comparar las dos cosas \
					seria comparar granularidades distintas. Cada fila trae su practicaId a la \
					vista para que quien conoce el caso pueda elegir. Unificarlas es 06.04.

					ESTO NO CONSUME NADA (RN-M17-001) y no persiste nada. Es idempotente.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Autorizaciones candidatas con su veredicto",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(schema = @Schema(
									implementation = AutorizacionElegibleResponse.class)))),
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
	public ResponseEntity<List<AutorizacionElegibleResponse>> elegibles(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Dia contra el que se evalua todo. Si se omite, hoy",
					example = "2026-09-19")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		List<AutorizacionElegibleResponse> elegibles =
				consumoService.elegibles(apiActor.current(), personaId, fecha).stream()
						.map(AutorizacionElegibleResponse::de)
						.toList();

		return ResponseEntity.ok(elegibles);
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createAutorizacion",
			summary = "Registrar una autorizacion",
			description = """
					RF-M17-001. Exige paciente:manage sobre la sede del contexto.

					EL CONVENIO SE CONGELA, no se referencia. El backend copia a la autorizacion \
					el codigo, el nombre y las TRES exigencias que el convenio tenia el dia \
					vigenciaDesde, y no las vuelve a leer nunca: cambiar el convenio manana no \
					reescribe con que reglas se autorizo al paciente el mes pasado.

					LA COPIA ES OPCIONAL, y esa es la diferencia con la cobertura. Si no hay \
					convenio vigente de esta sede con ese plan, o lo hay pero la practica no tiene \
					arancel cargado, el alta ENTRA IGUAL sin snapshot. El mostrador no se puede \
					quedar sin cargar un numero de autorizacion real porque la grilla de aranceles \
					este incompleta.

					estadoInicial admite PENDIENTE (default) o APROBADA. OBSERVADA y RECHAZADA no: \
					son la respuesta a un pedido y se aplican con POST /{id}/estado.

					SOLO SI NACE APROBADA se valida el solapamiento: dos autorizaciones aprobadas \
					de la misma cobertura y practica con vigencias que se pisan contarian el saldo \
					dos veces (409 autorizacion-superpuesta). Dos CONSECUTIVAS —renovar— si \
					conviven, y dos de practicas distintas tambien.

					cantidadConsumida nace en 0 y esta version no la mueve.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Autorizacion registrada. La cabecera Location apunta al recurso",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AutorizacionResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, vigencia invertida, o estado inicial no "
							+ "cargable",
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
					description = "La persona, la cobertura o la orden no existen, o son de otra "
							+ "organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La persona no es paciente (persona-sin-perfil-paciente), la "
							+ "cobertura o la orden estan dadas de baja (cobertura-inactiva, "
							+ "orden-inactiva), el numero ya existe (documento-numero-taken), o la "
							+ "aprobada se solapa con otra (autorizacion-superpuesta)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AutorizacionResponse> create(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Valid @RequestBody CreateAutorizacionRequest request) {

		log.info("Alta de autorizacion solicitada: personaId={} coberturaId={}",
				personaId, request.coberturaId());

		AutorizacionView creada = autorizacionService.registrar(
				apiActor.current(),
				personaId,
				new AutorizacionAltaCommand(
						request.coberturaId(),
						request.practicaId(),
						request.ordenMedicaId(),
						request.numero(),
						estadoDe(request.estadoInicial()),
						request.cantidadAutorizada(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						request.observaciones()));

		return ResponseEntity
				.created(URI.create(
						"/api/v1/personas/" + personaId + "/autorizaciones/" + creada.id()))
				.body(AutorizacionResponse.de(creada));
	}

	@PutMapping(path = "/{autorizacionId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateAutorizacion",
			summary = "Corregir una autorizacion",
			description = """
					Exige paciente:manage sobre la sede del contexto. Edicion parcial: lo que no \
					viaja no se toca.

					LA COBERTURA, LA PRACTICA Y EL ESTADO NO ESTAN EN EL CUERPO, y ninguno es un \
					olvido. Las dos primeras son inmutables: cambiarlas es OTRA autorizacion, \
					porque reescribirian contra que se autorizo. El estado se mueve con \
					POST /{id}/estado, que recibe una ACCION.

					Tampoco esta el snapshot del convenio: es una copia congelada, y una copia que \
					se puede editar no es una copia.

					Si la autorizacion ya esta APROBADA, mover sus fechas revalida el solapamiento \
					bajo el mismo lock que la aprobacion: estirar una vigencia hasta pisar a la \
					siguiente es exactamente el mismo riesgo.

					expectedVersion es obligatorio.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Autorizacion actualizada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AutorizacionResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, vigencia invertida, o cantidad por debajo de "
							+ "lo ya consumido",
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
					description = "La autorizacion o la orden no existen, o son de otro paciente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Autorizacion dada de baja (autorizacion-inactiva), version "
							+ "desactualizada (concurrent-modification), numero en uso (documento-numero-taken) o "
							+ "la edicion crea un solapamiento (autorizacion-superpuesta)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AutorizacionResponse> update(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Identificador de la autorizacion", example = "77")
			@PathVariable long autorizacionId,

			@Valid @RequestBody UpdateAutorizacionRequest request) {

		AutorizacionView actualizada = autorizacionService.editar(
				apiActor.current(),
				personaId,
				autorizacionId,
				new AutorizacionEdicionCommand(
						request.numero(),
						request.ordenMedicaId(),
						request.cantidadAutorizada(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						request.observaciones(),
						request.expectedVersion()));

		return ResponseEntity.ok(AutorizacionResponse.de(actualizada));
	}

	@PostMapping(path = "/{autorizacionId}/estado", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "resolverAutorizacion",
			summary = "Aplicar la respuesta del financiador",
			description = """
					RF-M17-001. Exige paciente:manage sobre la sede del contexto.

					RECIBE UNA ACCION, NO UN ESTADO DESTINO. Con un estado destino, pedir APROBADA \
					sobre una autorizacion RECHAZADA seria una peticion valida que el servidor \
					tiene que rechazar por semantica, y el cliente podria construir cualquier \
					transicion imaginable. Con una accion, la unica forma de llegar a APROBADA es \
					APROBAR, y el conjunto de transiciones posibles queda del lado del backend.

					Transiciones: PENDIENTE y OBSERVADA admiten las tres acciones. APROBADA y \
					RECHAZADA son TERMINALES y responden 409 \
					autorizacion-transicion-no-permitida. Corregir una decision tomada es dar de \
					baja la autorizacion y cargar otra: si una aprobada pudiera volver a \
					PENDIENTE, el saldo que ya se conto para atender a alguien desapareceria \
					retroactivamente.

					APROBACION CONCURRENTE: es el mismo mecanismo. El segundo en llegar encuentra \
					la autorizacion ya resuelta y recibe 409, no un 200 que aprueba dos veces.

					AUTORIZACION PARCIAL: al APROBAR, cantidadAutorizada y las vigencias pisan a \
					las declaradas al cargar. El financiador puede otorgar seis sesiones donde se \
					pidieron veinte, o una ventana mas corta: lo que vale es lo que concedio.

					motivo es OBLIGATORIO al OBSERVAR y al RECHAZAR. Sin el, el mostrador no sabe \
					que corregir.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Autorizacion resuelta",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AutorizacionResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Accion desconocida, o falta el motivo al observar o rechazar",
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
					description = "La autorizacion no existe, es de otra organizacion o de otro "
							+ "paciente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Estado terminal "
							+ "(autorizacion-transicion-no-permitida), autorizacion dada de baja "
							+ "(autorizacion-inactiva), version desactualizada (concurrent-modification) o la "
							+ "aprobacion se solapa con otra (autorizacion-superpuesta)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AutorizacionResponse> resolver(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Identificador de la autorizacion", example = "77")
			@PathVariable long autorizacionId,

			@Valid @RequestBody ResolverAutorizacionRequest request) {

		AutorizacionView resuelta = autorizacionService.resolver(
				apiActor.current(),
				personaId,
				autorizacionId,
				new ResolucionDeAutorizacionCommand(
						accionDe(request.accion()),
						request.motivo(),
						request.cantidadAutorizada(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						request.expectedVersion()));

		return ResponseEntity.ok(AutorizacionResponse.de(resuelta));
	}

	@PostMapping(path = "/{autorizacionId}/documento", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "vincularDocumentoDeAutorizacion",
			summary = "Vincular el comprobante de la autorizacion",
			description = """
					RF-M17-002 y RF-M25-006. Exige paciente:manage sobre la sede del contexto.

					ACA NO SE SUBE NADA. El archivo lo sube POST /personas/{personaId}/adjuntos, \
					que ya valida tipo y tamano, genera una clave de almacenamiento opaca que \
					nunca sale del backend (RN-M25-002) y autoriza cada descarga.

					EL ADJUNTO TIENE QUE SER DE ESTA PERSONA: vincular el de otro paciente lo \
					volveria descargable desde esta ruta y evadiria el control de acceso que el \
					adjunto hereda de su persona (RN-M25-003).

					adjuntoId null desvincula.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Vinculo actualizado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AutorizacionResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo activo o sin paciente:manage",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La autorizacion o el adjunto no existen, o son de otro paciente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La autorizacion o el adjunto estan dados de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AutorizacionResponse> vincularDocumento(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Identificador de la autorizacion", example = "77")
			@PathVariable long autorizacionId,

			@Valid @RequestBody VincularDocumentoRequest request) {

		return ResponseEntity.ok(AutorizacionResponse.de(autorizacionService.vincularDocumento(
				apiActor.current(), personaId, autorizacionId, request.adjuntoId())));
	}

	@DeleteMapping(path = "/{autorizacionId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "deactivateAutorizacion",
			summary = "Dar de baja una autorizacion",
			description = """
					Baja LOGICA con motivo obligatorio. Exige paciente:manage sobre la sede del \
					contexto.

					DAR DE BAJA NO ES RECHAZAR, Y NO ES VENCER. Rechazar es la respuesta del \
					financiador y se hace con POST /{id}/estado: la autorizacion queda viva y \
					explica por que no se pudo atender. Vencer es que paso la fecha, y se calcula \
					al leer. Dar de baja significa "esta autorizacion nunca debio cargarse".

					No borra nada (regla maestra 10). La autorizacion sigue siendo legible con \
					estado INACTIVA, y su numero se puede volver a usar.

					La baja NO toma el lock de solapamiento: quitar una fila del conjunto activo \
					nunca puede crear un solapamiento.

					No hay reactivacion. Una autorizacion que vuelve es un alta nueva.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Autorizacion dada de baja"),
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
					description = "La autorizacion no existe, es de otra organizacion o de otro "
							+ "paciente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ya estaba dada de baja (autorizacion-already-inactive)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivate(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Identificador de la autorizacion", example = "77")
			@PathVariable long autorizacionId,

			@Valid @RequestBody DeactivateDocumentoRequest request) {

		autorizacionService.darDeBaja(
				apiActor.current(), personaId, autorizacionId, request.reason());
		return ResponseEntity.noContent().build();
	}

	// =================================================================================
	// Traduccion de texto a enum
	// =================================================================================

	/**
	 * El estado inicial declarado, o 400.
	 *
	 * <p>El DTO lo recibe como {@code String} y no como el enum de dominio, mismo criterio que el
	 * tipo de cobertura en 03.04: si lo recibiera tipado, un valor desconocido saldria como el
	 * error generico de deserializacion de Jackson, que dice "no se pudo leer el cuerpo" y no cual
	 * campo esta mal.
	 */
	private static EstadoAutorizacion estadoDe(String estado) {
		if (estado == null) {
			return EstadoAutorizacion.PENDIENTE;
		}
		try {
			return EstadoAutorizacion.valueOf(estado.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException desconocido) {
			throw new IllegalArgumentException(
					"Una autorizacion se carga PENDIENTE o APROBADA");
		}
	}

	private static AccionSobreAutorizacion accionDe(String accion) {
		if (accion == null) {
			throw new IllegalArgumentException("La resolucion necesita declarar la accion");
		}
		try {
			return AccionSobreAutorizacion.valueOf(accion.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException desconocida) {
			throw new IllegalArgumentException(
					"La accion tiene que ser APROBAR, OBSERVAR o RECHAZAR");
		}
	}
}
