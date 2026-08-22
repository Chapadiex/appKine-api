package com.akine.platform.api;

import com.akine.platform.api.dto.VersionResponse;
import com.akine.platform.application.VersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrato tecnico minimo de versionado (AKINE-00.01).
 *
 * <p>Deliberadamente NO expone dominio funcional: los endpoints de M01-M29 se crean en
 * sus etapas correspondientes.
 *
 * <p>Capa {@code api}: solo HTTP. Sin reglas de negocio ni acceso a datos.
 */
@RestController
@RequestMapping(path = "/api/v1/version", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Platform", description = "Contrato tecnico: salud y versionado")
public class VersionController {

	private final VersionService versionService;

	public VersionController(VersionService versionService) {
		this.versionService = versionService;
	}

	@GetMapping
	@Operation(
			summary = "Version del backend y del contrato",
			description = "Devuelve la identidad tecnica del backend en ejecucion y la version "
					+ "SemVer del contrato OpenAPI que publica. El frontend la usa para verificar "
					+ "que el cliente generado corresponde a la version desplegada.")
	public ResponseEntity<VersionResponse> version() {
		return ResponseEntity.ok(VersionResponse.from(versionService.current()));
	}
}
