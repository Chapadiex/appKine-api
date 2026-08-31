package com.akine.encounter.api;

import com.akine.encounter.api.dto.GuardarBorradorRequest;
import com.akine.encounter.api.dto.SesionResponse;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionView;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Inicio de la atencion y autosave del borrador (M14).
 *
 * <p>Una Sesion <b>no es un Turno</b>: DP-05 les da maquinas de estado independientes, y ninguna
 * transicion administrativa prueba por si sola que la prestacion ocurrio.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/sesiones",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Sesiones", description = "Atencion real: inicio idempotente y guardado de borrador (M14)")
public class SesionController {

	private final SesionService sesionService;
	private final EncounterApiActor apiActor;

	public SesionController(SesionService sesionService, EncounterApiActor apiActor) {
		this.sesionService = sesionService;
		this.apiActor = apiActor;
	}

	@PostMapping("/turnos/{turnoId}")
	@Operation(
			summary = "Iniciar la atencion de un turno",
			description = """
					Abre la atencion, o **devuelve la que ya estaba abierta**.

					El doble inicio es idempotente a proposito: un profesional que aprieta dos \
					veces o recarga la pantalla es el caso normal, y un 409 le exigiria a la \
					pantalla distinguir dos situaciones que para el usuario son la misma \
					(RN-M14-001, un turno produce como mucho una sesion).

					La Historia Clinica del paciente **se crea si no existia**. Exige perfil de \
					paciente vigente: una atencion sobre alguien que solo es "persona" falla, y \
                    no se crea historia clinica a nombre de quien no es paciente.

                    Quien atiende es el profesional del turno. Si el turno tiene uno asignado y \
					no es quien inicia, es 409: dejar que otro abra la sesion de un turno ajeno \
					rompe la propiedad antes de que la sesion exista.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Atencion iniciada"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `sesion:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El turno no habilita una atencion, o la persona no es paciente",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SesionResponse> iniciar(
			@PathVariable long consultorioId,
			@PathVariable long turnoId) {

		SesionView vista = sesionService.iniciar(apiActor.current(), consultorioId, turnoId);
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/sesiones/" + vista.id()))
				.body(SesionResponse.de(vista));
	}

	@GetMapping("/{sesionId}")
	@Operation(
			summary = "Ver una sesion",
			description = "Devuelve la sesion con su borrador y su **version**, que es la que hay "
					+ "que devolver al guardar.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Sesion"),
			@ApiResponse(
					responseCode = "404",
					description = "La sesion o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SesionResponse> ver(
			@PathVariable long consultorioId,
			@PathVariable long sesionId) {

		return ResponseEntity.ok(SesionResponse.de(
				sesionService.ver(apiActor.current(), consultorioId, sesionId)));
	}

	@PutMapping("/{sesionId}/borrador")
	@Operation(
			summary = "Guardar el borrador de la atencion",
			description = """
					Guarda el contenido parcial de la atencion. Pensado para llamarse seguido, \
					desde el autosave de la pantalla.

					**Hay que mandar la `version` que se leyo.** Dos pestanas del mismo \
					profesional sobre la misma sesion son el caso normal; sin la version la \
					segunda pisa a la primera en silencio y el profesional pierde lo que \
					escribio. Un 409 `concurrent-modification` significa recargar.

					**Solo el profesional de la sesion puede guardar.** No es cuestion de \
					permiso —dos profesionales de la misma sede tienen el mismo \
					`sesion:register`— sino de propiedad de esa atencion, y por eso el rechazo \
					es 409 y no 403.

					El contenido es **opaco**: que campos tiene una evaluacion lo define \
					AKINE-06.02. Esta etapa garantiza que se guarda, se versiona y no se pierde.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Borrador guardado"),
			@ApiResponse(
					responseCode = "404",
					description = "La sesion o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La sesion la atiende otro profesional, o la version quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SesionResponse> guardarBorrador(
			@PathVariable long consultorioId,
			@PathVariable long sesionId,
			@RequestBody @Valid GuardarBorradorRequest request) {

		return ResponseEntity.ok(SesionResponse.de(sesionService.guardarBorrador(
				apiActor.current(), consultorioId, sesionId,
				request.contenido(), request.version())));
	}
}
