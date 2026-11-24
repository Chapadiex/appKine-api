package com.akine.organization.api;

import com.akine.organization.api.dto.EstadoDelMotorResponse;
import com.akine.organization.api.dto.FuncionesIaResponse;
import com.akine.organization.api.dto.PruebaDelMotorResponse;
import com.akine.organization.api.dto.UpdateFuncionesIaRequest;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.FuncionesIaService;
import com.akine.platform.spi.modelo.MotorDeModelos;
import com.akine.platform.spi.modelo.agente.ConfiguracionDelAgente;
import com.akine.platform.spi.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Funciones de IA del centro y estado del servidor de modelos.
 *
 * <p>Leer que funciones estan activas alcanza con ser miembro vigente: la aplicacion de todos los
 * roles lo necesita para mostrar u ocultar el asistente, la biblioteca y la evolucion. Cambiarlas, y
 * mirar o ejercitar el motor, exige {@code tenant:update}: solo el ORG_ADMIN.
 */
@RestController
@RequestMapping(path = "/api/v1/organizations/{orgId}/ia", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Inteligencia artificial del centro",
		description = "Que funciones de IA usa el centro y en que estado esta el servidor de modelos")
public class FuncionesIaController {

	private static final Duration PLAZO_ESTADO = Duration.ofSeconds(5);
	private static final Duration PLAZO_CARGA = Duration.ofSeconds(40);

	private final FuncionesIaService funcionesIaService;
	private final MotorDeModelos motor;
	private final ConfiguracionDelAgente configuracionDelAgente;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public FuncionesIaController(
			FuncionesIaService funcionesIaService,
			MotorDeModelos motor,
			ConfiguracionDelAgente configuracionDelAgente,
			AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.funcionesIaService = funcionesIaService;
		this.motor = motor;
		this.configuracionDelAgente = configuracionDelAgente;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping
	@Operation(
			operationId = "getFuncionesIaDeOrganizacion",
			summary = "Funciones de IA del centro",
			description = "Por cada funcion: si el servidor la ofrece, si el centro la quiere y si queda "
					+ "activa. Requiere ser miembro vigente. Un centro que nunca la edito responde todo lo "
					+ "que ofrece el servidor como activo, con version 0.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Funciones de IA",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = FuncionesIaResponse.class))),
			@ApiResponse(responseCode = "403", description = "No hay sesion autenticada o falta el contexto",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, esta dada de baja, o es de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<FuncionesIaResponse> ver(
			@Parameter(description = "Identificador de la organizacion", example = "1") @PathVariable long orgId) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requireMember(
				actor.accountId(), orgId, actor.contextOrganizationId(), actor.platformAdmin());
		return ResponseEntity.ok(FuncionesIaResponse.from(funcionesIaService.ver(orgId)));
	}

	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateFuncionesIaDeOrganizacion",
			summary = "Elegir que funciones de IA usa el centro",
			description = """
					Reemplazo completo de los cuatro interruptores. Requiere tenant:update (solo el \
					ORG_ADMIN). Prender una funcion que el servidor no ofrece se guarda igual y queda \
					inactiva hasta que el despliegue la tenga. Apagar EVOLUCION o BIBLIOTECA deja \
					inactivas las SUGERENCIAS sin tocar su interruptor.

					La version enviada se compara antes de mutar; si quedo vieja, 409 \
					concurrent-modification.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Funciones actualizadas, con la version nueva",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = FuncionesIaResponse.class))),
			@ApiResponse(responseCode = "400", description = "Falta algun interruptor o la version",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "El actor no es ORG_ADMIN de la organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, esta dada de baja, o es de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La version enviada quedo vieja: releer y reintentar",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<FuncionesIaResponse> actualizar(
			@Parameter(description = "Identificador de la organizacion", example = "1") @PathVariable long orgId,
			@Valid @RequestBody UpdateFuncionesIaRequest request) {
		ApiActor actor = exigirEditor(orgId);
		return ResponseEntity.ok(FuncionesIaResponse.from(funcionesIaService.actualizar(orgId,
				request.asistente(), request.evolucion(), request.sugerencias(), request.biblioteca(),
				request.version(), actor.accountId())));
	}

	@GetMapping("/motor")
	@Operation(
			operationId = "getEstadoDelMotorDeIa",
			summary = "Estado del servidor de modelos",
			description = "Si Ollama responde, que modelo usa cada funcion, si esta descargado y cargado, y si "
					+ "corre en GPU o en CPU, y que proveedor usa el asistente (sin la key, solo si hay una). Siempre 200: "
					+ "un servidor caido viaja con alcanzable=false. "
					+ "Requiere tenant:update.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Estado del motor",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EstadoDelMotorResponse.class))),
			@ApiResponse(responseCode = "403", description = "El actor no es ORG_ADMIN de la organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, esta dada de baja, o es de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EstadoDelMotorResponse> motor(
			@Parameter(description = "Identificador de la organizacion", example = "1") @PathVariable long orgId) {
		exigirEditor(orgId);
		return ResponseEntity.ok(EstadoDelMotorResponse.from(motor.estado(PLAZO_ESTADO), configuracionDelAgente));
	}

	@PostMapping("/motor/precarga")
	@Operation(
			operationId = "precargarMotorDeIa",
			summary = "Cargar los modelos en memoria",
			description = "Pide a Ollama cargar cada modelo configurado para que la primera pregunta no pague "
					+ "la carga, y devuelve el estado despues. Espera hasta 40 s (el nginx corta a los 60); una sola carga o prueba por vez en todo el servidor. "
					+ "sigue cargando en el servidor y el estado lo muestra al volver a consultarlo. Requiere tenant:update.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Estado del motor despues de cargar",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EstadoDelMotorResponse.class))),
			@ApiResponse(responseCode = "403", description = "El actor no es ORG_ADMIN de la organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, esta dada de baja, o es de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EstadoDelMotorResponse> precargar(
			@Parameter(description = "Identificador de la organizacion", example = "1") @PathVariable long orgId) {
		exigirEditor(orgId);
		return ResponseEntity.ok(EstadoDelMotorResponse.from(motor.precargar(PLAZO_CARGA), configuracionDelAgente));
	}

	@PostMapping("/motor/prueba")
	@Operation(
			operationId = "probarMotorDeIa",
			summary = "Probar el modelo",
			description = "Una pregunta minima al modelo del asistente, con el tiempo que tardo. Siempre 200: "
					+ "un fallo viaja con ok=false. No usa datos del centro. Requiere tenant:update.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Resultado de la prueba",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PruebaDelMotorResponse.class))),
			@ApiResponse(responseCode = "403", description = "El actor no es ORG_ADMIN de la organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, esta dada de baja, o es de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PruebaDelMotorResponse> probar(
			@Parameter(description = "Identificador de la organizacion", example = "1") @PathVariable long orgId) {
		exigirEditor(orgId);
		return ResponseEntity.ok(PruebaDelMotorResponse.from(motor.probar(PLAZO_CARGA)));
	}

	private ApiActor exigirEditor(long orgId) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requireOrgDataEditor(
				actor.accountId(), orgId, actor.contextOrganizationId(), actor.platformAdmin());
		return actor;
	}
}
