package com.akine.identity.api;

import com.akine.identity.api.dto.AltaDeCentroRequest;
import com.akine.identity.api.dto.AltaDeCentroResponse;
import com.akine.identity.application.AltaDeCentroPlataformaService;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.AltaDeCentro;
import com.akine.platform.spi.tenant.TenantContextHolder;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;

/** Alta de un centro desde la consola de plataforma (18/11/2026). */
@RestController
@Tag(name = "Contratacion", description = "Plan, servicios, adicionales y prueba gratis de un centro")
public class AltaDeCentroController {

	private final AltaDeCentroPlataformaService service;
	private final TenantContextHolder tenantContextHolder;

	public AltaDeCentroController(AltaDeCentroPlataformaService service, TenantContextHolder tenantContextHolder) {
		this.service = service;
		this.tenantContextHolder = tenantContextHolder;
	}

	@PostMapping(path = "/api/v1/platform/organizations",
			consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "darDeAltaCentro",
			summary = "Dar de alta un centro",
			description = """
					Crea el centro con su primera sede y su contratacion (plan ofrecido, servicios y \
					adicionales), en prueba gratis de 30 dias o activo, e invita al correo del duenio \
					como administrador de toda la organizacion: la cuenta y la membership nacen cuando \
					acepta. Con solicitudId, el pedido de la landing queda APROBADO con este centro. \
					Todo o nada. Reservado a la administracion de plataforma.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Centro creado e invitacion enviada",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AltaDeCentroResponse.class))),
			@ApiResponse(responseCode = "400", description = "Falta un dato, sin servicios o zona horaria invalida",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Reservado a la administracion de plataforma",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "El plan no existe o no se ofrece, o el pedido no existe",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "El pedido ya fue resuelto",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AltaDeCentroResponse> darDeAlta(@Valid @RequestBody AltaDeCentroRequest request) {
		IdentityApiActor actor = IdentityApiActor.current(tenantContextHolder);
		if (!actor.platformAdmin()) {
			throw new AccessDeniedException("Operacion reservada a la administracion de plataforma");
		}
		AltaDeCentroPlataformaService.Resultado resultado = service.darDeAlta(new AltaDeCentro.Comando(
				request.nombreCentro(), request.nombrePrimeraSede(), request.zonaHoraria(), request.planCode(),
				EnumSet.copyOf(request.servicios()),
				request.adicionales().isEmpty() ? EnumSet.noneOf(Adicional.class) : EnumSet.copyOf(request.adicionales()),
				request.prueba(), request.solicitudId(), actor.accountId()),
				request.emailDuenio());
		return ResponseEntity.status(HttpStatus.CREATED).body(new AltaDeCentroResponse(
				resultado.organizationId(), resultado.consultorioId(), resultado.invitacionId(),
				resultado.emailDuenio()));
	}
}
