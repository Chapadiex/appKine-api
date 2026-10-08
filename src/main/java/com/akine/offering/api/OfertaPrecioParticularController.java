package com.akine.offering.api;

import com.akine.offering.api.dto.CambiarFinPrecioParticularRequest;
import com.akine.offering.api.dto.CreatePrecioParticularRequest;
import com.akine.offering.api.dto.DeactivateOfferingRequest;
import com.akine.offering.api.dto.PrecioParticularResponse;
import com.akine.offering.application.OfertaPrecioParticularService;
import com.akine.offering.application.OfertaPrecioParticularView;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Precio particular de una oferta por vigencia (B-3, RF-M16-009, RN-M16-007).
 *
 * <p>El precio de lista de la oferta ({@code precioBase}) sigue sin vigencia. Estos precios lo
 * reemplazan en el periodo que cubren, y el devengo copia el que rige el dia del cierre: cambiar un
 * precio nunca toca lo ya devengado (CA-M16-009-06).
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/ofertas/{ofertaId}/precios-particulares",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Servicios y ofertas",
		description = "Catalogo global de servicios y como cada sede los presta (M27)")
public class OfertaPrecioParticularController {

	private static final Logger log = LoggerFactory.getLogger(OfertaPrecioParticularController.class);

	private final OfertaPrecioParticularService precioService;
	private final OfferingApiActor apiActor;

	public OfertaPrecioParticularController(
			OfertaPrecioParticularService precioService, OfferingApiActor apiActor) {
		this.precioService = precioService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listPreciosParticularesDeOferta",
			summary = "Listar los precios particulares por vigencia de la oferta",
			description = """
					RF-M16-009. La grilla de vigencias, ACTIVOS E HISTORICOS, del mas nuevo al mas \
					viejo. vigente dice cual rige hoy.

					El dia que ningun precio particular lo cubre, la oferta cobra su precio de \
					lista (precioBase). Una lista vacia es el caso normal de una oferta que nunca \
					tuvo precios por vigencia.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Precios particulares de la oferta",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = PrecioParticularResponse.class)))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo, o sin pertenencia a la organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La oferta o la sede no existen, o son de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<PrecioParticularResponse>> list(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta", example = "34")
			@PathVariable long ofertaId) {

		OperatingActor actor = apiActor.current();
		return ResponseEntity.ok(precioService
				.listar(actor, exigirContexto(actor), consultorioId, ofertaId).stream()
				.map(PrecioParticularResponse::de)
				.toList());
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createPrecioParticularDeOferta",
			summary = "Fijar un precio particular de la oferta para un periodo",
			description = """
					RF-M16-009. Exige consultorio:manage sobre la sede de la ruta.

					DOS PRECIOS ACTIVOS DE LA MISMA OFERTA NO PUEDEN PISARSE: 409 \
					precio-particular-solapado, con el id y el periodo del que choca. Para subir \
					un precio se cierra la vigencia del actual (PUT) y se carga el nuevo.

					El importe no se edita despues: corregir una carga es darla de baja y cargarla \
					de nuevo. Lo ya devengado no cambia nunca, porque la obligacion copio su \
					importe.

					Sin moneda, hereda la del precio de lista de la oferta.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Precio creado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PrecioParticularResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Importe negativo, vigencia invertida o moneda ausente o invalida",
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
					description = "Periodo solapado (precio-particular-solapado), oferta dada de baja "
							+ "(oferta-inactiva) o sede no operable (consultorio-no-operable)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PrecioParticularResponse> create(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta", example = "34")
			@PathVariable long ofertaId,

			@Valid @RequestBody CreatePrecioParticularRequest request) {

		OperatingActor actor = apiActor.current();
		log.info("Alta de precio particular solicitada: consultorioId={} ofertaId={}",
				consultorioId, ofertaId);

		OfertaPrecioParticularView creado = precioService.crear(
				actor, exigirContexto(actor), consultorioId, ofertaId, request.importe(),
				request.moneda(), request.vigenciaDesde(), request.vigenciaHasta());

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/ofertas/" + ofertaId
						+ "/precios-particulares/" + creado.id()))
				.body(PrecioParticularResponse.de(creado));
	}

	@PutMapping(path = "/{precioId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "cambiarFinPrecioParticularDeOferta",
			summary = "Cerrar o reabrir la vigencia de un precio particular",
			description = """
					RF-M16-009. Exige consultorio:manage. Es lo UNICO editable de un precio: el fin \
					de su vigencia. Extenderlo puede producir 409 precio-particular-solapado.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Precio actualizado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PrecioParticularResponse.class))),
			@ApiResponse(responseCode = "400", description = "Vigencia invertida o falta la version",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo, o sin consultorio:manage en la sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La oferta, la sede o el precio no existen, o son de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "Periodo solapado (precio-particular-solapado), precio dado de baja "
							+ "(precio-particular-inactivo) o version desactualizada (concurrent-modification)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PrecioParticularResponse> cambiarFin(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta", example = "34")
			@PathVariable long ofertaId,

			@Parameter(description = "Precio particular", example = "51")
			@PathVariable long precioId,

			@Valid @RequestBody CambiarFinPrecioParticularRequest request) {

		OperatingActor actor = apiActor.current();
		return ResponseEntity.ok(PrecioParticularResponse.de(precioService.cambiarFin(
				actor, exigirContexto(actor), consultorioId, ofertaId, precioId,
				request.vigenciaHasta(), request.expectedVersion())));
	}

	// PRODUCES EXPLICITO por el 406 del Accept problem+json (ver ConvenioController.deactivate).
	@DeleteMapping(path = "/{precioId}",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "deactivatePrecioParticularDeOferta",
			summary = "Dar de baja un precio particular",
			description = """
					Baja LOGICA con motivo obligatorio. Exige consultorio:manage. Libera el \
					periodo; lo ya devengado con ese precio no se toca.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Precio dado de baja"),
			@ApiResponse(responseCode = "400", description = "Falta el motivo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo, o sin consultorio:manage en la sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La oferta, la sede o el precio no existen, o son de otro tenant",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El precio ya estaba dado de baja (precio-particular-inactivo)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivate(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta", example = "34")
			@PathVariable long ofertaId,

			@Parameter(description = "Precio particular", example = "51")
			@PathVariable long precioId,

			@Valid @RequestBody DeactivateOfferingRequest request) {

		OperatingActor actor = apiActor.current();
		precioService.darDeBaja(
				actor, exigirContexto(actor), consultorioId, ofertaId, precioId, request.reason());
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
