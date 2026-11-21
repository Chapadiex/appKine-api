package com.akine.identity.api;

import com.akine.identity.api.dto.AceptarTerminosRequest;
import com.akine.identity.api.dto.MisTerminosResponse;
import com.akine.identity.application.TerminosService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Terminos de la cuenta autenticada: si tiene la version vigente pendiente, y aceptarla
 * (19/11/2026, docs/diseno/AKINE-terminos-obligatorios.md). Son de las pocas rutas que una cuenta
 * con terminos pendientes puede usar: el interceptor de terminos las deja pasar.
 */
@RestController
@RequestMapping(path = "/api/v1/me/terminos",
		produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@Tag(name = "Mi cuenta")
public class MisTerminosController {

	private final TerminosService terminosService;

	public MisTerminosController(TerminosService terminosService) {
		this.terminosService = terminosService;
	}

	@GetMapping
	@Operation(
			operationId = "getMisTerminos",
			summary = "Terminos vigentes para la cuenta autenticada",
			description = """
					Version vigente, enlace al texto y si la cuenta la tiene pendiente. Responde \
					aunque haya terminos pendientes: es lo que la pantalla de aceptacion lee.""")
	@ApiResponse(responseCode = "200", description = "Estado de los terminos de la cuenta",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = MisTerminosResponse.class)))
	public ResponseEntity<MisTerminosResponse> ver() {
		long cuentaId = IdentityApiActor.principal().accountId();
		return ResponseEntity.ok(MisTerminosResponse.de(terminosService.estado(cuentaId)));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "aceptarMisTerminos",
			summary = "Aceptar la version vigente de los terminos",
			description = """
					Deja constancia de que la cuenta acepto la version vigente y destraba la API. \
					Idempotente: aceptar una version ya aceptada responde igual.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Aceptada"),
			@ApiResponse(responseCode = "400",
					description = "La version no es la vigente (errors.versionTerminos): hay que recargar",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> aceptar(@Valid @RequestBody AceptarTerminosRequest request) {
		long cuentaId = IdentityApiActor.principal().accountId();
		terminosService.aceptar(cuentaId, request.versionTerminos(), Instant.now());
		return ResponseEntity.noContent().build();
	}
}
