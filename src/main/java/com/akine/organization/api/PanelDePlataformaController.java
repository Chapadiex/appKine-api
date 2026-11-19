package com.akine.organization.api;

import com.akine.organization.api.dto.PanelDePlataformaResponse;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.PanelDePlataformaService;
import com.akine.platform.spi.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Panel de control de la consola de plataforma (19/11/2026). */
@RestController
@Tag(name = "Plataforma",
		description = "Administracion de la plataforma: acceso de soporte y roles globales")
public class PanelDePlataformaController {

	private final PanelDePlataformaService service;
	private final AuthorizationGuard authorizationGuard;
	private final TenantContextHolder tenantContextHolder;

	public PanelDePlataformaController(PanelDePlataformaService service, AuthorizationGuard authorizationGuard,
			TenantContextHolder tenantContextHolder) {
		this.service = service;
		this.authorizationGuard = authorizationGuard;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping(path = "/api/v1/platform/resumen", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "getPanelDePlataforma",
			summary = "Estado general de la plataforma",
			description = """
					Centros por estado de su suscripcion (activos, en prueba, por vencer en 7 dias, \
					vencidos, suspendidos y cancelados), pedidos de prueba pendientes, las pruebas que \
					terminan en los proximos 30 dias y los ultimos centros, pedidos y cambios de \
					suscripcion. Todo leido de la base, sin cache. Reservado a la administracion de \
					plataforma.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El panel",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PanelDePlataformaResponse.class))),
			@ApiResponse(responseCode = "403", description = "Reservado a la administracion de plataforma",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PanelDePlataformaResponse> ver() {
		ApiActor actor = ApiActor.current(tenantContextHolder);
		authorizationGuard.requirePlatformAdmin(actor.platformAdmin());
		return ResponseEntity.ok(PanelDePlataformaResponse.from(service.leer()));
	}
}
