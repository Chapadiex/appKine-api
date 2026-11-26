package com.akine.assistant.api;

import com.akine.assistant.api.dto.BotDeTelegramResponse;
import com.akine.assistant.api.dto.ConectarBotDeTelegramRequest;
import com.akine.assistant.api.dto.VinculoDeTelegramResponse;
import com.akine.assistant.application.TelegramService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Carga por Telegram (docs/diseno/AKINE-telegram.md): el bot del centro y el vinculo de cada cuenta.
 * Los mensajes no pasan por aca: los trae el sondeo del backend.
 *
 * <p>Las operaciones 204 declaran tambien {@code application/json}: el cliente generado manda
 * {@code Accept: application/problem+json} y sin eso responderian 406.
 */
@RestController
@Tag(name = "Telegram", description = "Cargar en AKINE contando lo que se hizo por el bot de Telegram del centro")
public class TelegramController {

	private static final String PRODUCE = MediaType.APPLICATION_JSON_VALUE;
	private static final String PROBLEMA = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final TelegramService telegram;
	private final AsistenteApiActor apiActor;

	public TelegramController(TelegramService telegram, AsistenteApiActor apiActor) {
		this.telegram = telegram;
		this.apiActor = apiActor;
	}

	@GetMapping(path = "/api/v1/organizations/{orgId}/telegram", produces = {PRODUCE, PROBLEMA})
	@Operation(operationId = "getBotDeTelegram", summary = "El bot de Telegram del centro",
			description = "Si hay un bot conectado, su usuario y si el sondeo anda. Nunca devuelve el token. "
					+ "Requiere `tenant:update` (el ORG_ADMIN).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El bot, o `conectado` false"),
			@ApiResponse(responseCode = "403", description = "Sin `tenant:update` o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "`not-found`: otra organizacion",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "`telegram-deshabilitado`: el interruptor esta apagado",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public BotDeTelegramResponse getBotDeTelegram(@PathVariable long orgId) {
		return BotDeTelegramResponse.de(telegram.verBot(apiActor.current(), orgId).orElse(null));
	}

	@PutMapping(path = "/api/v1/organizations/{orgId}/telegram", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = {PRODUCE, PROBLEMA})
	@Operation(operationId = "conectarBotDeTelegram", summary = "Conectar el bot de Telegram del centro",
			description = """
					Valida el token con Telegram y lo guarda cifrado; reemplaza al bot anterior. Desde ese \
					momento el backend lee los mensajes del bot cada pocos segundos. Requiere `tenant:update`.

					El bot se crea en Telegram con @BotFather (`/newbot`), y conviene desactivarle el modo \
					privado (`/setprivacy` → Disable) para que lea los mensajes de un grupo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El bot conectado"),
			@ApiResponse(responseCode = "400", description = "`validation-error`: el token no tiene la forma de un "
					+ "token, o Telegram no lo reconoce",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin `tenant:update` o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "`not-found`: otra organizacion",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "`telegram-deshabilitado`: el interruptor esta apagado",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public BotDeTelegramResponse conectarBotDeTelegram(@PathVariable long orgId,
			@RequestBody @Valid ConectarBotDeTelegramRequest request) {
		return BotDeTelegramResponse.de(telegram.conectar(apiActor.current(), orgId, request.token()));
	}

	@DeleteMapping(path = "/api/v1/organizations/{orgId}/telegram", produces = {PRODUCE, PROBLEMA})
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "desconectarBotDeTelegram", summary = "Desconectar el bot de Telegram del centro",
			description = "Borra el token: el bot deja de leerse. Los vinculos de las cuentas quedan. Requiere "
					+ "`tenant:update`.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Desconectado"),
			@ApiResponse(responseCode = "403", description = "Sin `tenant:update` o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "`not-found`: otra organizacion",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "`telegram-deshabilitado`: el interruptor esta apagado",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public void desconectarBotDeTelegram(@PathVariable long orgId) {
		telegram.desconectar(apiActor.current(), orgId);
	}

	@GetMapping(path = "/api/v1/consultorios/{consultorioId}/telegram/vinculo", produces = {PRODUCE, PROBLEMA})
	@Operation(operationId = "getMiVinculoDeTelegram", summary = "Mi cuenta con Telegram",
			description = "Si la cuenta de quien pregunta esta vinculada, el codigo pendiente y el bot del centro. "
					+ "Requiere `asistente:acciones` en la sede.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El vinculo"),
			@ApiResponse(responseCode = "403", description = "Sin `asistente:acciones` o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "`not-found`: la sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "`telegram-deshabilitado`: el interruptor esta apagado",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public VinculoDeTelegramResponse getMiVinculoDeTelegram(@PathVariable long consultorioId) {
		return VinculoDeTelegramResponse.de(telegram.verVinculo(apiActor.current(), consultorioId), Instant.now());
	}

	@PostMapping(path = "/api/v1/consultorios/{consultorioId}/telegram/vinculo", produces = {PRODUCE, PROBLEMA})
	@Operation(operationId = "generarCodigoDeTelegram", summary = "Pedir un codigo para vincular mi Telegram",
			description = """
					Un codigo de seis digitos, de un solo uso, que vence a los 15 minutos. Se le manda al bot \
					del centro como `/vincular CODIGO`. Al usarlo, lo que la persona mande por Telegram se \
					carga con su cuenta, en esta sede. Si ya estaba vinculada, sigue igual hasta usar el \
					codigo. Requiere `asistente:acciones` en la sede.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El vinculo con el codigo"),
			@ApiResponse(responseCode = "403", description = "Sin `asistente:acciones` o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "`not-found`: la sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "`telegram-deshabilitado`: el interruptor esta apagado. "
					+ "`telegram-sin-bot`: el centro no conecto un bot",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public VinculoDeTelegramResponse generarCodigoDeTelegram(@PathVariable long consultorioId) {
		return VinculoDeTelegramResponse.de(telegram.generarCodigo(apiActor.current(), consultorioId), Instant.now());
	}

	@DeleteMapping(path = "/api/v1/consultorios/{consultorioId}/telegram/vinculo", produces = {PRODUCE, PROBLEMA})
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "desvincularMiTelegram", summary = "Desvincular mi Telegram",
			description = "Lo que se mande desde ese usuario de Telegram deja de cargarse. Requiere "
					+ "`asistente:acciones` en la sede.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Desvinculado"),
			@ApiResponse(responseCode = "403", description = "Sin `asistente:acciones` o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "`not-found`: la sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "`telegram-deshabilitado`: el interruptor esta apagado",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public void desvincularMiTelegram(@PathVariable long consultorioId) {
		telegram.desvincular(apiActor.current(), consultorioId);
	}
}
