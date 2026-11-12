package com.akine.organization.api;

import com.akine.organization.api.dto.MarcaResponse;
import com.akine.organization.api.dto.UpdateMarcaRequest;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.LogoContenido;
import com.akine.organization.application.MarcaCommand;
import com.akine.organization.application.MarcaService;
import com.akine.organization.domain.LogoVariante;
import com.akine.platform.spi.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Marca del tenant: colores institucionales, datos de contacto y logo.
 *
 * <p>Leer la marca —y el logo— alcanza con ser miembro vigente: la necesita la aplicacion de
 * todos los roles para pintarse. Editarla exige {@code tenant:update}, igual que los demas datos
 * de la organizacion: solo su {@code ORG_ADMIN}, y la plataforma no.
 */
@RestController
@RequestMapping(path = "/api/v1/organizations/{orgId}/marca", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Marca de la organizacion",
		description = "Colores institucionales, logo y datos de contacto del centro")
public class MarcaController {

	private final MarcaService marcaService;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public MarcaController(
			MarcaService marcaService,
			AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.marcaService = marcaService;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping
	@Operation(
			operationId = "getMarcaDeOrganizacion",
			summary = "Marca de la organizacion",
			description = "Colores por tema, datos de contacto y logos cargados. Requiere ser miembro "
					+ "vigente de la organizacion del contexto activo. Un centro que nunca edito su "
					+ "marca responde 200 con todo ausente y version 0.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Marca vigente",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = MarcaResponse.class))),
			@ApiResponse(responseCode = "403", description = "No hay sesion autenticada o falta el contexto",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, esta dada de baja, o es de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MarcaResponse> ver(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId) {
		exigirMiembro(orgId);
		return ResponseEntity.ok(MarcaResponse.from(marcaService.ver(orgId)));
	}

	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateMarcaDeOrganizacion",
			summary = "Reemplazar colores y datos de contacto",
			description = """
					Reemplazo completo: lo omitido o vacio se borra, y un color borrado vuelve al de \
					AKINE. Requiere tenant:update (solo el ORG_ADMIN de la organizacion).

					El contraste de los colores NO se valida: el centro elige libre y la pantalla \
					avisa. Solo se valida la forma (#rrggbb, email, URL con http o https).

					La version enviada se compara antes de mutar; si quedo vieja, 409 \
					concurrent-modification. Los logos no se tocan por aca.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Marca actualizada, con la version nueva",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = MarcaResponse.class))),
			@ApiResponse(responseCode = "400", description = "Algun campo no tiene la forma esperada",
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
	public ResponseEntity<MarcaResponse> actualizar(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,
			@Valid @RequestBody UpdateMarcaRequest request) {
		ApiActor actor = exigirEditor(orgId);
		MarcaCommand comando = new MarcaCommand(
				request.colorPrincipalClaro(), request.colorAcentoClaro(),
				request.colorPrincipalOscuro(), request.colorAcentoOscuro(),
				request.telefono(), request.email(), request.sitioWeb(),
				request.direccion(), request.leyenda());
		return ResponseEntity.ok(MarcaResponse.from(
				marcaService.actualizar(orgId, comando, request.version(), actor.accountId())));
	}

	@GetMapping(path = "/logo/{variante}", produces = {
			MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE, "image/webp",
			MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "descargarLogoDeOrganizacion",
			summary = "Contenido del logo de un tema",
			description = "La imagen tal como se subio. Requiere ser miembro vigente. 404 si el centro "
					+ "no cargo logo para ese tema: en oscuro, el cliente cae al logo claro.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La imagen",
					content = {
							@Content(mediaType = MediaType.IMAGE_PNG_VALUE,
									schema = @Schema(type = "string", format = "binary")),
							@Content(mediaType = MediaType.IMAGE_JPEG_VALUE,
									schema = @Schema(type = "string", format = "binary")),
							@Content(mediaType = "image/webp",
									schema = @Schema(type = "string", format = "binary"))}),
			@ApiResponse(responseCode = "403", description = "No hay sesion autenticada o falta el contexto",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No hay logo para ese tema, o la organizacion "
					+ "no existe o es de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<byte[]> logo(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,
			@Parameter(description = "Tema del logo", example = "CLARO")
			@PathVariable LogoVariante variante) {
		exigirMiembro(orgId);
		LogoContenido logo = marcaService.logo(orgId, variante);
		return ResponseEntity.ok()
				// El tipo lo decidio el servidor mirando los bytes; nosniff impide que el
				// navegador "adivine" otro.
				.header("X-Content-Type-Options", "nosniff")
				.cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
				.eTag('"' + logo.sha256() + '"')
				.contentType(MediaType.parseMediaType(logo.contentType()))
				.body(logo.contenido());
	}

	@PutMapping(path = "/logo/{variante}", consumes = {
			MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE, "image/webp"})
	@Operation(
			operationId = "subirLogoDeOrganizacion",
			summary = "Subir o reemplazar el logo de un tema",
			description = """
					El cuerpo es la imagen, sin envoltorio. PNG, JPG o WebP de hasta 500 kB. El tipo \
					lo deciden los bytes del archivo, no el Content-Type del pedido: SVG y cualquier \
					otra cosa se rechazan con 400 (errors.logo). Requiere tenant:update.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Logo guardado; la marca con su huella nueva",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = MarcaResponse.class))),
			@ApiResponse(responseCode = "400", description = "Archivo vacio, demasiado grande o de un "
					+ "formato no admitido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "El actor no es ORG_ADMIN de la organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, esta dada de baja, o es de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MarcaResponse> subirLogo(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,
			@Parameter(description = "Tema del logo", example = "CLARO")
			@PathVariable LogoVariante variante,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "La imagen",
					content = {
							@Content(mediaType = MediaType.IMAGE_PNG_VALUE,
									schema = @Schema(type = "string", format = "binary")),
							@Content(mediaType = MediaType.IMAGE_JPEG_VALUE,
									schema = @Schema(type = "string", format = "binary")),
							@Content(mediaType = "image/webp",
									schema = @Schema(type = "string", format = "binary"))})
			@RequestBody byte[] contenido) {
		ApiActor actor = exigirEditor(orgId);
		return ResponseEntity.ok(MarcaResponse.from(
				marcaService.subirLogo(orgId, variante, contenido, actor.accountId())));
	}

	@DeleteMapping("/logo/{variante}")
	@Operation(
			operationId = "quitarLogoDeOrganizacion",
			summary = "Quitar el logo de un tema",
			description = "Idempotente: quitar un logo que no esta responde igual. Requiere tenant:update.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "La marca sin ese logo",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = MarcaResponse.class))),
			@ApiResponse(responseCode = "403", description = "El actor no es ORG_ADMIN de la organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "No existe, esta dada de baja, o es de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MarcaResponse> quitarLogo(
			@Parameter(description = "Identificador de la organizacion", example = "1")
			@PathVariable long orgId,
			@Parameter(description = "Tema del logo", example = "CLARO")
			@PathVariable LogoVariante variante) {
		ApiActor actor = exigirEditor(orgId);
		return ResponseEntity.ok(MarcaResponse.from(
				marcaService.quitarLogo(orgId, variante, actor.accountId())));
	}

	private void exigirMiembro(long orgId) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requireMember(
				actor.accountId(), orgId, actor.contextOrganizationId(), actor.platformAdmin());
	}

	private ApiActor exigirEditor(long orgId) {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requireOrgDataEditor(
				actor.accountId(), orgId, actor.contextOrganizationId(), actor.platformAdmin());
		return actor;
	}
}
