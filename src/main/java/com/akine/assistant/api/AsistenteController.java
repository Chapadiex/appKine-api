package com.akine.assistant.api;

import com.akine.assistant.api.dto.ConsultaAsistenteRequest;
import com.akine.assistant.api.dto.RespuestaAsistenteResponse;
import com.akine.assistant.application.AsistenteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El asistente de datos: preguntas en lenguaje natural sobre turnos, sesiones, clases y reportes de
 * una sede (docs/diseno/AKINE-asistente-agente.md). Solo lectura.
 *
 * <p>Nada de la pregunta ni de la respuesta va al log: la auditoria registra las herramientas
 * llamadas, el resultado y el consumo de tokens.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/asistente/consultas",
		produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@Tag(name = "Asistente", description = "Preguntas en lenguaje natural sobre los datos de la sede")
public class AsistenteController {

	private final AsistenteService asistente;
	private final AsistenteApiActor apiActor;

	public AsistenteController(AsistenteService asistente, AsistenteApiActor apiActor) {
		this.asistente = asistente;
		this.apiActor = apiActor;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "consultarAsistente", summary = "Preguntarle al asistente de datos",
			description = """
					Responde una pregunta sobre los datos de la sede: turnos y asistencia, sesiones, \
					pacientes, coberturas, practicas y precios, deudas y cobros, clases, reportes y como se \
					usa AKINE. Requiere `asistente:use` y el interruptor `akine.asistente.enabled`.

					Un modelo de lenguaje responde como **agente**: pide herramientas de solo lectura, cada \
					una ejecutada con los permisos del usuario, y redacta el texto con sus resultados. \
					Los resultados de las herramientas viajan al proveedor del modelo. `herramientas` lista \
					las que llamo, con su entrada. Lo que el usuario no puede ver vuelve como `sin_permiso` \
					al modelo; si todo fue rechazado, `resultado` es SIN_PERMISO, con 200.

					Si el modelo necesita saber de que persona se habla, `resultado` es ACLARACION y \
					`opciones` trae hasta cinco: el cliente reenvia la misma pregunta con el `personaId` \
					elegido. El servidor no guarda la conversacion: `historial` lleva cada pregunta \
					anterior con el texto que se respondio y los ids de las personas nombradas; \
					`interpretacion` esta deprecada y se ignora.

					Con `akine.asistente.acciones.enabled` y `asistente:acciones`, una frase que cuenta o \
					pide algo ("vinieron todos menos Maria", "Juan pago 15.000 en efectivo") no se responde: \
					`resultado` es PROPUESTA y `accion` trae una tarjeta para revisar. **Nada se graba** \
					hasta `confirmarAccionDelAsistente`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La respuesta"),
			@ApiResponse(responseCode = "400", description = "`validation-error`: pregunta vacia o mas larga que 600 "
					+ "caracteres, o historial mal formado",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin `asistente:use` o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "`not-found`: la sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "`asistente-deshabilitado`: el interruptor esta apagado",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "503", description = "`asistente-no-disponible`: no responde ni el "
					+ "proveedor principal ni el alternativo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public RespuestaAsistenteResponse consultarAsistente(
			@PathVariable long consultorioId,
			@RequestBody @Valid ConsultaAsistenteRequest request) {

		return RespuestaAsistenteResponse.de(asistente.consultar(apiActor.current(), consultorioId,
				request.pregunta(), request.aHistorial(), request.personaId(), request.rutaActual()));
	}
}
