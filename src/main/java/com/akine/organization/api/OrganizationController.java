package com.akine.organization.api;

import com.akine.organization.api.dto.ConsultorioPageResponse;
import com.akine.organization.api.dto.CreateOrganizationRequest;
import com.akine.organization.api.dto.OrganizationResponse;
import com.akine.organization.api.dto.UpdateOrganizationRequest;
import com.akine.organization.application.ConsultorioEstadoFiltro;
import com.akine.organization.application.ConsultorioView;
import com.akine.organization.application.OrganizationService;
import com.akine.organization.application.OrganizationView;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.platform.spi.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Administracion del tenant: alta, lectura, edicion y sus sedes.
 *
 * <h2>Cross-tenant es 404, jamas 403</h2>
 * Pedir una organizacion que no es la del contexto activo se responde igual que pedir una que
 * no existe. Un 403 confirmaria que existe, y alcanzaria con probar ids consecutivos para
 * enumerar los clientes del SaaS. El 403 queda reservado para el otro caso: el actor esta
 * dentro de su alcance pero le falta el permiso —por ejemplo, un miembro que intenta editar.
 *
 * <h2>Quien decide los permisos</h2>
 * {@link AuthorizationGuard}, siempre. Los controllers no evaluan reglas por su
 * cuenta ni las duplican: cuando 01.03 traiga la evaluacion fina de permisos, se reemplaza un
 * solo lugar y estos metodos no cambian.
 *
 * <p>Capa {@code api}: solo HTTP. Ninguna entity JPA cruza este borde; lo que sale son DTO
 * construidos desde las vistas de {@code application}.
 */
@RestController
@RequestMapping(path = "/api/v1/organizations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Organizaciones", description = "Alta y administracion de tenants y sus sedes")
public class OrganizationController {

	private static final Logger log = LoggerFactory.getLogger(OrganizationController.class);

	private final OrganizationService organizationService;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public OrganizationController(
			OrganizationService organizationService,
			AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.organizationService = organizationService;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createOrganization",
			summary = "Alta administrativa de una organizacion",
			description = """
					Crea la organizacion, su primer consultorio y su suscripcion en una sola \
					transaccion. Reservado a la administracion de plataforma.

					El primer consultorio se crea SIEMPRE, incluso por este camino: el contexto de \
					trabajo es Organizacion + Consultorio, asi que un tenant sin ninguna sede no \
					ofreceria ningun contexto seleccionable y quien entrara despues no tendria \
					donde trabajar.

					Este NO es el camino del alta self-service. Esa crea ademas la cuenta y la \
					membership del fundador en la misma transaccion, y la resuelve el modulo de \
					identidad en 01.02.

					El header Idempotency-Key es obligatorio: identifica el intento, de modo que un \
					reintento por timeout de red quede correlacionado en el log y en la auditoria \
					en vez de parecer un alta distinta. En 01.01 el alta duplicada la cierra la \
					unicidad del slug con 409 conflict, no un replay con 200: este camino \
					administrativo no registra idempotencia porque no tiene propietario que \
					registrar, y el registro de onboarding exige uno.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Organizacion creada. La cabecera Location apunta al recurso nuevo",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OrganizationResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, o falta el header Idempotency-Key",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "La operacion esta reservada a la administracion de plataforma",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El plan indicado no existe o ya no es contratable",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El slug ya pertenece a otra organizacion, o la clave de "
							+ "idempotencia se reuso con otro contenido",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<OrganizationResponse> create(
			@Parameter(
					description = "Identificador unico del intento, generado por el cliente. "
							+ "Reintentar la misma alta debe reusar el mismo valor.",
					required = true,
					example = "0f9d5f6e-1c2b-4a3d-9e8f-7a6b5c4d3e2f")
			@RequestHeader("Idempotency-Key") String idempotencyKey,

			@Valid @RequestBody CreateOrganizationRequest request) {

		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());

		if (idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("El header Idempotency-Key no puede estar vacio");
		}

		// La clave se registra en el log y no en la respuesta: es lo que permite correlacionar
		// un reintento por timeout de red con el alta original cuando alguien investiga un
		// duplicado. No se loguea el payload: lleva el nombre comercial del cliente.
		log.info("Alta administrativa de organizacion solicitada: idempotencyKey={} planCode={}",
				idempotencyKey, request.planCode());

		OrganizationView creada = organizationService.create(
				request.name(),
				request.slug(),
				request.timezone(),
				request.planCode(),
				actor.accountId());

		return ResponseEntity
				.created(URI.create("/api/v1/organizations/" + creada.id()))
				.body(OrganizationResponse.from(creada));
	}

	@GetMapping("/{orgId}")
	@Operation(
			operationId = "getOrganization",
			summary = "Datos de una organizacion",
			description = "Devuelve el tenant con su estado operativo efectivo y la version que "
					+ "hay que reenviar para editarlo. Requiere ser miembro vigente de esa "
					+ "organizacion y que sea la del contexto activo. Una organizacion inexistente, "
					+ "dada de baja o de otro tenant se responden las tres igual con 404: "
					+ "distinguirlas permitiria enumerar los clientes del SaaS probando ids.")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Organizacion encontrada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OrganizationResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "No existe, esta dada de baja, o pertenece a otro tenant. Los "
							+ "tres casos se responden igual",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<OrganizationResponse> find(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId) {

		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requireMember(
				actor.accountId(), orgId, actor.contextOrganizationId(), actor.platformAdmin());

		return ResponseEntity.ok(OrganizationResponse.from(organizationService.find(orgId)));
	}

	@PatchMapping(path = "/{orgId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateOrganization",
			summary = "Editar los datos mutables de la organizacion",
			description = """
					Actualiza nombre y zona horaria. Requiere administrar esa organizacion.

					El slug no se puede cambiar: se usa en URLs y en soporte, y renombrarlo rompe \
					enlaces. Cambiar de identificador es crear otro tenant, no editar este.

					La version enviada se compara antes de mutar. Si quedo vieja porque alguien \
					mas edito mientras tanto, la respuesta es 409 conflict y hay que releer y \
					reintentar: es preferible a pisar en silencio el cambio de otro.

					Los campos omitidos o vacios se dejan como estan; no los borra.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Organizacion actualizada, con la version nueva",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OrganizationResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, o falta la version",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "El actor esta en el tenant correcto pero no lo administra",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "No existe, esta dada de baja, o pertenece a otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La version enviada quedo vieja: releer y reintentar",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<OrganizationResponse> update(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Valid @RequestBody UpdateOrganizationRequest request) {

		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requireOrgAdmin(
				actor.accountId(), orgId, actor.contextOrganizationId(), actor.platformAdmin());

		OrganizationView actualizada = organizationService.update(
				orgId, request.name(), request.timezone(), request.version(), actor.accountId());

		return ResponseEntity.ok(OrganizationResponse.from(actualizada));
	}

	@GetMapping("/{orgId}/consultorios")
	@Operation(
			operationId = "listOrganizationConsultorios",
			summary = "Sedes de la organizacion",
			description = "Listado paginado de las sedes del tenant. Requiere ser miembro "
					+ "vigente de esa organizacion, y NO consultorio:manage: es la lectura que "
					+ "necesita el selector de contexto de trabajo, asi que restringirla dejaria "
					+ "a un profesional sin poder elegir sede. El parametro estado es opcional y "
					+ "su valor por defecto, ACTIVO, conserva el comportamiento historico del "
					+ "endpoint: un cliente que no lo manda ve exactamente lo mismo que antes. "
					+ "El tamano de pagina se acota a "
					+ ApiPaging.TAMANO_MAXIMO + ": pedir mas devuelve ese maximo, no un error.")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Pagina de sedes. Contenido vacio si la pagina quedo fuera de rango",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ConsultorioPageResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "El valor de estado no pertenece al catalogo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "No existe, esta dada de baja, o pertenece a otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ConsultorioPageResponse> consultorios(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Parameter(
					description = "Que sedes devolver. ACTIVO son las vigentes, INACTIVO las "
							+ "dadas de baja, TODOS ambas. Una sede dada de baja conserva su "
							+ "historia y sigue siendo consultable, pero hay que pedirla "
							+ "explicitamente para que no aparezca por descuido en el selector "
							+ "de contexto.",
					example = "ACTIVO")
			@RequestParam(defaultValue = "ACTIVO") ConsultorioEstadoFiltro estado,

			@Parameter(description = "Numero de pagina, base cero", example = "0")
			@RequestParam(defaultValue = ApiPaging.PAGINA_POR_DEFECTO) int page,

			@Parameter(description = "Elementos por pagina, acotado a 100", example = "20")
			@RequestParam(defaultValue = ApiPaging.TAMANO_POR_DEFECTO) int size) {

		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requireMember(
				actor.accountId(), orgId, actor.contextOrganizationId(), actor.platformAdmin());

		List<ConsultorioView> sedes = organizationService.consultorios(orgId, estado);
		return ResponseEntity.ok(
				ConsultorioPageResponse.of(sedes, ApiPaging.pagina(page), ApiPaging.tamano(size)));
	}
}
