package com.akine.scheduling.api;

import com.akine.platform.spi.listado.OrdenDeListado;
import com.akine.scheduling.api.dto.HistorialDeAgendaPageResponse;
import com.akine.scheduling.application.FiltroDeHistorialDeAgenda;
import com.akine.scheduling.application.HistorialDeAgendaService;
import com.akine.scheduling.application.TipoDeCambioDeAgenda;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Historial de cambios de la agenda de un profesional (AKINE-agenda-historial).
 *
 * <p>Va por organizacion y no por sede porque la persona atiende en varias: el historial junta sus
 * memberships de todas las sedes que el lector alcanza. {@code consultorioId} acota a una.
 */
@RestController
@RequestMapping(
		path = "/api/v1/organizations/{orgId}/agenda/historial",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
// Mismo nombre Y misma descripcion que AgendaController: springdoc deduplica los tags por igualdad
// completa, y una descripcion distinta publica un segundo tag "Agenda" que el generador del
// frontend rechaza ("attribute tags.Agenda is repeated").
@Tag(
		name = "Agenda",
		description = "Turnos disponibles calculados a partir de disponibilidad, oferta y "
				+ "habilitaciones (M12)")
public class HistorialDeAgendaController {

	static final int TAMANO_POR_DEFECTO = 10;
	static final int TAMANO_MAXIMO = 100;

	private final HistorialDeAgendaService historial;
	private final SchedulingApiActor apiActor;

	public HistorialDeAgendaController(HistorialDeAgendaService historial, SchedulingApiActor apiActor) {
		this.historial = historial;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listarHistorialDeAgenda",
			summary = "Historial de cambios de la agenda de un profesional",
			description = """
					Horario habitual (alta, edicion, baja), excepciones y bloqueos —ausencias, licencias y \
					vacaciones incluidas; un desbloqueo es la baja de una excepcion— y turnos (reserva, \
					sobreturno, confirmacion, cancelacion, ausencia, reprogramacion, cambio de sede, \
					observaciones) del profesional, con quien lo hizo y con que rol, cuando, el motivo y \
					como estaba antes y como quedo despues, ya armado para mostrar.

					`profesionalId` es la membership del profesional en cualquier sede: se leen todas \
					las de esa persona en la organizacion. Una reprogramacion que le saco un turno \
					tambien aparece.

					Exige `agenda:excepciones` con su alcance: el `ORG_ADMIN` lee la organizacion, el \
					`CONSULTORIO_ADMIN` y el `ADMINISTRATIVO` sus sedes, y el `PROFESIONAL` solo su \
					propia agenda (403 sobre la de otro). El paciente de un turno viaja solo a quien \
					tiene `turno:read` en esa sede.

					`desde` y `hasta` son instantes UTC (ISO-8601) sobre cuando se hizo el cambio, no sobre la \
					fecha del turno: `desde` inclusivo, `hasta` exclusivo. Para "el dia de la sede" el cliente \
					convierte la medianoche local a UTC. Las fechas de `antes`/`despues` ya vienen en la zona \
					de la sede de cada cambio.

					`actorRol` es el rol de quien opero: `ORG_ADMIN`, `CONSULTORIO_ADMIN`, `ADMINISTRATIVO`, \
					`PROFESIONAL` (o `PLATFORM_ADMIN` con acceso de soporte); ausente en cambios anteriores \
					a la agenda de profesionales.

					Es una lectura de la auditoria transversal: una sola fuente.

					**Un cambio de sede es una sola fila** `TURNO_TRASLADO`, con el antes (sede, hora, \
					profesional y box de origen) y el despues; la cancelacion del original y la reserva del \
					nuevo no aparecen como filas propias y viajan en `eventosRelacionados`. Si el traslado \
					paso el turno a otra persona, la fila aparece en el historial de las dos \
					(`cambioDeProfesional`). Una cancelacion que no es parte de un traslado sigue siendo \
					`TURNO_CANCELACION`.

					**Cierres de la sede entera** (`CIERRE_DE_SEDE_ALTA/EDICION/BAJA`): una excepcion de sede \
					sin profesional aparece en el historial de cada profesional al que afecto —tenia horario \
					habitual en esa sede en el periodo, o turnos o clases ahi—, resuelto al leer. Un cierre de \
					otra sede, o de una en la que la persona no atendia ese periodo, no aparece. `cierreDeSede` \
					trae el periodo (`ultimoDia` inclusive), el motivo y los turnos del profesional en el periodo, \
					para ir a la agenda operativa de esa sede y fecha.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pagina de cambios, del mas nuevo al mas viejo por defecto",
					content = @Content(schema = @Schema(implementation = HistorialDeAgendaPageResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "`tipo` o `sort` fuera del enum, o `hasta` no posterior a `desde`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto, o sin `agenda:excepciones` sobre ninguna sede del pedido "
							+ "(con alcance propio: la agenda es de otro profesional; en ese caso el problema `forbidden` lleva "
							+ "la extension `alcance: \"PROPIO\"`)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La organizacion no es la del contexto, o el profesional o la sede no son de ella",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<HistorialDeAgendaPageResponse> listar(
			@Parameter(description = "Organizacion del contexto", example = "7")
			@PathVariable long orgId,

			@Parameter(description = "Membership del profesional, en cualquiera de sus sedes", example = "31",
					required = true)
			@RequestParam long profesionalId,

			@Parameter(description = "Acota a una sede", example = "3")
			@RequestParam(required = false) Long consultorioId,

			@Parameter(description = "Tipos de cambio; repetible. Sin el, todos",
					array = @ArraySchema(schema = @Schema(allowableValues = {"HORARIO_ALTA", "HORARIO_EDICION",
							"HORARIO_BAJA", "EXCEPCION_ALTA", "EXCEPCION_EDICION", "EXCEPCION_BAJA", "CIERRE_DE_SEDE_ALTA",
							"CIERRE_DE_SEDE_EDICION", "CIERRE_DE_SEDE_BAJA", "TURNO_RESERVA",
							"TURNO_SOBRETURNO", "TURNO_CONFIRMACION", "TURNO_CANCELACION", "TURNO_AUSENCIA",
							"TURNO_REPROGRAMACION", "TURNO_TRASLADO", "TURNO_OBSERVACIONES"})))
			@RequestParam(required = false) List<String> tipo,

			@Parameter(description = "Desde (inclusivo), sobre cuando se hizo el cambio",
					example = "2026-11-01T03:00:00Z")
			@RequestParam(required = false) Instant desde,

			@Parameter(description = "Hasta (exclusivo)", example = "2026-12-01T03:00:00Z")
			@RequestParam(required = false) Instant hasta,

			@Parameter(description = "Texto: contiene, en el motivo y en el tipo de evento", example = "licencia")
			@RequestParam(required = false) String q,

			@Parameter(description = "Pagina, base cero", example = "0")
			@RequestParam(defaultValue = "0") int page,

			@Parameter(description = "Elementos por pagina. Por defecto 10; se recorta a 100", example = "10")
			@RequestParam(defaultValue = "10") int size,

			@Parameter(description = OrdenDeListado.DESCRIPCION + "ocurridoEn descendente.",
					schema = @Schema(allowableValues = {"ocurridoEn,asc", "ocurridoEn,desc"}))
			@RequestParam(name = OrdenDeListado.PARAMETRO, required = false) String sort) {

		Set<TipoDeCambioDeAgenda> tipos = tipo == null ? Set.of()
				: tipo.stream().filter(t -> t != null && !t.isBlank()).map(TipoDeCambioDeAgenda::leer)
						.collect(Collectors.toSet());
		Sort orden = OrdenDeListado.leer(sort, "ocurridoEn")
				.map(pedido -> pedido.aSort("occurredAt", "id"))
				.orElse(Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id")));
		PageRequest pagina = PageRequest.of(Math.max(page, 0),
				size < 1 ? TAMANO_POR_DEFECTO : Math.min(size, TAMANO_MAXIMO), orden);

		return ResponseEntity.ok(HistorialDeAgendaPageResponse.de(historial.listar(apiActor.current(), orgId,
				new FiltroDeHistorialDeAgenda(profesionalId, consultorioId, tipos, desde, hasta,
						q == null || q.isBlank() ? null : q.strip()),
				pagina)));
	}
}
