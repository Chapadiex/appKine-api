package com.akine.encounter.api;

import com.akine.encounter.api.dto.FijarPoliticaDeEnmiendaRequest;
import com.akine.encounter.api.dto.PoliticaDeEnmiendaResponse;
import com.akine.encounter.application.PoliticaDeEnmiendaService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La ventana para enmendar una sesion cerrada, de la organizacion del contexto (U-25, DP-43).
 *
 * <p>Sin {@code orgId} en la ruta, como el padron de personas: es la organizacion del contexto, y
 * una ruta con otra organizacion seria una forma mas de pedir lo ajeno.
 */
@RestController
@RequestMapping(
		path = "/api/v1/politica-de-enmienda",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Sesiones", description = "Atencion real: inicio idempotente y guardado de borrador (M14)")
public class PoliticaDeEnmiendaController {

	private final PoliticaDeEnmiendaService politicas;
	private final EncounterApiActor apiActor;

	public PoliticaDeEnmiendaController(PoliticaDeEnmiendaService politicas, EncounterApiActor apiActor) {
		this.politicas = politicas;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "verPoliticaDeEnmienda",
			summary = "Ver la ventana para enmendar sesiones de la organizacion",
			description = """
					Cuantos dias desde el cierre se puede enmendar una sesion (DP-43). Sin politica \
					propia rigen **30 dias** y la respuesta trae `porDefecto = true` y `version` \
					nula. Exige administrar la organizacion (`consultorio:manage` con alcance \
					organizacion: el `ORG_ADMIN`). La fecha limite de cada sesion viaja en la sesion \
					misma (`enmendableHasta`).""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Politica vigente"),
			@ApiResponse(
					responseCode = "403",
					description = "No administra la organizacion, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PoliticaDeEnmiendaResponse> ver() {
		return ResponseEntity.ok(PoliticaDeEnmiendaResponse.de(politicas.ver(apiActor.current())));
	}

	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "fijarPoliticaDeEnmienda",
			summary = "Fijar la ventana para enmendar sesiones de la organizacion",
			description = """
					Fija los dias desde el cierre en que una sesion se puede enmendar, de 1 a 3650 \
					(DP-43). **Rige tambien para las sesiones ya cerradas**: la ventana no se \
					congela en cada sesion, asi que achicarla puede dejar fuera de ventana una \
					sesion que hoy se podia enmendar, y agrandarla la vuelve a habilitar.

					Hay que mandar la `version` leida, **nula** si se leyo la politica por defecto. \
					Si no coincide, 409 `concurrent-modification`. Deja \
					`POLITICA_DE_ENMIENDA_FIJADA` en la auditoria.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Politica fijada, con la version nueva"),
			@ApiResponse(
					responseCode = "400",
					description = "Ventana fuera de 1..3650, o falta",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No administra la organizacion, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La version quedo vieja (`concurrent-modification`)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PoliticaDeEnmiendaResponse> fijar(
			@RequestBody @Valid FijarPoliticaDeEnmiendaRequest request) {

		return ResponseEntity.ok(PoliticaDeEnmiendaResponse.de(politicas.fijar(
				apiActor.current(), request.ventanaDias(), request.version())));
	}
}
