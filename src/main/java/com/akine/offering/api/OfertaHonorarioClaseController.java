package com.akine.offering.api;

import com.akine.offering.api.dto.DeactivateOfferingRequest;
import com.akine.offering.api.dto.HonorarioPorClaseDtos.CambiarFinHonorarioPorClaseRequest;
import com.akine.offering.api.dto.HonorarioPorClaseDtos.CreateHonorarioPorClaseRequest;
import com.akine.offering.api.dto.HonorarioPorClaseDtos.HonorarioPorClaseResponse;
import com.akine.offering.application.OfertaHonorarioClaseService;
import com.akine.offering.application.OfertaHonorarioClaseView;
import com.akine.offering.application.OperatingActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Honorario por clase de una oferta, por vigencia (AKINE-08.09b, DP-38, RF-M22-006).
 *
 * <p>La tarifa es por participante presente; el reparto entre instructores y la liquidacion los
 * hace {@code activity}. Lectura y escritura exigen {@code consultorio:manage}.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/ofertas/{ofertaId}/honorarios-por-clase",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Servicios y ofertas",
		description = "Catalogo global de servicios y como cada sede los presta (M27)")
public class OfertaHonorarioClaseController {

	private final OfertaHonorarioClaseService honorarioService;
	private final OfferingApiActor apiActor;

	public OfertaHonorarioClaseController(
			OfertaHonorarioClaseService honorarioService, OfferingApiActor apiActor) {
		this.honorarioService = honorarioService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listHonorariosPorClaseDeOferta",
			summary = "Listar los honorarios por clase de la oferta",
			description = """
					DP-38 (RF-M22-006). La grilla de vigencias, ACTIVOS E HISTORICOS, del mas nuevo \
					al mas viejo. vigente dice cual rige hoy. Exige consultorio:manage: es lo que \
					cobra un colega.

					La tarifa que aplica a una clase es la vigente el dia local de la clase en la \
					sede. Una clase sin tarifa ese dia no entra en ninguna liquidacion.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Honorarios de la oferta",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = HonorarioPorClaseResponse.class)))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo, o sin consultorio:manage en la sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La oferta o la sede no existen, o son de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<HonorarioPorClaseResponse>> list(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta", example = "34")
			@PathVariable long ofertaId) {

		OperatingActor actor = apiActor.current();
		return ResponseEntity.ok(honorarioService
				.listar(actor, exigirContexto(actor), consultorioId, ofertaId).stream()
				.map(HonorarioPorClaseResponse::de)
				.toList());
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createHonorarioPorClaseDeOferta",
			summary = "Fijar el honorario por participante presente de la oferta para un periodo",
			description = """
					DP-38 (RF-M22-006). Exige consultorio:manage.

					DOS HONORARIOS ACTIVOS DE LA MISMA OFERTA NO PUEDEN PISARSE: 409 \
					honorario-de-clase-solapado, con el id y el periodo del que choca. Para cambiar \
					la tarifa se cierra la vigencia de la actual (PUT) y se carga la nueva.

					El importe no se edita despues. Lo ya liquidado no cambia nunca: la liquidacion \
					copio su importe (CA-M22-007-06). Sin moneda, hereda la del precio de lista.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Honorario creado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = HonorarioPorClaseResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Importe no positivo, vigencia invertida o moneda ausente o invalida",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo, o sin consultorio:manage en la sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La oferta o la sede no existen, o son de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "Periodo solapado (honorario-de-clase-solapado), oferta dada de baja "
							+ "(oferta-inactiva) o sede no operable (consultorio-no-operable)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<HonorarioPorClaseResponse> create(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta", example = "34")
			@PathVariable long ofertaId,

			@Valid @RequestBody CreateHonorarioPorClaseRequest request) {

		OperatingActor actor = apiActor.current();
		OfertaHonorarioClaseView creado = honorarioService.crear(
				actor, exigirContexto(actor), consultorioId, ofertaId,
				request.importePorParticipante(), request.moneda(),
				request.vigenciaDesde(), request.vigenciaHasta());

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/ofertas/" + ofertaId
						+ "/honorarios-por-clase/" + creado.id()))
				.body(HonorarioPorClaseResponse.de(creado));
	}

	@PutMapping(path = "/{honorarioId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "cambiarFinHonorarioPorClaseDeOferta",
			summary = "Cerrar o reabrir la vigencia de un honorario por clase",
			description = """
					DP-38. Exige consultorio:manage. Es lo UNICO editable de un honorario: el fin \
					de su vigencia. Extenderlo puede producir 409 honorario-de-clase-solapado.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Honorario actualizado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = HonorarioPorClaseResponse.class))),
			@ApiResponse(responseCode = "400", description = "Vigencia invertida o falta la version",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo, o sin consultorio:manage en la sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La oferta, la sede o el honorario no existen, o son de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "Periodo solapado (honorario-de-clase-solapado), honorario dado de "
							+ "baja (honorario-de-clase-inactivo) o version desactualizada "
							+ "(concurrent-modification)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<HonorarioPorClaseResponse> cambiarFin(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta", example = "34")
			@PathVariable long ofertaId,

			@Parameter(description = "Honorario por clase", example = "12")
			@PathVariable long honorarioId,

			@Valid @RequestBody CambiarFinHonorarioPorClaseRequest request) {

		OperatingActor actor = apiActor.current();
		return ResponseEntity.ok(HonorarioPorClaseResponse.de(honorarioService.cambiarFin(
				actor, exigirContexto(actor), consultorioId, ofertaId, honorarioId,
				request.vigenciaHasta(), request.expectedVersion())));
	}

	// PRODUCES EXPLICITO por el 406 del Accept problem+json (ver ConvenioController.deactivate).
	@DeleteMapping(path = "/{honorarioId}",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "deactivateHonorarioPorClaseDeOferta",
			summary = "Dar de baja un honorario por clase",
			description = """
					Baja LOGICA con motivo obligatorio. Exige consultorio:manage. Libera el \
					periodo; lo ya liquidado con esa tarifa no se toca.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Honorario dado de baja"),
			@ApiResponse(responseCode = "400", description = "Falta el motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo, o sin consultorio:manage en la sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La oferta, la sede o el honorario no existen, o son de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El honorario ya estaba dado de baja (honorario-de-clase-inactivo)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivate(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta", example = "34")
			@PathVariable long ofertaId,

			@Parameter(description = "Honorario por clase", example = "12")
			@PathVariable long honorarioId,

			@Valid @RequestBody DeactivateOfferingRequest request) {

		OperatingActor actor = apiActor.current();
		honorarioService.darDeBaja(
				actor, exigirContexto(actor), consultorioId, ofertaId, honorarioId, request.reason());
		return ResponseEntity.noContent().build();
	}

	private static long exigirContexto(OperatingActor actor) {
		Long organizationId = actor.contextOrganizationId();
		if (organizationId == null) {
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo elegido");
		}
		return organizationId;
	}
}
