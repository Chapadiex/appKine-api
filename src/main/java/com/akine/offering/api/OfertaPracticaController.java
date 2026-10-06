package com.akine.offering.api;

import com.akine.offering.api.dto.PracticasDeOfertaResponse;
import com.akine.offering.api.dto.ReemplazarPracticasRequest;
import com.akine.offering.application.OfertaPracticaService;
import com.akine.offering.application.OperatingActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Que practicas del catalogo clinico puede prestar una Oferta, y cual es su principal (A-9, DP-11,
 * RF-M06-008).
 *
 * <p>Autorizacion igual que las habilitaciones: leer exige pertenencia a la organizacion;
 * reemplazar, {@code consultorio:manage} sobre la sede. 404 para todo lo ajeno, 403 sin contexto o
 * sin permiso, nunca 401.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/ofertas/{ofertaId}/practicas",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Servicios y ofertas",
		description = "Catalogo global de servicios y como cada sede los presta (M27)")
public class OfertaPracticaController {

	private static final Logger log = LoggerFactory.getLogger(OfertaPracticaController.class);

	private final OfertaPracticaService practicaService;
	private final OfferingApiActor apiActor;

	public OfertaPracticaController(OfertaPracticaService practicaService, OfferingApiActor apiActor) {
		this.practicaService = practicaService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "getPracticasDeOferta",
			summary = "Ver que practicas puede prestar la oferta y cual es la principal",
			description = """
					Devuelve las practicas de la oferta, ACTIVAS E INACTIVAS, con la principal \
					vigente en practicaPrincipalId.

					Declaran lo que la oferta PUEDE prestar, no lo que se presto en una sesion: \
					eso lo dicen los tratamientos realizados. La principal es la que se devenga o \
					consume cuando una sesion cierra sin tratamientos.

					Una lista vacia significa que la oferta no declara practicas (por ejemplo una \
					actividad como Pilates). NO significa todas, al reves que las habilitaciones.

					vigenteEnCatalogo en false es una practica dada de baja en el catalogo que la \
					oferta conserva: la fila sigue valiendo y se muestra.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Practicas de la oferta",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PracticasDeOfertaResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, o sin pertenencia a la organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La oferta o la sede no existen, o son de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PracticasDeOfertaResponse> get(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta configurada", example = "34")
			@PathVariable long ofertaId) {

		OperatingActor actor = apiActor.current();
		long organizationId = exigirContexto(actor);

		return ResponseEntity.ok(PracticasDeOfertaResponse.de(
				practicaService.leer(actor, organizationId, consultorioId, ofertaId)));
	}

	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "reemplazarPracticasDeOferta",
			summary = "Fijar que practicas puede prestar la oferta y cual es la principal",
			description = """
					REEMPLAZA EL CONJUNTO COMPLETO. Lo que entra y no estaba se crea, lo que \
					estaba y no entra se da de baja con motivo automatico, y lo que sigue no se \
					toca. Si cambia la principal, la anterior pierde la marca.

					Con al menos una practica, practicaPrincipalId es obligatoria y tiene que \
					estar en la lista; con la lista vacia tiene que ser null. Si no, 400.

					Las practicas que ENTRAN tienen que existir, ser visibles para el centro \
					(propias o del catalogo de plataforma) y poder elegirse hoy: una ajena o \
					inexistente responde 404, una dada de baja o fuera de vigencia 409 \
					practica-no-utilizable. Las que ya estaban no se revalidan.

					expectedVersion es la de la OFERTA, la misma que usan los reemplazos de \
					habilitaciones.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Practicas resultantes",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PracticasDeOfertaResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta la lista o expectedVersion, o la principal no es coherente "
							+ "con la lista",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, o sin consultorio:manage en la sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La oferta, la sede o alguna practica no existen o no son "
							+ "visibles para este centro",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Version desactualizada (conflict), practica que no se puede "
							+ "elegir (practica-no-utilizable), oferta dada de baja "
							+ "(oferta-inactiva), o sede no operable (consultorio-no-operable)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PracticasDeOfertaResponse> reemplazar(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta configurada", example = "34")
			@PathVariable long ofertaId,

			@Valid @RequestBody ReemplazarPracticasRequest request) {

		OperatingActor actor = apiActor.current();
		long organizationId = exigirContexto(actor);

		log.info("Reemplazo de practicas de oferta: consultorioId={} ofertaId={} cantidad={}",
				consultorioId, ofertaId, request.practicaIds().size());

		return ResponseEntity.ok(PracticasDeOfertaResponse.de(
				practicaService.reemplazar(
						actor, organizationId, consultorioId, ofertaId,
						request.practicaIds(), request.practicaPrincipalId(),
						request.expectedVersion())));
	}

	/** El tenant del contexto validado, o 403 (nunca 401: ver {@code HabilitacionController}). */
	private static long exigirContexto(OperatingActor actor) {
		Long organizationId = actor.contextOrganizationId();
		if (organizationId == null) {
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo elegido");
		}
		return organizationId;
	}
}
