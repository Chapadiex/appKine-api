package com.akine.clinical.api;

import com.akine.clinical.api.dto.DatosAdministrativosDeHistoriaResponse;
import com.akine.clinical.application.HistoriaClinicaAdministrativaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La lectura limitada de la Historia Clinica para quien no tiene {@code hc:read} (matriz §32,
 * "Ver Historia Clinica" del {@code ADMINISTRATIVO}: "Limitado"). Un controller aparte del de la
 * historia para que nada de lo clinico —justificacion, auditoria de acceso, contenido— se le
 * mezcle por compartir clase.
 */
@RestController
@RequestMapping(path = "/api/v1/historias-clinicas/por-persona/{personaId}")
@Tag(name = "Historia clinica",
		description = "Apertura, resumen y antecedentes de la Historia Clinica de una persona (M09)")
public class HistoriaClinicaAdministrativaController {

	private final HistoriaClinicaAdministrativaService servicio;
	private final ClinicalApiActor apiActor;

	public HistoriaClinicaAdministrativaController(
			HistoriaClinicaAdministrativaService servicio, ClinicalApiActor apiActor) {
		this.servicio = servicio;
		this.apiActor = apiActor;
	}

	@GetMapping(path = "/datos-administrativos", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "verDatosAdministrativosDeHistoriaClinica",
			summary = "Ver los datos administrativos de la Historia Clinica de una persona",
			description = """
					Matriz de permisos §32, "Ver Historia Clinica" del ADMINISTRATIVO: Limitado, \
					que la matriz define como solo metadatos administrativos y NUNCA contenido \
					clinico. Devuelve si la persona tiene historia, cuando se abrio, sus casos \
					(numero, estado, oferta, fechas) y el equipo tratante vigente de cada uno. \
					No trae resumen, antecedentes, diagnostico, objetivo ni motivo de cierre.

					Exige paciente:read (lo tiene el personal, no el rol PACIENTE). No pide \
					justificacion de acceso y no se audita como acceso clinico: no expone \
					contenido clinico. Una persona propia sin historia responde 200 con \
					tieneHistoriaClinica = false; una persona de otra organizacion, o sin perfil \
					de paciente vigente, 404.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Los datos administrativos",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(
									implementation = DatosAdministrativosDeHistoriaResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto o sin paciente:read",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La persona es de otra organizacion o no es paciente",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public DatosAdministrativosDeHistoriaResponse verDatosAdministrativos(
			@Parameter(description = "Persona titular de la historia", example = "12")
			@PathVariable long personaId) {

		return DatosAdministrativosDeHistoriaResponse.from(
				servicio.datosAdministrativos(apiActor.current(), personaId));
	}
}
