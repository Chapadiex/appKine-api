package com.akine.identity.api;

import com.akine.identity.api.dto.MyProfileResponse;
import com.akine.identity.application.MyProfileService;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Perfil propio (DP-45 punto 3). Vive en identity porque la cuenta es de identity. */
@RestController
@RequestMapping(path = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Mi cuenta")
public class MyProfileController {

	private final MyProfileService service;

	public MyProfileController(MyProfileService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(
			operationId = "getMe",
			summary = "Perfil de la cuenta autenticada",
			description = """
					Devuelve nombre, apellido y correo de la cuenta que hace el request y, si el \
					token esta acotado a un contexto de trabajo, la organizacion, la sede y el rol \
					de la membership en ese contexto. Sin contexto activo (token pre_context, o \
					administrador de plataforma sin contexto) el campo context es null y la \
					respuesta sigue siendo 200.

					No requiere contexto ni permiso: la cuenta se lee a si misma y el \
					identificador sale del principal, no hay parametro que lo cambie. No expone \
					credenciales ni estado interno.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Perfil propio",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = MyProfileResponse.class))),
			@ApiResponse(responseCode = "401", description = "Sin sesion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MyProfileResponse> me() {
		AuthenticatedPrincipal actor = IdentityApiActor.principal();
		MyProfileService.Perfil p = service.perfil(actor.accountId(), actor.organizationId(), actor.consultorioId())
				.orElseThrow(() -> new AccessDeniedException("La cuenta ya no existe"));
		MyProfileService.ContextoActivo c = p.contexto();
		return ResponseEntity.ok(new MyProfileResponse(
				p.accountId(), p.email(), p.nombre(), p.apellido(),
				c == null ? null : new MyProfileResponse.ActiveWorkContext(
						c.organizationId(), c.organizationName(), c.consultorioId(),
						c.consultorioName(), c.roleCode(), c.roleLabel())));
	}
}
