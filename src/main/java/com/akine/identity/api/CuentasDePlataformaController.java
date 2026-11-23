package com.akine.identity.api;

import com.akine.identity.api.dto.CuentasDePlataformaResponse;
import com.akine.identity.application.CuentasDePlataformaService;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cuentas por estado para el panel de la consola de plataforma (19/11/2026). */
@RestController
@Tag(name = "Plataforma",
		description = "Administracion de la plataforma: acceso de soporte y roles globales")
public class CuentasDePlataformaController {

	private final CuentasDePlataformaService service;
	private final TenantContextHolder tenantContextHolder;

	public CuentasDePlataformaController(CuentasDePlataformaService service,
			TenantContextHolder tenantContextHolder) {
		this.service = service;
		this.tenantContextHolder = tenantContextHolder;
	}

	@GetMapping(path = "/api/v1/platform/resumen/cuentas", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "getCuentasDePlataforma",
			summary = "Cuentas por estado",
			description = "Cuentas vigentes activas, pendientes de activar y bloqueadas. Reservado a la "
					+ "administracion de plataforma.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Los conteos",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CuentasDePlataformaResponse.class))),
			@ApiResponse(responseCode = "403", description = "Reservado a la administracion de plataforma",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CuentasDePlataformaResponse> ver() {
		IdentityApiActor actor = IdentityApiActor.current(tenantContextHolder);
		if (!actor.platformAdmin()) {
			throw new AccessDeniedException("Operacion reservada a la administracion de plataforma");
		}
		CuentasDePlataformaService.Resumen resumen = service.resumen();
		return ResponseEntity.ok(new CuentasDePlataformaResponse(
				resumen.activas(), resumen.pendientesDeActivar(), resumen.bloqueadas()));
	}
}
