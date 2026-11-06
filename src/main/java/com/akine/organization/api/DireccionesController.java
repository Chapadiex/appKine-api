package com.akine.organization.api;

import com.akine.organization.api.dto.BusquedaDeDireccionesResponse;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.DireccionesService;
import com.akine.platform.spi.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * Busqueda de direcciones para ubicar una sede (M03, V140).
 *
 * <p>Vive bajo la organizacion y exige ser miembro, como el listado de sedes: no es una
 * operacion sobre una sede en particular —se usa mientras se la esta dando de alta— y no revela
 * nada del tenant. Lo que si protege es el cupo del proveedor: cada pedido es una consulta a un
 * servicio externo con limite de uso, y por eso no esta abierto sin sesion.
 *
 * <p><b>No es autocompletado.</b> La pantalla la llama cuando la persona termina de escribir y
 * pide buscar; llamarla tecla a tecla viola la politica de uso de Nominatim y agota el limite de
 * una consulta por segundo para todo el despliegue.
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/direcciones")
@Tag(name = "Consultorios", description = "Alta, edicion y baja de las sedes de una organizacion")
public class DireccionesController {

	private static final int LARGO_MAXIMO = 200;

	private static final Pattern PAIS = Pattern.compile("[A-Za-z]{2}");

	private final DireccionesService direccionesService;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public DireccionesController(
			DireccionesService direccionesService,
			AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.direccionesService = direccionesService;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping(
			path = "/busqueda",
			produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "buscarDirecciones",
			summary = "Busca una direccion para ubicar la sede",
			description = """
					Devuelve hasta cinco direcciones candidatas con sus coordenadas para el texto \
					escrito, acotadas opcionalmente a un pais. Sirve para completar la direccion \
					estructurada y la ubicacion de una sede al darla de alta o editarla.

					Requiere ser miembro de la organizacion. La respuesta trae el proveedor y el texto \
					de atribucion que la pantalla tiene que mostrar junto a los resultados: la licencia \
					del proveedor lo exige.

					NO es autocompletado: se llama cuando la persona pide buscar, no tecla a tecla. El \
					proveedor admite una consulta por segundo para todo el despliegue, y el backend \
					serializa los pedidos para respetarlo. Si el proveedor no responde o esta apagado en \
					este despliegue, 503 geocodificador-no-disponible con Retry-After: la sede se puede \
					guardar igual con la direccion escrita y sin coordenadas, o fijando la ubicacion a \
					mano.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Los candidatos, los mejores primero. Lista vacia si no hay ninguno",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = BusquedaDeDireccionesResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Texto vacio o de mas de 200 caracteres, o pais que no es un codigo "
							+ "ISO de dos letras",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No es miembro de la organizacion, o no hay contexto de trabajo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "503",
					description = "El geocodificador no respondio o esta apagado "
							+ "(geocodificador-no-disponible). Retry-After dice en cuantos segundos reintentar",
					headers = @Header(
							name = "Retry-After",
							description = "Segundos sugeridos para reintentar",
							schema = @Schema(type = "integer", example = "30")),
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<BusquedaDeDireccionesResponse> buscar(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,

			@Parameter(description = "Direccion escrita, tal como la tipeo la persona. Hasta 200 caracteres",
					example = "Av. Colon 1234, Cordoba", required = true)
			@RequestParam String q,

			@Parameter(description = "Pais al que acotar la busqueda, ISO 3166-1 alpha-2", example = "AR")
			@RequestParam(required = false) String pais) {

		// A mano y no con @Size/@Pattern sobre el @RequestParam: eso exige @Validated en la clase, que
		// cambia como se reportan los errores en todo el controller (mismo criterio que
		// AdjuntoClinicoController). Los tres son 400 validation-error con su mensaje.
		if (q == null || q.isBlank()) {
			throw new IllegalArgumentException("El texto de la direccion a buscar es obligatorio");
		}
		if (q.length() > LARGO_MAXIMO) {
			throw new IllegalArgumentException(
					"El texto de la direccion a buscar no puede superar los " + LARGO_MAXIMO + " caracteres");
		}
		if (pais != null && !pais.isBlank() && !PAIS.matcher(pais.strip()).matches()) {
			throw new IllegalArgumentException("El pais tiene que ser un codigo ISO de dos letras");
		}

		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requireMember(
				actor.accountId(), orgId, actor.contextOrganizationId(), actor.platformAdmin());

		return ResponseEntity.ok(BusquedaDeDireccionesResponse.from(direccionesService.buscar(q, pais)));
	}
}
