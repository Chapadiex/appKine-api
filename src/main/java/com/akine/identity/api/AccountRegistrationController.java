package com.akine.identity.api;

import com.akine.identity.api.dto.AcceptedResponse;
import com.akine.identity.api.dto.ActivateAccountRequest;
import com.akine.identity.api.dto.RegisterAccountRequest;
import com.akine.identity.api.dto.ResendActivationRequest;
import com.akine.identity.application.AccountActivationService;
import com.akine.identity.application.OnboardingService;
import com.akine.identity.application.RegistroCuentaCommand;
import com.akine.identity.application.ResultadoRegistro;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alta self-service y activacion de la cuenta (RF-M02-001, ADR-0008).
 *
 * <h2>Dos endpoints que responden lo mismo pase lo que pase</h2>
 *
 * <p>El registro y el reenvio de activacion devuelven <b>202 con el mismo cuerpo</b> exista o
 * no la direccion, este ya activa o este bloqueada (ADR-0018). No hay {@code Location}, no hay
 * identificadores en la respuesta y no hay 409 de "email en uso". El motivo esta en el ADR y
 * conviene tenerlo presente antes de "arreglar" esto: el registro es el endpoint mas facil de
 * automatizar del sistema —no necesita ni siquiera una contrasena valida para preguntar— y
 * cualquier diferencia observable lo convierte en un verificador de direcciones de correo.
 * Sobre un sistema de historia clinica, ese padron dice quien es paciente o profesional de un
 * centro.
 *
 * <p>El usuario legitimo que se olvido de que ya tenia cuenta igual llega a destino: recibe un
 * correo de "ya tenes cuenta, inicia sesion o recupera tu contrasena". Llega por el canal que
 * prueba que la direccion es suya, no por la respuesta HTTP que ve cualquiera.
 *
 * <h2>Idempotencia del alta</h2>
 *
 * <p>El registro exige {@code Idempotency-Key} y lo trata como {@code OrganizationController}:
 * identifica el intento, de modo que un reintento por timeout de red no cree una segunda cuenta
 * ni mande un segundo correo. A diferencia del alta administrativa, aca la clave <b>si</b> se
 * registra: hay un propietario del intento y el desenlace se puede reproducir.
 */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Registro y activacion", description = "Alta self-service y habilitacion de la cuenta")
public class AccountRegistrationController {

	private static final Logger log = LoggerFactory.getLogger(AccountRegistrationController.class);

	/**
	 * Texto del acuse del registro.
	 *
	 * <p>Constante y no armado por caso: si se construyera segun lo que ocurrio, tarde o
	 * temprano alguien le agregaria un matiz y ahi se filtraria la diferencia.
	 */
	private static final String ACUSE_REGISTRO =
			"Recibimos tu solicitud. Si el email no tenia cuenta, vas a recibir un enlace para "
					+ "activarla; si ya tenia, vas a recibir un aviso para iniciar sesion.";

	private static final String ACUSE_ACTIVACION =
			"Si el email corresponde a una cuenta pendiente de activacion, vas a recibir un "
					+ "enlace nuevo.";

	private final OnboardingService onboardingService;
	private final AccountActivationService activationService;

	public AccountRegistrationController(
			OnboardingService onboardingService,
			AccountActivationService activationService) {
		this.onboardingService = onboardingService;
		this.activationService = activationService;
	}

	@PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "registerAccount",
			summary = "Alta self-service de cuenta, organizacion y primer consultorio",
			description = """
					Crea en una sola transaccion la cuenta del fundador, la organizacion, su \
					primer consultorio, la suscripcion y la membership de administrador, y encola \
					el correo con el enlace de activacion.

					RESPUESTA UNIFORME (ADR-0018): 202 Accepted con el mismo cuerpo tanto si el \
					email estaba libre como si ya tenia cuenta. En el segundo caso no se crea ni \
					se modifica nada y se encola un correo de "ya tenes cuenta". No hay 409 \
					email-already-registered y no va a haberlo: seria el oraculo de existencia \
					que el 401 uniforme del login existe para cerrar.

					La cuenta nace PENDIENTE_ACTIVACION. Hasta confirmar el enlace, el login \
					responde 401 igual que con una contrasena incorrecta.

					El header Idempotency-Key es obligatorio. Reintentar el alta con la misma \
					clave devuelve el mismo desenlace sin crear una segunda cuenta ni mandar un \
					segundo correo; el cliente debe generarlo una vez al abrir el formulario y \
					reusarlo en cada reintento.

					Los rechazos posibles no dicen nada sobre el email: 400 por contrasena que \
					no cumple la politica o por campos ausentes, 404 por un planCode que no \
					existe o ya no se contrata, y 409 por un organizationSlug ya tomado. Los \
					tres se evaluan ANTES de mirar si el email tiene cuenta, justamente para \
					que la respuesta sea la misma en los dos casos: validarlos despues \
					convertiria el endpoint en un verificador de direcciones de correo.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "202",
					description = "Solicitud aceptada. Mismo cuerpo exista o no la cuenta",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AcceptedResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, contrasena que no cumple la politica, o "
							+ "falta el header Idempotency-Key",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El planCode pedido no existe o ya no se contrata. Misma "
							+ "respuesta exista o no la cuenta",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El organizationSlug pedido ya lo usa otro tenant. Misma "
							+ "respuesta exista o no la cuenta",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "429",
					description = "Demasiados intentos desde esta IP",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AcceptedResponse> register(
			@Parameter(
					description = "Identificador unico del intento, generado por el cliente. "
							+ "Reintentar la misma alta debe reusar el mismo valor.",
					required = true,
					example = "0f9d5f6e-1c2b-4a3d-9e8f-7a6b5c4d3e2f")
			@RequestHeader("Idempotency-Key") String idempotencyKey,

			@Valid @RequestBody RegisterAccountRequest request) {

		if (idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("El header Idempotency-Key no puede estar vacio");
		}

		ResultadoRegistro resultado = onboardingService.registrar(new RegistroCuentaCommand(
				idempotencyKey,
				// requestHash null: la deduplicacion por contenido no se implementa en 01.02.
				// La clave sola ya impide el doble submit, que es el caso real.
				null,
				request.email(),
				request.password(),
				request.firstName(),
				request.lastName(),
				request.organizationName(),
				request.organizationSlug(),
				request.consultorioName(),
				request.planCode()));

		// El desenlace se loguea y NO se responde. Es la linea que hay que mirar cuando alguien
		// reporte "me registre y no paso nada": ahi se ve si se creo o si el email ya existia.
		// Ni el email ni la contrasena entran al log (RN-M02-003).
		log.info("Alta self-service procesada: idempotencyKey={} cuentaCreada={}",
				idempotencyKey, resultado.cuentaCreada());

		return ResponseEntity.accepted().body(AcceptedResponse.de(ACUSE_REGISTRO));
	}

	// PRODUCES EXPLICITO, Y NO ES DECORACION. El 204 no lleva cuerpo, asi que lo unico que
	// esta operacion declara producir en el contrato es el problem+json de sus errores, y eso es
	// exactamente lo que el cliente generado manda en Accept. Con el produces de clase —solo
	// application/json— Spring respondia 406 ANTES de entrar al metodo, y la activacion por
	// enlace de correo quedaba rota de punta a punta. Declarar los dos deja pasar al cliente
	// generado y a cualquier llamador que pida application/json.
	//
	// No lo agarraba ningun test: los unitarios del frontend usan HttpTestingController, que no
	// negocia contenido, y los de integracion de aca mandan el Accept de MockMvc. Solo aparece
	// con el cliente generado real contra el servidor real.
	@PostMapping(path = "/activate", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE })
	@Operation(
			operationId = "activateAccount",
			summary = "Confirmar el enlace y habilitar la cuenta",
			description = """
					Consume el token del correo, deja la cuenta ACTIVA e invalida los demas \
					enlaces de activacion que hubiera vivos. Si se pidio el reenvio tres veces, \
					el que se use mata a los otros dos: un enlace no usado sigue siendo una llave.

					La contrasena solo hace falta si la cuenta todavia no tiene credencial \
					—invitacion, 01.03—. En el alta self-service ya la tiene y enviarla NO la \
					cambia: cambiarla por este camino seria una toma de cuenta que esquiva el \
					reset, que si revoca todas las sesiones.

					Token inexistente, ya usado, invalidado por uno mas nuevo, vencido o del tipo \
					equivocado responden los cinco el mismo 400 invalid-token. Decir "expirado" \
					le confirmaria a quien prueba valores que acerto uno real.

					Activar NO abre sesion: despues de esto el frontend manda al login.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "204",
					description = "Cuenta activada"),
			@ApiResponse(
					responseCode = "400",
					description = "Token invalido, usado o vencido; o contrasena que no cumple "
							+ "la politica cuando la cuenta no tenia credencial",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La cuenta no admite activarse desde su estado actual",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> activate(@Valid @RequestBody ActivateAccountRequest request) {
		activationService.activar(request.token(), request.password());
		// Ni el id de la cuenta sale en la respuesta: quien activa todavia no esta autenticado,
		// y devolverselo le confirmaria que el token pertenecia a una cuenta concreta.
		return ResponseEntity.noContent().build();
	}

	@PostMapping(path = "/activation/resend", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "resendActivation",
			summary = "Reenviar el enlace de activacion",
			description = """
					Emite un enlace nuevo e invalida los anteriores. Es el camino para "el correo \
					no me llego" o "el enlace vencio".

					RESPUESTA UNIFORME (ADR-0018): 202 con el mismo cuerpo en los cuatro casos \
					—cuenta pendiente, cuenta ya activa, cuenta bloqueada o desactivada, y email \
					sin cuenta—. Solo el primero emite algo. La diferencia no se publica ni por \
					el estado, ni por el cuerpo, ni por un header.

					Emitir invalida los enlaces anteriores: pedir el reenvio tres veces deja UNA \
					llave viva, no tres.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "202",
					description = "Solicitud aceptada. Mismo cuerpo exista o no la cuenta",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AcceptedResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el email",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "429",
					description = "Demasiados intentos desde esta IP",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AcceptedResponse> resendActivation(
			@Valid @RequestBody ResendActivationRequest request) {

		activationService.reenviarActivacion(request.email());
		return ResponseEntity.accepted().body(AcceptedResponse.de(ACUSE_ACTIVACION));
	}
}
