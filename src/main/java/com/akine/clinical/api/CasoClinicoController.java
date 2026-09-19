package com.akine.clinical.api;

import com.akine.clinical.api.dto.AbrirCasoClinicoRequest;
import com.akine.clinical.api.dto.CasoClinicoResponse;
import com.akine.clinical.api.dto.CasoEventoResponse;
import com.akine.clinical.api.dto.CerrarCasoClinicoRequest;
import com.akine.clinical.api.dto.EditarCasoClinicoRequest;
import com.akine.clinical.api.dto.EquipoDelCasoRequest;
import com.akine.clinical.api.dto.IntegranteDelEquipoRequest;
import com.akine.clinical.api.dto.ReabrirCasoClinicoRequest;
import com.akine.clinical.application.CasoClinicoAltaCommand;
import com.akine.clinical.application.CasoClinicoService;
import com.akine.clinical.application.IntegranteDelEquipo;
import com.akine.clinical.domain.RolEnCaso;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Casos Clinicos de una Historia Clinica (M10, RF-M10-001..006).
 *
 * <h2>Dos bases de ruta, y por que</h2>
 *
 * <p>El alta y el listado cuelgan de la historia
 * —{@code /historias-clinicas/&#123;id&#125;/casos}— porque la historia es la ficha cuya
 * autorizacion clinica se evalua: con ella en la ruta no hay forma de abrir ni listar sin
 * nombrarla. Las seis operaciones sobre un caso existente son planas
 * —{@code /casos-clinicos/&#123;id&#125;}— porque el caso <b>sabe</b> de que historia es, y
 * obligar al cliente a repetirla abriria la puerta a que los dos ids no coincidan: un pedido que
 * nombra la historia A y el caso B habria que rechazarlo, y ese es un caso que directamente no
 * existe si el id no viaja. Mismo reparto que las entradas clinicas de 04.02.
 *
 * <h2>Cerrar no es borrar, y por eso no hay DELETE</h2>
 *
 * <p>Un caso no se elimina: si se abrio por error, se cierra con motivo. No hay baja logica ni
 * fisica, y la ausencia de un {@code DELETE} en este controller es la decision, no un olvido — dos
 * formas de que un caso "no este" es como se construye una consulta que se olvida de una.
 *
 * <h2>Toda operacion es acceso clinico</h2>
 *
 * <p>Las tres lecturas tambien. Cada una exige {@code hc:read} o {@code hc:write} mas relacion
 * asistencial o motivo declarado (DP-03), y cada una deja su evento de auditoria — incluidas las
 * lecturas, que es lo que distingue este modulo del resto del backend. La ficha de un caso lleva
 * diagnostico presuntivo, que es contenido clinico.
 */
@RestController
@Tag(name = "Casos clinicos",
		description = "Problemas clinicos concretos dentro de una Historia Clinica (M10)")
public class CasoClinicoController {

	private static final Logger log = LoggerFactory.getLogger(CasoClinicoController.class);

	private static final String POR_HISTORIA = "/api/v1/historias-clinicas/{historiaClinicaId}";
	private static final String POR_CASO = "/api/v1/casos-clinicos/{casoClinicoId}";

	private final CasoClinicoService casoService;
	private final ClinicalApiActor apiActor;

	public CasoClinicoController(CasoClinicoService casoService, ClinicalApiActor apiActor) {
		this.casoService = casoService;
		this.apiActor = apiActor;
	}

	// =================================================================================
	// Colgadas de la historia
	// =================================================================================

	@PostMapping(path = POR_HISTORIA + "/casos",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "abrirCasoClinico",
			summary = "Abrir un Caso Clinico",
			description = """
					RF-M10-001. Crea el caso con su correlativo dentro de la historia y su equipo \
					tratante inicial, todo en la misma transaccion.

					VARIOS CASOS ACTIVOS A LA VEZ SON LEGITIMOS (RN-M10-002): una rodilla y un \
					hombro son dos casos del mismo paciente el mismo dia, y no hay ninguna \
					restriccion que lo impida. Lo que si hace el backend es DETENER el alta \
					cuando ya hay un caso ACTIVO de la misma oferta, con 409 \
					caso-clinico-posible-duplicado y la lista de candidatos. El profesional los \
					mira y decide: abre el que existe, o reenvia el alta con \
					confirmaPosibleDuplicado en true. Es el mismo mecanismo del alta de Persona \
					(RN-M07-001), que los usuarios ya conocen.

					ESE 409 NO ES EL 409 DE CONCURRENCIA, y por eso son problemType distintos: \
					este se resuelve confirmando, concurrent-modification se resuelve releyendo.

					EL CORRELATIVO NO ES EL ID. numeroCaso cuenta dentro de la historia —"el caso \
					2 de este paciente"— y se asigna de forma atomica: dos altas simultaneas \
					desde dos sedes no se llevan el mismo numero.

					LA OFERTA TIENE QUE ESTAR VIGENTE en la sede del contexto (RN-M10-006): si no, \
					409 oferta-no-vigente. Es 409 y no 404 porque la oferta existe y quien la \
					eligio la esta viendo en una lista.

					Exige hc:write mas relacion asistencial o motivo declarado, y queda auditada \
					como CASO_CLINICO_OPENED.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Caso abierto, con su equipo inicial",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CasoClinicoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Cuerpo invalido",
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
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "Posible duplicado sin confirmar (con `candidatos`), o la "
							+ "oferta no esta vigente",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CasoClinicoResponse> abrir(

			@Parameter(description = "Historia clinica en la que se abre el caso", example = "88")
			@PathVariable long historiaClinicaId,

			@Valid @RequestBody AbrirCasoClinicoRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		CasoClinicoResponse cuerpo = CasoClinicoResponse.from(casoService.abrir(
				apiActor.current(),
				new CasoClinicoAltaCommand(
						historiaClinicaId,
						request.ofertaId(),
						request.diagnosticoPresuntivo(),
						request.objetivoTerapeutico(),
						equipoDe(request.equipo()),
						Boolean.TRUE.equals(request.confirmaPosibleDuplicado())),
				justificacion));

		log.debug("Caso clinico creado por API: casoClinicoId={}", cuerpo.id());
		return ResponseEntity
				.created(URI.create("/api/v1/casos-clinicos/" + cuerpo.id()))
				.body(cuerpo);
	}

	@GetMapping(path = POR_HISTORIA + "/casos", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "listarCasosClinicos",
			summary = "Listar los casos de una Historia Clinica",
			description = """
					Devuelve cada caso con su equipo VIGENTE, mas recientes primero.

					Por defecto trae TODOS, activos y cerrados. Un caso cerrado no es un caso \
					borrado: se sigue leyendo entero con todo su historial, que es lo que hace \
					consultable el recorrido del paciente (regla maestra 10). Con soloActivos se \
					pide la lista de trabajo del dia.

					Es lectura clinica: exige hc:read mas relacion asistencial o motivo \
					declarado, y queda auditada.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Casos de la historia",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(schema = @Schema(
									implementation = CasoClinicoResponse.class)))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto, sin hc:read, o sin relacion asistencial ni "
							+ "motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La historia no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public List<CasoClinicoResponse> listar(

			@Parameter(description = "Historia clinica cuyos casos se piden", example = "88")
			@PathVariable long historiaClinicaId,

			@Parameter(description = "Dejar afuera los cerrados")
			@RequestParam(defaultValue = "false") boolean soloActivos,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return casoService
				.listar(apiActor.current(), historiaClinicaId, soloActivos, justificacion)
				.stream()
				.map(CasoClinicoResponse::from)
				.toList();
	}

	// =================================================================================
	// Sobre un caso existente
	// =================================================================================

	@GetMapping(path = POR_CASO, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "verCasoClinico",
			summary = "Ver un Caso Clinico con su equipo vigente",
			description = """
					UN CASO CERRADO SE SIGUE VIENDO ENTERO, con 200. Cerrar no es borrar: lo que \
					un caso cerrado no admite son sesiones nuevas y edicion de contenido clinico.

					El equipo que viaja es el VIGENTE. El historico completo —quien estuvo y \
					cuando— se lee con el historial del caso, que es otra operacion: mostrarlos \
					juntos pondria a alguien que dejo el equipo hace un año al lado del que \
					atiende hoy.

					Es lectura clinica y deja su evento de auditoria.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El caso con su equipo vigente",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CasoClinicoResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:read, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "El caso no existe, es de otro tenant, o su historia no es "
							+ "accesible. Los tres son indistinguibles: distinguirlos permitiria "
							+ "censar por ids los casos de otro centro",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public CasoClinicoResponse ver(

			@Parameter(description = "Caso clinico pedido", example = "17")
			@PathVariable long casoClinicoId,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return CasoClinicoResponse.from(
				casoService.ver(apiActor.current(), casoClinicoId, justificacion));
	}

	@PatchMapping(path = POR_CASO,
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "editarCasoClinico",
			summary = "Editar el diagnostico y el objetivo de un caso",
			description = """
					RF-M10-004. Reemplaza los dos campos completos; no es un parche por campo \
					ausente, porque un PATCH que distingue "no lo mandes" de "vacialo" obliga al \
					cliente a codificar esa diferencia y la primera pantalla que se olvide borra \
					el objetivo sin querer.

					LA OFERTA NO SE EDITA. Cambiarla convertiria el caso en otro caso, con el \
					mismo numero y el mismo historial: si lo que se trata es otra cosa, \
					corresponde cerrar este y abrir uno nuevo.

					UN CASO CERRADO RESPONDE 409, no 403 y no 404. El caso existe y quien opera \
					tiene hc:write: lo que no admite cambios es el estado. Lo que corresponde es \
					reabrirlo con motivo, que queda en el historial (RF-M10-006). Editar en \
					silencio un caso terminado es historia clinica reescrita, y ADR-0011 lo \
					prohibe.

					expectedVersion NO ES UN CHEQUEO DE RUTINA: dos profesionales del equipo \
					editando el objetivo del mismo caso son el caso normal.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Caso editado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CasoClinicoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Cuerpo invalido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El caso no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El caso esta cerrado, o la version quedo vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public CasoClinicoResponse editar(

			@Parameter(description = "Caso clinico que se edita", example = "17")
			@PathVariable long casoClinicoId,

			@Valid @RequestBody EditarCasoClinicoRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return CasoClinicoResponse.from(casoService.editar(
				apiActor.current(),
				casoClinicoId,
				request.diagnosticoPresuntivo(),
				request.objetivoTerapeutico(),
				request.expectedVersion(),
				justificacion));
	}

	@PostMapping(path = POR_CASO + "/cierre",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "cerrarCasoClinico",
			summary = "Cerrar un Caso Clinico",
			description = """
					RF-M10-006. EL MOTIVO ES OBLIGATORIO: sin el, un cierre es indistinguible de \
					un abandono y el historial deja de servir para lo unico que sirve. Se rechaza \
					con 400 caso-sin-motivo-de-cierre, no con 409: no hay conflicto de estado, \
					falta un dato del pedido, y reintentar sin motivo falla exactamente igual.

					CERRAR NO ES BORRAR, y por eso esta operacion no es un DELETE. El caso se \
					sigue leyendo entero con todo su historial; lo que no admite son sesiones \
					nuevas y edicion de contenido clinico. No existe la baja de un caso: si se \
					abrio por error, se cierra con motivo.

					CERRAR DOS VECES NO ES UN CONFLICTO: es el mismo pedido, se responde 200 con \
					el caso tal como quedo y el motivo ORIGINAL intacto. Pisarlo con el nuevo \
					perderia el que explica el cierre.

					Queda asentado como CIERRE en el historial del caso, y auditado como \
					CASO_CLINICO_CLOSED.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Caso cerrado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CasoClinicoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Falta el motivo del cierre",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El caso no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La version enviada quedo vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public CasoClinicoResponse cerrar(

			@Parameter(description = "Caso clinico que se cierra", example = "17")
			@PathVariable long casoClinicoId,

			@Valid @RequestBody CerrarCasoClinicoRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		log.info("Cierre de caso clinico solicitado: casoClinicoId={}", casoClinicoId);
		return CasoClinicoResponse.from(casoService.cerrar(
				apiActor.current(),
				casoClinicoId,
				request.motivo(),
				request.expectedVersion(),
				justificacion));
	}

	@PostMapping(path = POR_CASO + "/reapertura",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "reabrirCasoClinico",
			summary = "Reabrir un Caso Clinico cerrado",
			description = """
					RF-M10-006. El estado vuelve a ACTIVO y NO aparece ningun estado nuevo: el \
					requerimiento pide reabrir, no un tercer valor. Lo que hace revisable la \
					reapertura despues es el historial del caso, no una columna.

					EL MOTIVO ES OBLIGATORIO, por lo mismo que en el cierre.

					LAS TRES COLUMNAS DEL CIERRE SE LIMPIAN. No se pierde nada: el cierre \
					anterior, con su motivo y su actor, queda en el historial del caso, que es \
					append-only. Dejarlas puestas haria que una consulta por "cerrados" devuelva \
					casos abiertos.

					NO REINICIA LA NUMERACION DE SESIONES DEL CASO. La sesion siguiente a una \
					reapertura es la 9, no la 1: renumerar seria reescribir historia clinica.

					Reabrir un caso ya activo devuelve 200 con el caso tal cual: es el mismo \
					pedido.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Caso reabierto",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CasoClinicoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Falta el motivo de la reapertura",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El caso no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La version enviada quedo vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public CasoClinicoResponse reabrir(

			@Parameter(description = "Caso clinico que se reabre", example = "17")
			@PathVariable long casoClinicoId,

			@Valid @RequestBody ReabrirCasoClinicoRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		log.info("Reapertura de caso clinico solicitada: casoClinicoId={}", casoClinicoId);
		return CasoClinicoResponse.from(casoService.reabrir(
				apiActor.current(),
				casoClinicoId,
				request.motivo(),
				request.expectedVersion(),
				justificacion));
	}

	@PutMapping(path = POR_CASO + "/equipo",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "cambiarEquipoDeCasoClinico",
			summary = "Reemplazar el equipo tratante de un caso",
			description = """
					RF-M10-005. Es un PUT y un REEMPLAZO COMPLETO, no un alta ni una baja por \
					integrante: quien mira la pantalla ve una lista y guarda la lista, y \
					expresarlo con dos operaciones obligaria al cliente a calcular el diff.

					LO QUE SALE DE LA LISTA NO SE BORRA: se le marca la fecha de salida. Un \
					profesional desvinculado sigue figurando en el caso que trato, PORQUE LO \
					TRATO (regla maestra 10). Lo que si cambia es que deja de poder escribir, y \
					eso lo decide su membership vigente. A quien vuelve se le abre una \
					participacion nueva: la anterior termino y sigue siendo cierta.

					EL ROL NO OTORGA NI QUITA PERMISOS. RESPONSABLE y TRATANTE expresan quien \
					responde por el caso; quien puede escribir en la historia lo deciden hc:write \
					y la membership.

					expectedVersion ES IMPRESCINDIBLE Y POR UN MOTIVO PARTICULAR: esta operacion \
					no toca ninguna columna del caso, escribe en otra tabla, asi que el backend \
					fuerza el avance de la version al guardar. Sin eso, dos cambios concurrentes \
					commitean los dos —cada uno partiendo del equipo que leyo— y el resultado no \
					es ninguno de los dos.

					Un caso cerrado responde 409: el equipo de un caso terminado es historia.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Equipo reemplazado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CasoClinicoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Cuerpo invalido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El caso no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El caso esta cerrado, o la version quedo vieja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public CasoClinicoResponse cambiarEquipo(

			@Parameter(description = "Caso clinico cuyo equipo se reemplaza", example = "17")
			@PathVariable long casoClinicoId,

			@Valid @RequestBody EquipoDelCasoRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return CasoClinicoResponse.from(casoService.cambiarEquipo(
				apiActor.current(),
				casoClinicoId,
				equipoDe(request.integrantes()),
				request.expectedVersion(),
				justificacion));
	}

	@GetMapping(path = POR_CASO + "/eventos", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "verEventosDeCasoClinico",
			summary = "Ver el historial de estados de un caso",
			description = """
					RF-M10-006. Del evento mas viejo al mas nuevo, al reves que casi todo el \
					resto del modulo: esto no es una bandeja sino una LINEA DE TIEMPO, y una \
					linea de tiempo se lee en el orden en que ocurrio.

					ES APPEND-ONLY. Ningun evento se edita ni se borra, y por eso no hay ninguna \
					operacion que lo permita: un historial que se puede editar no es un historial.

					NINGUN EVENTO TRAE CONTENIDO CLINICO. detalle dice que se edito el objetivo, \
					no cual era. El diagnostico y el objetivo se leen del caso, con su propio \
					acceso auditado.

					ESTO NO ES LA AUDITORIA Y NO LA REEMPLAZA: audit_event registra ACCESOS y lo \
					lee quien audita con auditoria:read; esto registra ESTADOS y lo lee el \
					profesional con hc:read. Dos lectores, dos permisos, dos preguntas.

					Es su propia operacion y su propio evento de auditoria.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Historial del caso",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(schema = @Schema(
									implementation = CasoEventoResponse.class)))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:read, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El caso no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public List<CasoEventoResponse> eventos(

			@Parameter(description = "Caso clinico cuyo historial se pide", example = "17")
			@PathVariable long casoClinicoId,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return casoService.eventos(apiActor.current(), casoClinicoId, justificacion).stream()
				.map(CasoEventoResponse::from)
				.toList();
	}

	// =================================================================================
	// Internos
	// =================================================================================

	/**
	 * Traduce el equipo del DTO al del comando.
	 *
	 * <p>El rol viaja como texto en el contrato y como enum hacia adentro: un rol desconocido es un
	 * 400 de binding y no una fila con un valor que el CHECK de la base despues rechaza. Ausente
	 * equivale a {@code TRATANTE}, que es lo que hace que una pantalla que todavia no eligio rol
	 * pueda guardar el equipo.
	 */
	private static List<IntegranteDelEquipo> equipoDe(List<IntegranteDelEquipoRequest> declarado) {
		if (declarado == null) {
			return List.of();
		}
		return declarado.stream()
				.map(integrante -> new IntegranteDelEquipo(
						integrante.profesionalMembershipId(),
						integrante.rol() == null
								? RolEnCaso.TRATANTE
								: RolEnCaso.valueOf(integrante.rol())))
				.toList();
	}
}
