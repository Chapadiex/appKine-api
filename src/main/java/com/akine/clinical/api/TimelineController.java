package com.akine.clinical.api;

import com.akine.clinical.api.dto.TimelineResponse;
import com.akine.clinical.application.TimelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El timeline longitudinal de una Historia Clinica (RF-M09-004).
 *
 * <h2>Un indice, no un visor</h2>
 *
 * <p>La respuesta trae hechos datados con su origen y su referencia, y <b>ningun contenido
 * clinico</b>: ni evoluciones, ni diagnosticos, ni mediciones. Quien quiera el detalle va al
 * recurso dueño con su propio permiso, y ese acceso se audita alli. Si el timeline trajera
 * contenido, una sola lectura entregaria la historia entera y los eventos de acceso de cada
 * modulo dejarian de significar algo.
 *
 * <h2>Por que es una sola operacion y no una por fuente</h2>
 *
 * <p>La pagina se calcula al leer, agregando lo que aporta cada contribuyente. <b>No hay tabla de
 * timeline</b>: seria una segunda copia de la verdad, y el dia que alguien enmiende una entrada o
 * anule una sesion sin avisarle al proyector, esa copia miente y nadie se entera. El costo esta
 * asumido —una consulta por fuente, hoy cuatro— y acotado por el tope por contribuyente.
 */
@RestController
@RequestMapping(path = "/api/v1/historias-clinicas/{historiaClinicaId}/timeline")
@Tag(name = "Timeline clinico",
		description = "Linea de tiempo longitudinal de una Historia Clinica (M09)")
public class TimelineController {

	private final TimelineService timelineService;
	private final ClinicalApiActor apiActor;

	public TimelineController(TimelineService timelineService, ClinicalApiActor apiActor) {
		this.timelineService = timelineService;
		this.apiActor = apiActor;
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "verTimelineClinico",
			summary = "Ver el timeline de una Historia Clinica",
			description = """
					RF-M09-004. Devuelve un INDICE de hechos clinicos, mas nuevos primero, \
					agregando las cuatro fuentes que contribuyen hoy: entradas clinicas, \
					adjuntos clinicos, antecedentes y sesiones CERRADAS.

					EL TURNO NO APARECE, y es una decision y no un olvido. RN-M09-005: las \
					asistencias no clinicas no forman parte de la Historia Clinica, y DP-05 es \
					explicito en que ninguna transicion administrativa prueba que una prestacion \
					ocurrio. Un turno reservado, cancelado o ausente es un hecho de agenda. La \
					sesion CERRADA si es un hecho clinico; una en curso es un borrador, e \
					indexarla pondria en el timeline una fila que cambia mientras alguien la mira.

					NINGUN EVENTO TRAE CONTENIDO CLINICO. Solo el instante, el origen, la \
					etiqueta del tipo de hecho y la referencia para ir a buscarlo al modulo dueño.

					PAGINA POR CURSOR, NO POR OFFSET. Un OFFSET sobre un agregado de fuentes \
					heterogeneas se desordena en cuanto una fuente inserta: basta que se registre \
					una entrada mientras alguien pagina para que la pagina 2 repita o saltee \
					filas. El cursor es OPACO: la unica forma valida de obtener uno es haber \
					leido la pagina anterior, y uno que no decodifica es 400 cursor-invalido, \
					nunca "primera pagina".

					CADA LECTURA SE AUDITA (DP-03). El timeline le muestra a quien lo abre \
					cuantos hechos clinicos tiene un paciente y de que clase, que ya es \
					informacion sensible aunque no traiga contenido. Un administrativo con \
					hc:read y motivo declarado PASA, a proposito: negar por rol romperia la \
					recepcion real de un centro, y el control es la auditoria.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pagina del timeline",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = TimelineResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "El cursor no se pudo decodificar (cursor-invalido)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo, sin hc:read, o sin relacion "
							+ "asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La historia no existe, esta dada de baja, o es de otra "
							+ "organizacion. Los tres casos son indistinguibles a proposito",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public TimelineResponse ver(

			@Parameter(description = "Historia clinica cuyo timeline se pide", example = "88")
			@PathVariable long historiaClinicaId,

			@Parameter(description = "Cursor opaco devuelto por la pagina anterior. Sin el, se "
					+ "devuelve la primera pagina")
			@RequestParam(required = false) String cursor,

			@Parameter(description = "Tamano de pagina pedido. El servidor lo acota: un valor "
					+ "mayor al tope se recorta en silencio en vez de rechazarse, para que el "
					+ "cliente que pide de mas igual reciba una pagina valida")
			@RequestParam(required = false) Integer limite,

			@Parameter(description = "Filtro OPCIONAL por Caso Clinico (04.03). Sin el, el "
					+ "timeline es el de la historia entera. Con el, quedan solo los hechos que "
					+ "cada fuente puede atribuirle a ese caso — hoy las sesiones cerradas, "
					+ "porque la entrada clinica, el adjunto y el antecedente cuelgan de la "
					+ "historia y no del caso. Un caso que no es de esa historia responde 404 y "
					+ "no una pagina vacia: son dos situaciones distintas", example = "17")
			@RequestParam(required = false) Long casoId,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return TimelineResponse.from(timelineService.ver(
				apiActor.current(), historiaClinicaId, cursor, limite, casoId, justificacion));
	}
}
