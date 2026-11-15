package com.akine.encounter.api;

import com.akine.encounter.api.dto.EvolucionDelCasoResponse;
import com.akine.encounter.application.EvolucionDelCasoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Evolucion de un Caso Clinico (M31, RF-M31-001 y RF-M31-002). */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/casos/{casoId}/evolucion",
		produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@Tag(name = "Evolucion del caso",
		description = "Indicadores y alertas calculados sobre las sesiones cerradas de un caso clinico")
class EvolucionDelCasoController {

	private static final String JUSTIFICACION = "X-Justificacion-Acceso";

	private final EvolucionDelCasoService evolucion;
	private final EncounterApiActor apiActor;

	EvolucionDelCasoController(EvolucionDelCasoService evolucion, EncounterApiActor apiActor) {
		this.evolucion = evolucion;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "verEvolucionDelCaso",
			summary = "Indicadores y alertas de evolucion de un caso",
			description = """
					Calcula, sobre las sesiones CERRADAS del caso, la serie de dolor, la evolucion \
					declarada, la tolerancia, las series de mediciones por definicion y lado, la \
					adherencia, la frecuencia real contra el plan y el avance del plan, y evalua \
					las alertas de RF-M31-002, cada una con las sesiones o turnos que la originan.

					SE CALCULA AL LEER Y NO SE GUARDA NADA (RN-M31-002), ni modifica la sesion, \
					el caso ni el plan (RN-M31-001). Es una sugerencia para revisar, no una \
					indicacion.

					EXIGE hc:read Y RELACION ASISTENCIAL O JUSTIFICACION, como la historia \
					clinica: muestra sesiones de todos los profesionales del caso. Toda lectura se \
					audita (EVOLUCION_DEL_CASO_ANALIZADA), sin texto clinico.

					Con menos de tres sesiones presentes no evalua alertas: trae los indicadores y \
					DATOS_INSUFICIENTES. Las sesiones sin caso no entran y se informan en \
					advertencias. Analiza como maximo las 200 sesiones mas recientes.

					Con la funcion apagada en el despliegue responde 404.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Evolucion del caso",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EvolucionDelCasoResponse.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo, sin hc:read, o sin relacion asistencial "
							+ "ni justificacion (justificacion-de-acceso-requerida)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La sede o el caso no existen o son de otro tenant, o la funcion "
							+ "esta apagada",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EvolucionDelCasoResponse> ver(

			@Parameter(description = "Sede del contexto", example = "7")
			@PathVariable long consultorioId,

			@Parameter(description = "Caso clinico", example = "41")
			@PathVariable long casoId,

			@Parameter(description = "Motivo declarado del acceso (DP-03). Obligatorio sin "
					+ "relacion asistencial con el paciente. Viaja en cabecera para no quedar en "
					+ "los logs de un proxy, y queda auditado")
			@RequestHeader(name = JUSTIFICACION, required = false) String justificacion) {

		return ResponseEntity.ok(EvolucionDelCasoResponse.de(
				evolucion.analizar(apiActor.current(), consultorioId, casoId, justificacion)));
	}
}
