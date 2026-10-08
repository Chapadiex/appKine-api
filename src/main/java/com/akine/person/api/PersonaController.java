package com.akine.person.api;

import com.akine.person.api.dto.ActivarPerfilPacienteRequest;
import com.akine.person.api.dto.BajaDePerfilPacienteRequest;
import com.akine.person.api.dto.BajaDePersonaRequest;
import com.akine.person.api.dto.CreatePersonaRequest;
import com.akine.person.api.dto.PersonaPageResponse;
import com.akine.person.api.dto.PersonaResponse;
import com.akine.person.api.dto.ResumenDePersonaResponse;
import com.akine.person.api.dto.UpdatePersonaRequest;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.PerfilFiltro;
import com.akine.person.application.PerfilPacienteService;
import com.akine.person.application.PersonaAltaCommand;
import com.akine.person.application.PersonaBusqueda;
import com.akine.person.application.PersonaEdicionCommand;
import com.akine.person.application.PersonaEstadoFiltro;
import com.akine.person.application.PersonaPagina;
import com.akine.person.application.PersonaService;
import com.akine.person.application.PersonaView;
import com.akine.person.application.ResumenDePersonaService;
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

/**
 * El padron de personas de una organizacion (M07).
 *
 * <h2>Por que la ruta no lleva ni la organizacion ni la sede</h2>
 *
 * <p>{@code /api/v1/personas} y no {@code /api/v1/organizations/{id}/personas}: <b>el tenant sale
 * del contexto validado del request</b>, no de la URL. {@code TenantContextFilter} ya lo
 * revalido contra la base en esta llamada, y dejar que el cliente lo nombre en la ruta seria
 * darle un lugar donde afirmar una pertenencia que el servidor tiene que verificar igual. Es el
 * mismo criterio que la disponibilidad de 02.04 y las ofertas de 02.06.
 *
 * <p>Y tampoco lleva la sede, a diferencia de las ofertas: una Persona pertenece a la
 * ORGANIZACION y no a un consultorio (DP-03 y la cabecera de V27). Que las mutaciones igual
 * exijan una sede activa en el contexto es un detalle de <b>autorizacion</b>, no de propiedad del
 * dato — el motivo, que no es obvio, esta en {@code PermissionCodes.PACIENTE_MANAGE}.
 *
 * <h2>Autorizacion</h2>
 *
 * <ul>
 *   <li><b>Leer</b>: pertenencia al tenant. Alcanza con tener contexto de organizacion activo.
 *       No hay codigo de permiso de lectura — la matriz no declara {@code paciente:read} y una
 *       etapa no amplia la matriz.</li>
 *   <li><b>Mutar</b>: {@code paciente:manage} sobre la sede del contexto. Pasan
 *       {@code ORG_ADMIN}, {@code CONSULTORIO_ADMIN} y {@code ADMINISTRATIVO}; el
 *       {@code PROFESIONAL} solo con grant explicito, que es lo que la matriz §4 llama "Segun
 *       permiso".</li>
 * </ul>
 *
 * <h2>Codigos, y por que no son intercambiables</h2>
 *
 * <ul>
 *   <li><b>404 para una persona de otro tenant</b>, nunca 403. Un 403 confirmaria que ese id
 *       existe y bastaria recorrer numeros para medir el padron de pacientes de otro centro.</li>
 *   <li><b>403 cuando falta contexto o falta el permiso</b>, nunca 401. El interceptor del
 *       frontend borra el token ante cualquier 401 y deja al usuario en un bucle de login.</li>
 *   <li><b>200 para una persona INACTIVA.</b> RN-M07-004: los historicos siguen resolviendo.</li>
 *   <li><b>409</b> para los tres invariantes: documento repetido ({@code persona-documento-taken},
 *       duro, no se puede confirmar), posible duplicado ({@code persona-posible-duplicado},
 *       advertencia que se confirma reenviando), persona inactiva ({@code persona-inactiva}) y
 *       version desactualizada. El de concurrencia llega con {@code type} <b>{@code conflict}</b>,
 *       no {@code concurrent-modification}.</li>
 * </ul>
 *
 * <h2>Lo que AKINE-03.02 agrego, y lo que sigue sin traer</h2>
 *
 * <p>03.02 sumo la <b>baja logica</b> de la persona y de su perfil (RF-M07-005) y la <b>ficha
 * 360</b> (RF-M07-004). Los adjuntos administrativos viven en {@link AdjuntoController}, bajo esta
 * misma ruta.
 *
 * <ul>
 *   <li><b>Coberturas</b>: son M08/M15, etapas 03.03 y 03.04. Cuando existan, aparecen en el 360
 *       solas: implementan {@code ResumenDePersonaContributor} y no tocan este modulo.</li>
 *   <li><b>Contenido clinico en el 360</b>: no falta, se decidio no ponerlo. AKINE-04.01 exige
 *       justificacion declarada para todo acceso clinico, y mostrarlo al abrir una ficha de
 *       mostrador convertiria ese control en un formalismo.</li>
 *   <li><b>El vinculo entre una persona y una cuenta del portal.</b> RN-M07-002 los separa, y
 *       resolver el autoservicio es una etapa propia.</li>
 *   <li><b>La fusion de fichas duplicadas.</b> El caso borde "paciente fusionado" de la etapa no
 *       se implementa: fusionar exige reapuntar turnos, sesiones, obligaciones y adjuntos en
 *       cuatro modulos, y eso es una etapa con su propio diseño. Lo que hay hoy es la deteccion de
 *       posibles duplicados en el alta (03.01) y la baja logica de la ficha sobrante, que resuelve
 *       el caso practico sin prometer una fusion que no existe.</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/personas", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Personas", description = "Padron de personas y activacion del perfil clinico (M07)")
public class PersonaController {

	private static final Logger log = LoggerFactory.getLogger(PersonaController.class);

	private static final String PAGINA_POR_DEFECTO = "0";
	private static final String TAMANO_POR_DEFECTO = "20";
	private static final int TAMANO_MAXIMO = 100;

	private final PersonaService personaService;
	private final PerfilPacienteService perfilPacienteService;
	private final ResumenDePersonaService resumenDePersonaService;
	private final PersonApiActor apiActor;

	public PersonaController(
			PersonaService personaService,
			PerfilPacienteService perfilPacienteService,
			ResumenDePersonaService resumenDePersonaService,
			PersonApiActor apiActor) {

		this.personaService = personaService;
		this.perfilPacienteService = perfilPacienteService;
		this.resumenDePersonaService = resumenDePersonaService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "buscarPersonas",
			summary = "Buscar personas en el padron",
			description = """
					Busca por documento, apellido, nombre o telefono, todo con el mismo parametro \
					q. La comparacion ignora mayusculas y acentos, y los separadores del \
					documento y del telefono: buscar 12345678, 12.345.678 o Perez encuentra a la \
					misma persona.

					Sin q devuelve el padron completo, paginado y ordenado por apellido.

					estado filtra por ciclo de vida y por defecto trae solo las ACTIVAS. perfil \
					filtra por perfil clinico y por defecto no filtra: la busqueda del mostrador \
					no sabe de antemano si quien busca es paciente.

					OJO: esPaciente es DERIVADO y de solo lectura. Una persona sin perfil clinico \
					es una persona valida y completa —RN-M07-006—, no una ficha a medio cargar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pagina de personas del padron",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PersonaPageResponse.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo, o sin paciente:read",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public PersonaPageResponse buscar(
			@Parameter(description = "Texto libre: documento, apellido, nombre, telefono o numero de afiliado")
			@RequestParam(required = false) String q,

			@Parameter(description = "Filtro por ciclo de vida. Por defecto solo las ACTIVAS")
			@RequestParam(required = false) PersonaEstadoFiltro estado,

			@Parameter(description = "Filtro por perfil clinico. Por defecto indistinto")
			@RequestParam(required = false) PerfilFiltro perfil,

			@Parameter(description = "Pagina, base cero")
			@RequestParam(defaultValue = PAGINA_POR_DEFECTO) int page,

			@Parameter(description = "Tamano de pagina, acotado a 100")
			@RequestParam(defaultValue = TAMANO_POR_DEFECTO) int size) {

		OperatingActor actor = apiActor.current();
		int pagina = Math.max(page, 0);
		int tamano = Math.min(Math.max(size, 1), TAMANO_MAXIMO);

		PersonaPagina resultado = personaService.buscar(
				actor, new PersonaBusqueda(q, estado, perfil), pagina, tamano);

		return PersonaPageResponse.of(resultado, pagina, tamano);
	}

	@GetMapping("/{personaId}")
	@Operation(
			operationId = "verPersona",
			summary = "Ver una persona del padron",
			description = """
					Devuelve la ficha administrativa. Una persona INACTIVA responde 200: sus \
					historicos tienen que seguir resolviendo (RN-M07-004).

					Una persona de otra organizacion responde 404 y no 403: distinguirlas \
					confirmaria que ese id existe.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La persona",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PersonaResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto de trabajo activo, o sin paciente:read",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, o es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public PersonaResponse ver(@PathVariable long personaId) {
		return PersonaResponse.from(personaService.ver(apiActor.current(), personaId));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "crearPersona",
			summary = "Dar de alta una persona",
			description = """
					Registra una PERSONA. NO crea perfil de paciente, ni historia clinica, ni \
					caso, ni sesion: eso es RF-M07-010 y no hay ningun parametro que lo cambie. \
					Convertirla en paciente es POST /personas/{personaId}/perfil-paciente.

					Solo el apellido y el nombre son obligatorios. El documento es opcional \
					porque una persona sin DNI es un caso real, y exigirlo obligaria al mostrador \
					a inventar uno.

					DOS RECHAZOS DISTINTOS Y NO SE CONFUNDEN:
                    - persona-documento-taken es DURO. Ya hay alguien vigente con ese documento \
					en la organizacion. No se puede confirmar ni saltear.
                    - persona-posible-duplicado es una ADVERTENCIA. Coincide el nombre completo o \
					el telefono con alguien ya registrado. Viene con la lista de candidatos; si \
					el operador confirma que es otra persona, se reenvia con \
					confirmaPosibleDuplicado en true. Es RN-M07-001 —busqueda previa a la \
					creacion— hecho cumplir por el backend y no solo por la pantalla.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Persona creada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PersonaResponse.class))),
			@ApiResponse(responseCode = "400", description = "Datos invalidos",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo, o sin paciente:manage",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "Documento repetido, o posibles duplicados sin confirmar",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PersonaResponse> crear(@Valid @RequestBody CreatePersonaRequest request) {
		PersonaView creada = personaService.crear(apiActor.current(), new PersonaAltaCommand(
				request.tipoDocumento(),
				request.numeroDocumento(),
				request.apellido(),
				request.nombre(),
				request.fechaNacimiento(),
				request.email(),
				request.telefono(),
				request.notas(),
				Boolean.TRUE.equals(request.confirmaPosibleDuplicado())));

		log.debug("Persona creada por API: personaId={}", creada.id());
		return ResponseEntity.created(URI.create("/api/v1/personas/" + creada.id()))
				.body(PersonaResponse.from(creada));
	}

	@PatchMapping(path = "/{personaId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "editarPersona",
			summary = "Editar los datos administrativos de una persona",
			description = """
					Edicion parcial: lo que no viene, no se toca. Un campo en null NO vacia el \
					valor guardado.

					Una persona INACTIVA responde 409: reabrir una ficha dada de baja para \
					cambiarle el nombre reescribiria el historico que RN-M07-004 protege.

					La deteccion de posibles duplicados NO corre en la edicion —pediria \
					confirmacion cada vez que se corrige un telefono—, pero el documento repetido \
					se sigue rechazando: eso lo verifica el unique, no una rama de codigo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Persona actualizada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PersonaResponse.class))),
			@ApiResponse(responseCode = "400", description = "Datos invalidos",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo, o sin paciente:manage",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, o es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "Persona inactiva, documento repetido o version desactualizada",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public PersonaResponse editar(
			@PathVariable long personaId, @Valid @RequestBody UpdatePersonaRequest request) {

		PersonaView actualizada = personaService.editar(
				apiActor.current(),
				personaId,
				new PersonaEdicionCommand(
						request.tipoDocumento(),
						request.numeroDocumento(),
						request.apellido(),
						request.nombre(),
						request.fechaNacimiento(),
						request.email(),
						request.telefono(),
						request.notas(),
						request.expectedVersion()));

		return PersonaResponse.from(actualizada);
	}

	@PostMapping(path = "/{personaId}/perfil-paciente", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "activarPerfilPaciente",
			summary = "Activar el perfil clinico de una persona",
			description = """
					Convierte funcionalmente a la persona en paciente, reutilizando su identidad \
					(RF-M07-008, RN-M07-007). No recibe ningun dato personal: si lo recibiera, \
					existiria la forma de que la ficha clinica y la administrativa se separaran.

					ES IDEMPOTENTE Y RESPONDE 200, no 201 y no 409. Activar dos veces no es un \
					error del operador: es el boton tocado dos veces o el request reintentado \
					despues de un timeout, y la respuesta correcta es el perfil que ya existe.

					ACTIVAR UN PERFIL NO CREA HISTORIA CLINICA. La HC es M09 y la crea el modulo \
					clinico cuando exista; esto declara que la persona es paciente y nada mas \
					(regla maestra 1).

					Una persona INACTIVA responde 409. Una persona vigente cuyo perfil anterior \
					fue dado de baja SI se puede reactivar: es un alta nueva y el unique lo \
					permite.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200",
					description = "Perfil activado, o el que ya estaba vigente",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PersonaResponse.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo, o sin paciente:manage",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, o es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La persona esta dada de baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public PersonaResponse activarPerfil(
			@PathVariable long personaId,
			@Valid @RequestBody ActivarPerfilPacienteRequest request) {

		return PersonaResponse.from(
				perfilPacienteService.activar(apiActor.current(), personaId, request.motivo()));
	}

	@GetMapping("/{personaId}/resumen")
	@Operation(
			operationId = "verResumenDePersona",
			summary = "Ver la ficha 360 de una persona",
			description = """
					Consolida la identidad, los adjuntos y lo que aporta cada modulo: turnos y \
					situacion economica hoy; coberturas cuando existan (03.03/03.04).

					RECORTA POR PERMISOS Y NO RECHAZA. Una seccion cuyo permiso el actor no tiene \
					no viene, y aparece en seccionesOmitidas con el codigo que falta. Devolver 403 \
					sobre la ficha entera por no poder ver la deuda dejaria al profesional sin \
					poder abrir a ningun paciente; omitir en silencio seria peor, porque la \
					pantalla leeria "sin turnos" donde en realidad dice "no podes ver los turnos".

					NO TRAE NADA CLINICO, y no es que falte: AKINE-04.01 fijo que todo acceso \
					clinico exige justificacion declarada y queda auditado. Una ficha de mostrador \
					que muestre casos al abrirla convertiria ese control en un formalismo. El dato \
					clinico se pide por M09, con permiso y justificacion.

					Una persona INACTIVA responde 200: el 360 de una ficha dada de baja es \
					justamente donde se consulta su historico.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La ficha consolidada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ResumenDePersonaResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto de trabajo activo, o sin paciente:read",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, o es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResumenDePersonaResponse verResumen(@PathVariable long personaId) {
		return ResumenDePersonaResponse.from(
				resumenDePersonaService.ver(apiActor.current(), personaId));
	}

	@DeleteMapping(path = "/{personaId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "darDeBajaPersona",
			summary = "Dar de baja logica a una persona del padron",
			description = """
					Baja LOGICA con motivo obligatorio (RF-M07-005). NO BORRA NADA: la ficha se \
					sigue leyendo con 200, sus turnos siguen existiendo, sus obligaciones siguen \
					debiendose y sus adjuntos se siguen descargando. Lo que deja de admitir son \
					operaciones nuevas: editarla, activarle un perfil, adjuntarle documentos.

					DA DE BAJA TAMBIEN EL PERFIL DE PACIENTE vigente, en la misma transaccion. \
					Dejarlo vivo produciria una ficha que los modulos clinicos siguen viendo como \
					paciente vigente mientras el padron la considera cerrada.

					LIBERA EL DOCUMENTO: el mismo documento se puede volver a usar en un alta \
					nueva. Es intencional y es lo que permite corregir una ficha creada mal sin \
					borrarla.

					Devuelve la ficha como quedo, con su version nueva, en vez de un 204: la \
					pantalla la necesita para refrescar sin pedir otro GET.

					No valida si la persona tiene turnos futuros o deuda: dar de baja a alguien \
					que se fue debiendo es un caso legitimo, y bloquearlo obligaria a condonar \
					para poder cerrar la ficha.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Persona dada de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PersonaResponse.class))),
			@ApiResponse(responseCode = "400", description = "Falta el motivo de la baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo, o sin paciente:manage",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, o es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "Ya estaba dada de baja, o la version esta desactualizada",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public PersonaResponse darDeBaja(
			@PathVariable long personaId, @Valid @RequestBody BajaDePersonaRequest request) {

		log.info("Baja de persona solicitada: personaId={}", personaId);
		return PersonaResponse.from(personaService.darDeBaja(
				apiActor.current(), personaId, request.motivo(), request.expectedVersion()));
	}

	@DeleteMapping(path = "/{personaId}/perfil-paciente",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "darDeBajaPerfilPaciente",
			summary = "Dar de baja el perfil clinico de una persona",
			description = """
					La persona SIGUE VIGENTE en el padron y lo unico que deja de ser es paciente. \
					Es exactamente el estado que RN-M07-006 describe: alguien que consume \
					servicios no clinicos sin perfil clinico.

					ES IDEMPOTENTE Y RESPONDE 200. Dar de baja un perfil que ya no esta vigente no \
					es un error del operador: es el boton tocado dos veces.

					NO BORRA LA HISTORIA CLINICA. Igual que activar no la crea, esto no la borra: \
					la HC es de M09 y su existencia no depende de que el perfil siga vigente. \
					Reactivar el perfil despues vuelve a encontrar la historia que ya habia.

					El motivo es obligatorio, a diferencia de la activacion: la operacion que \
					restringe es la que alguien va a tener que justificar despues.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Perfil dado de baja, o ya no vigente",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PersonaResponse.class))),
			@ApiResponse(responseCode = "400", description = "Falta el motivo de la baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo, o sin paciente:manage",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, o es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public PersonaResponse darDeBajaPerfil(
			@PathVariable long personaId,
			@Valid @RequestBody BajaDePerfilPacienteRequest request) {

		return PersonaResponse.from(perfilPacienteService.desactivar(
				apiActor.current(), personaId, request.motivo()));
	}
}
