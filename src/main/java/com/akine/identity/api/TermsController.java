package com.akine.identity.api;

import com.akine.identity.api.dto.TermsResponse;
import com.akine.identity.application.TerminosService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Terminos y condiciones vigentes (DP-45).
 *
 * <p>Publico: lo lee la pantalla de registro, antes de que exista ninguna cuenta. Fuera de
 * {@code /api/v1/auth} a proposito: ese prefijo es el {@code Path} de la cookie de refresh, y
 * una lectura publica no tiene por que recibirla.
 */
@RestController
@RequestMapping(path = "/api/v1/terms", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Registro y activacion", description = "Alta self-service y habilitacion de la cuenta")
public class TermsController {

	private final TerminosService terminosService;

	public TermsController(TerminosService terminosService) {
		this.terminosService = terminosService;
	}

	@GetMapping
	@Operation(
			operationId = "getCurrentTerms",
			summary = "Version vigente de los terminos y condiciones",
			description = """
					Publico. Devuelve la version de los terminos que el alta self-service y la \
					aceptacion de invitacion con cuenta nueva exigen aceptar, y el enlace donde \
					se publica el texto. El texto legal no lo sirve el backend.

					Con aceptacionObligatoria=false (ninguna version configurada) el alta no \
					exige nada y versionVigente viene null.""")
	@ApiResponse(
			responseCode = "200",
			description = "Terminos vigentes",
			content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = TermsResponse.class)))
	public ResponseEntity<TermsResponse> current() {
		return ResponseEntity.ok(TermsResponse.de(terminosService.vigentes()));
	}
}
