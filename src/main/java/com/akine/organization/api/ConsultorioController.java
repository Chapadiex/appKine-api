package com.akine.organization.api;

import com.akine.organization.api.dto.ConsultorioResponse;
import com.akine.organization.api.dto.CreateConsultorioRequest;
import com.akine.organization.api.dto.DeactivateConsultorioRequest;
import com.akine.organization.api.dto.UpdateConsultorioRequest;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.ConsultorioAltaCommand;
import com.akine.organization.application.ConsultorioEdicionCommand;
import com.akine.organization.application.ConsultorioService;
import com.akine.organization.application.ConsultorioView;
import com.akine.organization.application.OperatingActor;
import com.akine.platform.spi.tenant.TenantContextHolder;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Administracion de sedes de una organizacion (M03).
 *
 * <h2>Que NO esta aca, y por que</h2>
 *
 * <ul>
 *   <li><b>El listado</b> ({@code GET /organizations/{orgId}/consultorios}) vive en
 *       {@code OrganizationController} desde 01.01 y se queda ahi: su autorizacion es "miembro
 *       vigente" y no {@code consultorio:manage}, porque es el insumo del selector de contexto.
 *       Moverlo aca invitaria a alinearle el permiso con el resto de este controller y dejaria
 *       a un {@code PROFESIONAL} sin poder elegir sede.</li>
 *   <li><b>La seleccion de contexto</b> (RF-M03-005) no genera endpoint nuevo: la cumple
 *       {@code POST /api/v1/auth/context}, que existe desde 01.02. Decirlo por escrito evita
 *       que alguien "implemente RF-M03-005" duplicando el canje de token.</li>
 *   <li><b>El primer box de la sede</b> (RF-M03-002) no entra: {@code Box} es del modulo
 *       {@code resource} y llega en AKINE-02.02. Crearlo desde aca violaria el ownership de
 *       modulos, y ArchUnit lo rechaza. <b>CA-M03-002 queda parcialmente cubierto</b> y asi se
 *       declara en el registro de cierre.</li>
 * </ul>
 *
 * <h2>Codigos, y por que no son intercambiables</h2>
 *
 * <ul>
 *   <li><b>404</b> para una sede de otro tenant o inexistente. Un 403 confirmaria que ese id
 *       existe y bastaria recorrer numeros consecutivos para enumerar las sedes del SaaS.</li>
 *   <li><b>403</b> cuando el actor esta dentro de su alcance y le falta el permiso: sobre un
 *       recurso que ya sabe que existe, el 404 no protege nada y ademas le miente.</li>
 *   <li><b>409</b> para los invariantes de estado —ultima sede activa, sede ya inactiva,
 *       nombre repetido, limite de plan, modificacion concurrente—. El actor tiene el permiso;
 *       lo que no admite la operacion es el estado.</li>
 * </ul>
 *
 * <p>Una sede INACTIVA se lee con 200, no con 404: RF-M03-004 exige que los historicos previos
 * permanezcan disponibles, y responder "no existe" seria borrar historia por la puerta de atras.
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/consultorios")
@Tag(name = "Consultorios", description = "Alta, edicion y baja de las sedes de una organizacion")
public class ConsultorioController {

	private static final Logger log = LoggerFactory.getLogger(ConsultorioController.class);

	/**
	 * Separador del payload canonico del hash de idempotencia.
	 *
	 * <p>Es el {@code UNIT SEPARATOR} de ASCII y no una cadena vacia: concatenar sin separador
	 * haria que ("ab", "c") y ("a", "bc") produjeran el mismo hash, y dos altas distintas con la
	 * misma clave pasarian por reintento. Es un caracter de control que no puede aparecer en
	 * ninguno de los campos.
	 */
	private static final String SEPARADOR_CANONICO = String.valueOf((char) 0x1F);

	private final ConsultorioService consultorioService;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public ConsultorioController(
			ConsultorioService consultorioService,
			AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.consultorioService = consultorioService;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createConsultorio",
			summary = "Alta de una sede adicional",
			description = """
					Crea una sede nueva en la organizacion. La PRIMERA sede no se crea por aca: \
					la crea el alta de la organizacion en la misma transaccion, porque el \
					contexto de trabajo es Organizacion + Consultorio y un tenant sin sede no \
					ofreceria ningun contexto seleccionable.

					Exige consultorio:manage con alcance ORGANIZACION. Un CONSULTORIO_ADMIN no \
					puede crear sedes, y no hace falta ninguna regla especial para impedirlo: un \
					alta no tiene sede objetivo —todavia no existe— asi que la interseccion con \
					un alcance de una sola sede es vacia.

					Pasa por el limite MAX_CONSULTORIOS del plan contratado. El header \
					Idempotency-Key es obligatorio: reintentar con la misma clave y el mismo \
					cuerpo devuelve la misma sede en vez de crear una segunda, que es lo que \
					cubre el reintento despues de un timeout de red.

					El primer box y el horario general de la sede NO se crean aca. Box es del \
					modulo de espacios (AKINE-02.02) y el horario general depende de la \
					disponibilidad profesional, que llega despues.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Sede creada, o la misma sede si es un reintento con la misma "
							+ "clave de idempotencia. La cabecera Location apunta al recurso",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ConsultorioResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, zona horaria que no es un identificador "
							+ "IANA, o falta el header Idempotency-Key",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Falta consultorio:manage con alcance organizacion, o no hay "
							+ "contexto de trabajo seleccionado",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La organizacion no existe, esta dada de baja, o es de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Nombre repetido entre las sedes vigentes "
							+ "(consultorio-name-taken), limite del plan alcanzado "
							+ "(plan-limit-exceeded), suscripcion suspendida "
							+ "(subscription-suspended), o clave de idempotencia reusada con "
							+ "otro contenido (idempotency-key-conflict)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ConsultorioResponse> create(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Parameter(
					description = "Identificador unico del intento, generado por el cliente. "
							+ "Reintentar la misma alta debe reusar el mismo valor.",
					required = true,
					example = "0f9d5f6e-1c2b-4a3d-9e8f-7a6b5c4d3e2f")
			@RequestHeader("Idempotency-Key") String idempotencyKey,

			@Valid @RequestBody CreateConsultorioRequest request) {

		if (idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("El header Idempotency-Key no puede estar vacio");
		}

		// La clave va al log y nunca a la respuesta: es lo que permite correlacionar un
		// reintento por timeout de red con el alta original cuando alguien investiga un
		// duplicado. El nombre de la sede no se loguea: es dato comercial del cliente.
		log.info("Alta de sede solicitada: organizationId={} idempotencyKey={}",
				orgId, idempotencyKey);

		ConsultorioView creada = consultorioService.create(actor(), orgId,
				new ConsultorioAltaCommand(
						request.name(),
						request.timezone(),
						request.slotMinutes(),
						request.legalName(),
						request.taxId(),
						request.addressLine(),
						request.phone(),
						request.contactEmail(),
						idempotencyKey,
						hashDe(request)));

		return ResponseEntity
				.created(URI.create("/api/v1/organizations/" + orgId + "/consultorios/" + creada.id()))
				.body(ConsultorioResponse.from(creada));
	}

	@GetMapping("/{consultorioId}")
	@Operation(
			operationId = "getConsultorio",
			summary = "Datos de una sede",
			description = """
					Devuelve la sede con su configuracion y la version que hay que reenviar para \
					editarla.

					Devuelve tambien las sedes INACTIVAS, con 200 y no con 404: RF-M03-004 exige \
					que los historicos previos permanezcan disponibles. Lo que una sede inactiva \
					rechaza son las operaciones nuevas, y eso se responde con 409.

					Exige consultorio:manage sobre ESA sede, asi que un CONSULTORIO_ADMIN puede \
					leer la suya y no las demas. Una sede de otro tenant responde 404, igual que \
					una inexistente.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "La sede, activa o dada de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ConsultorioResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Falta consultorio:manage sobre esa sede, o no hay contexto",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "No existe o pertenece a otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ConsultorioResponse> find(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId) {

		return ResponseEntity.ok(ConsultorioResponse.from(
				consultorioService.find(actor(), orgId, consultorioId)));
	}

	@PatchMapping(path = "/{consultorioId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateConsultorio",
			summary = "Edicion de una sede",
			description = """
					Cambia nombre, zona horaria, intervalo de agenda y datos institucionales \
					(RF-M03-003). Los campos omitidos no se tocan; para borrar un dato \
					institucional se manda cadena vacia.

					version es obligatoria y se compara antes de mutar: si quedo vieja, la \
					respuesta es 409 concurrent-modification y el cliente recarga. Sin eso, dos \
					ediciones simultaneas se pisan y el segundo en guardar borra el cambio del \
					primero sin que nadie se entere.

					Una sede INACTIVA no se puede editar: 409 consultorio-inactive.

					Cambiar la zona horaria NO reinterpreta el pasado. Los instantes ya \
					registrados son UTC y no se mueven; cambia como se proyectan de aca en \
					adelante, y la pantalla tiene que decirlo con esas palabras.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Sede actualizada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ConsultorioResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos o zona horaria que no es un identificador IANA",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Falta consultorio:manage sobre esa sede, o no hay contexto",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "No existe o pertenece a otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Sede dada de baja (consultorio-inactive), nombre repetido "
							+ "(consultorio-name-taken) o version desactualizada "
							+ "(concurrent-modification)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ConsultorioResponse> update(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Valid @RequestBody UpdateConsultorioRequest request) {

		ConsultorioView actualizada = consultorioService.update(actor(), orgId, consultorioId,
				new ConsultorioEdicionCommand(
						request.name(),
						request.timezone(),
						request.slotMinutes(),
						request.legalName(),
						request.taxId(),
						request.addressLine(),
						request.phone(),
						request.contactEmail(),
						request.version()));

		return ResponseEntity.ok(ConsultorioResponse.from(actualizada));
	}

	@PostMapping(path = "/{consultorioId}/deactivate", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "deactivateConsultorio",
			summary = "Baja logica de una sede",
			description = """
					Da de baja la sede con motivo obligatorio (RF-M03-004). NO borra nada: la \
					sede queda INACTIVA, sigue siendo legible por id y conserva intacta toda su \
					historia. No hay reactivacion: ningun RF de M03 la pide.

					Dos rechazos que hay que mostrar con su mensaje real y no con un generico:

					- last-consultorio-required: es la ultima sede activa del tenant. Sin ninguna \
					sede activa nadie podria canjear contexto y la organizacion quedaria \
					operativamente muerta, recuperable solo con intervencion manual. Es una \
					restriccion agregada al comportamiento del requerimiento, decidida \
					explicitamente.
					- consultorio-has-active-references: la sede tiene turnos pendientes \
					(reservados, confirmados o en espera, y que todavia no terminaron, incluido \
					el que esta en curso). El cuerpo trae el tipo turnos-futuros y cuantos son. \
					La baja no cancela esos turnos en cascada: hay que resolverlos antes, uno por \
					uno, y recien ahi la sede se puede dar de baja.

					Exige consultorio:manage con alcance ORGANIZACION, o sea que hoy solo un \
					ORG_ADMIN puede dar de baja una sede. Es mas estricto que la lectura literal \
					de la matriz de permisos, por el mismo criterio que impide que alguien se \
					quite a si mismo su ultimo rol administrativo: nadie destruye el alcance \
					desde el que opera. Queda como enmienda pendiente de confirmacion.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Sede dada de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ConsultorioResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo de la baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Falta consultorio:manage con alcance organizacion, o no hay "
							+ "contexto de trabajo seleccionado",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "No existe o pertenece a otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Es la ultima sede activa (last-consultorio-required), ya "
							+ "estaba dada de baja (consultorio-already-inactive), o tiene "
							+ "referencias vigentes (consultorio-has-active-references)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ConsultorioResponse> deactivate(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Parameter(description = "Identificador de la sede", example = "1")
			@PathVariable long consultorioId,

			@Valid @RequestBody DeactivateConsultorioRequest request) {

		ConsultorioView baja =
				consultorioService.deactivate(actor(), orgId, consultorioId, request.reason());
		return ResponseEntity.ok(ConsultorioResponse.from(baja));
	}

	/**
	 * Traduce el request al actor que espera {@code application}.
	 *
	 * <p>La sede sale del contexto ya revalidado contra la base y jamas de un parametro: sin
	 * ella, el evaluador no puede decidir un permiso de alcance CONSULTORIO y un
	 * {@code CONSULTORIO_ADMIN} quedaria sin ninguno.
	 */
	private OperatingActor actor() {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		return new OperatingActor(
				actor.accountId(), actor.platformAdmin(), authorizationGuard.consultorioDelContexto());
	}

	/**
	 * SHA-256 del payload canonico del alta.
	 *
	 * <p>Sirve para detectar la MISMA clave de idempotencia con un contenido DISTINTO, que es un
	 * error del cliente y no un reintento: devolverle el resultado viejo lo dejaria creyendo que
	 * se creo lo que pidio ahora.
	 *
	 * <p>Se calcula sobre una concatenacion con separador explicito y no sobre el JSON crudo: el
	 * orden de las claves y los espacios de un JSON no son estables entre clientes, y un hash
	 * sobre el texto crudo convertiria un reintento legitimo en un 409.
	 */
	private static String hashDe(CreateConsultorioRequest request) {
		String canonico = String.join(SEPARADOR_CANONICO,
				normalizar(request.name()),
				normalizar(request.timezone()),
				request.slotMinutes() == null ? "" : String.valueOf(request.slotMinutes()),
				normalizar(request.legalName()),
				normalizar(request.taxId()),
				normalizar(request.addressLine()),
				normalizar(request.phone()),
				normalizar(request.contactEmail()));
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha256.digest(canonico.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException imposible) {
			// SHA-256 es obligatorio en toda JVM. Si faltara, seguir sin hash desactivaria en
			// silencio la deteccion de clave reusada con otro contenido: mejor ruidoso.
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}

	private static String normalizar(String valor) {
		return valor == null ? "" : valor.strip().toLowerCase(Locale.ROOT);
	}
}
