package com.akine.assistant.api;

import com.akine.assistant.api.dto.ConfirmarAccionRequest;
import com.akine.assistant.api.dto.ResultadoDeAccionResponse;
import com.akine.assistant.application.AccionesDelAsistente;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Confirmar o descartar lo que el asistente propuso (docs/diseno/AKINE-asistente-acciones.md). La
 * propuesta nace en {@code consultarAsistente}, cuando la frase cuenta o pide algo.
 *
 * <p>Nada de la tarjeta va al log: puede traer nombres y la nota de una atencion.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/asistente/acciones/{accionId}",
		produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@Tag(name = "Asistente", description = "Preguntas en lenguaje natural sobre los datos de la sede")
public class AccionesController {

	private final AccionesDelAsistente acciones;
	private final AsistenteApiActor apiActor;

	public AccionesController(AccionesDelAsistente acciones, AsistenteApiActor apiActor) {
		this.acciones = acciones;
		this.apiActor = apiActor;
	}

	@PostMapping(path = "/confirmacion", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "confirmarAccionDelAsistente", summary = "Confirmar una accion propuesta por el asistente",
			description = """
					Graba la accion con lo que quedo en la tarjeta. Requiere `asistente:acciones`, el \
					interruptor `akine.asistente.acciones.enabled` y, ademas, el permiso de la operacion \
					que se graba (`asistencia:manage`, `cobro:register`, `sesion:register`, `turno:manage` \
					o `paciente:manage`): sin ese permiso responde 200 con `estado` RECHAZADA.

					Solo la confirma la cuenta que la pidio, en la misma sede, antes de que venza. \
					**Confirmar dos veces no graba de nuevo:** la segunda responde CONFIRMADA con el mismo \
					`enlace` y un `texto` que lo dice. Un rechazo \
					del negocio (la caja esta cerrada, la ficha cambio) vuelve con `estado` RECHAZADA y la \
					propuesta sigue abierta para corregirla y reintentar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Como termino"),
			@ApiResponse(responseCode = "400", description = "`validation-error`: la tarjeta no trae exactamente un "
					+ "bloque, o no es la de la propuesta (otro tipo u otra persona)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin `asistente:acciones` o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "`not-found`: la accion no existe o es de otra cuenta, "
					+ "sede u organizacion",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "`accion-no-vigente`: vencio, se descarto o la esta "
					+ "confirmando otro pedido. `acciones-deshabilitadas`: el interruptor esta apagado",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResultadoDeAccionResponse confirmarAccionDelAsistente(
			@PathVariable long consultorioId,
			@PathVariable long accionId,
			@RequestBody @Valid ConfirmarAccionRequest request) {

		return ResultadoDeAccionResponse.de(acciones.confirmar(apiActor.current(), consultorioId, accionId,
				request.aTarjeta()));
	}

	@PostMapping(path = "/descarte")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "descartarAccionDelAsistente", summary = "Descartar una accion propuesta por el asistente",
			description = "No graba nada. Descartar una accion que ya no esta propuesta no hace nada. Requiere "
					+ "`asistente:acciones`.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Descartada"),
			@ApiResponse(responseCode = "403", description = "Sin `asistente:acciones` o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "`not-found`: la accion no existe o es de otra cuenta, "
					+ "sede u organizacion",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "`acciones-deshabilitadas`: el interruptor esta apagado",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public void descartarAccionDelAsistente(@PathVariable long consultorioId, @PathVariable long accionId) {
		acciones.descartar(apiActor.current(), consultorioId, accionId);
	}
}
