package com.akine.scheduling.api;

import com.akine.scheduling.api.dto.AgendaDelDiaResponse;
import com.akine.scheduling.api.dto.CancelarTurnoRequest;
import com.akine.scheduling.api.dto.EventoDeTurnoResponse;
import com.akine.scheduling.api.dto.RegistrarAusenciaRequest;
import com.akine.scheduling.api.dto.ReprogramarTurnoRequest;
import com.akine.scheduling.api.dto.ReservarTurnoRequest;
import com.akine.scheduling.api.dto.TurnoDelDiaResponse;
import com.akine.scheduling.api.dto.TurnoResponse;
import com.akine.scheduling.application.CicloDeRecepcionService;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.ReprogramacionCommand;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * Reserva y confirmacion de turnos (M12).
 *
 * <p>La ruta cuelga de la SEDE y no de la oferta, a diferencia de la agenda: un turno pertenece a
 * la sede y su oferta es un dato suyo, mientras que un slot solo existe en relacion a una oferta.
 * Colgar el turno de la oferta obligaria a conocerla para leer un turno que ya existe.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/turnos",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Turnos", description = "Reserva de slots con revalidacion atomica e idempotencia (M12)")
public class TurnoController {

	private final TurnoService turnoService;
	private final CicloDeTurnoService cicloService;
	private final RecepcionService recepcionService;
	private final CicloDeRecepcionService cicloDeRecepcion;
	private final SchedulingApiActor apiActor;

	public TurnoController(
			TurnoService turnoService,
			CicloDeTurnoService cicloService,
			RecepcionService recepcionService,
			CicloDeRecepcionService cicloDeRecepcion,
			SchedulingApiActor apiActor) {

		this.turnoService = turnoService;
		this.cicloService = cicloService;
		this.recepcionService = recepcionService;
		this.cicloDeRecepcion = cicloDeRecepcion;
		this.apiActor = apiActor;
	}

	@PostMapping("/ofertas/{ofertaId}")
	@Operation(
			summary = "Reservar un turno",
			description = """
					Toma un slot de una oferta. **El servidor revalida todo**: que la oferta siga \
					vigente ese dia, que el profesional siga habilitado y atendiendo en ese \
					horario, que quede cupo y que ni el profesional ni el box tengan otro turno \
					que se cruce.

					**Una sola reserva gana.** Dos peticiones concurrentes por el mismo hueco se \
					serializan; la segunda recibe un 409 con el tipo que corresponde a su caso.

					Los cuatro conflictos son tipos distintos a proposito, porque llevan a la \
					pantalla a acciones distintas: `slot-no-disponible` (recargar la agenda), \
                    `slot-completo` (ofrecer el siguiente), `recurso-ocupado` (elegir otro \
					horario o profesional) y `persona-sin-perfil-paciente` (activar el perfil).

					Deshacer la reserva es otra operacion: `POST /{turnoId}/cancelacion`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Turno reservado"),
			@ApiResponse(
					responseCode = "200",
					description = "Reintento con la misma clave de idempotencia y el mismo pedido: "
							+ "se devuelve el turno ya creado, sin crear otro"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la oferta o la persona no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El hueco dejo de existir, no queda cupo, el recurso esta ocupado, "
							+ "la persona no es paciente, o la clave de idempotencia se reuso con "
							+ "otro pedido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<TurnoResponse> reservar(
			@PathVariable long consultorioId,
			@PathVariable long ofertaId,
			@RequestBody @Valid ReservarTurnoRequest request) {

		var resultado = turnoService.reservar(
				apiActor.current(), consultorioId, ofertaId,
				new ReservaCommand(
						request.personaId(),
						request.inicio(),
						request.profesionalId(),
						request.idempotencyKey()));

		TurnoResponse cuerpo = TurnoResponse.de(resultado.turno());

		// 200 cuando la clave de idempotencia ya tenia turno: un 201 afirmaria que se creo algo
		// que no se creo, y el contrato promete 200 para ese caso.
		if (!resultado.creado()) {
			return ResponseEntity.ok(cuerpo);
		}
		return ResponseEntity
				.created(URI.create(
						"/api/v1/consultorios/" + consultorioId + "/turnos/" + resultado.turno().id()))
				.body(cuerpo);
	}

	/**
	 * <p><b>Idempotente</b>: confirmar un turno ya confirmado devuelve 200 y no mueve la fecha de
	 * confirmacion. El doble click es el caso normal y castigarlo con un 409 obligaria a la pantalla
	 * a distinguir dos situaciones que para el usuario son la misma.
	 */
	@PostMapping("/{turnoId}/confirmacion")
	@Operation(
			operationId = "confirmarTurno",
			summary = "Confirmar un turno reservado",
			description = """
					Marca la reserva como confirmada. **Es idempotente**: confirmar dos veces \
					devuelve 200 sin cambiar nada.

					Confirmar es un estado de la RESERVA y no del cobro ni de la llegada del \
					paciente: DP-06 deja el prepago como politica configurable y nunca como \
					condicion del dominio clinico.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Turno confirmado"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<TurnoResponse> confirmar(
			@PathVariable long consultorioId,
			@PathVariable long turnoId) {

		return ResponseEntity.ok(TurnoResponse.de(
				turnoService.confirmar(apiActor.current(), consultorioId, turnoId)));
	}

	// =================================================================================
	// Ciclo de vida — AKINE-05.03
	// =================================================================================

	/**
	 * <p><b>POST a un sub-recurso y no DELETE sobre el turno</b>, en las tres transiciones. Un
	 * DELETE prometeria que el turno deja de existir, y RN-M12-002 dice exactamente lo contrario:
	 * la fila queda, con su motivo y su historial. Ademas una cancelacion lleva cuerpo —motivo y
	 * version— y un DELETE con cuerpo es una discusion que no hace falta tener.
	 */
	@PostMapping("/{turnoId}/cancelacion")
	@Operation(
			operationId = "cancelarTurno",
			summary = "Cancelar un turno futuro",
			description = """
					**Cancelar no borra** (RN-M12-002): la fila queda con su motivo, su actor y su \
					historial. Lo que si hace es **liberar el lugar**, que vuelve a estar \
					disponible en la agenda.

					**El motivo es obligatorio** (DP-04), y la version tambien: si otro operador \
					toco el turno entre medio, la cancelacion se rechaza en vez de pisarlo.

					**Solo turnos futuros.** Un turno que ya empezo es inalterable; lo que se \
					registra sobre el es una ausencia.

					**No es idempotente**, a diferencia de confirmar: entre dos cancelaciones el \
					lugar pudo haber sido tomado por otro paciente, y contestar 200 en silencio le \
					haria creer al operador que su motivo quedo registrado.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Turno cancelado"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El turno ya cerro su ciclo, ya empezo, tiene una atencion "
							+ "registrada, o la version quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<TurnoResponse> cancelar(
			@PathVariable long consultorioId,
			@PathVariable long turnoId,
			@RequestBody @Valid CancelarTurnoRequest request) {

		return ResponseEntity.ok(TurnoResponse.de(cicloService.cancelar(
				apiActor.current(), consultorioId, turnoId,
				request.motivo(), request.expectedVersion())));
	}

	@PostMapping("/{turnoId}/reprogramacion")
	@Operation(
			operationId = "reprogramarTurno",
			summary = "Mover un turno a otro horario",
			description = """
					**Es el mismo turno**: conserva id, paciente e historial (DP-04). No se cancela \
					uno y se crea otro, entre otras cosas porque la Sesion de M14 cuelga del \
					`turnoId` y ese vinculo se cortaria.

					El servidor **revalida el destino entero** —vigencia de la oferta, habilitacion \
					y horario del profesional, cupo y solapamiento— bajo el mismo lock de sede que \
					usa una reserva, asi que dos reprogramaciones al mismo hueco no pasan las dos.

					Un turno confirmado **vuelve a `RESERVADO`**: lo que el paciente confirmo era \
					otro horario.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Turno reprogramado"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno, la sede o la oferta no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El horario nuevo ya no existe o no tiene lugar, el turno ya "
							+ "empezo o cerro su ciclo, tiene una atencion registrada, o la "
							+ "version quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<TurnoResponse> reprogramar(
			@PathVariable long consultorioId,
			@PathVariable long turnoId,
			@RequestBody @Valid ReprogramarTurnoRequest request) {

		return ResponseEntity.ok(TurnoResponse.de(cicloService.reprogramar(
				apiActor.current(), consultorioId, turnoId,
				new ReprogramacionCommand(
						request.inicio(), request.profesionalId(),
						request.motivo(), request.expectedVersion()))));
	}

	@PostMapping("/{turnoId}/ausencia")
	@Operation(
			summary = "Registrar que el paciente no vino",
			description = """
					**No libera el lugar**: la hora se consumio igual, el profesional estuvo ahi. \
					Es la diferencia con cancelar.

					**Nunca elimina nada, ni este turno ni ningun otro** (DP-04). El documento \
					historico de 2019 borraba la serie ante la primera ausencia y esa conducta \
					esta explicitamente derogada.

					Solo se registra **despues** de la hora del turno: una ausencia anticipada no \
					es una ausencia, es una cancelacion.

					No prueba nada clinico. Que el paciente haya sido atendido lo dice la Sesion \
					(DP-05), y por eso un turno con atencion registrada no admite esta marca.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Ausencia registrada"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El turno todavia no empezo, ya cerro su ciclo, tiene una "
							+ "atencion registrada, o la version quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<TurnoResponse> registrarAusencia(
			@PathVariable long consultorioId,
			@PathVariable long turnoId,
			@RequestBody @Valid RegistrarAusenciaRequest request) {

		return ResponseEntity.ok(TurnoResponse.de(cicloService.marcarAusente(
				apiActor.current(), consultorioId, turnoId,
				request.motivo(), request.expectedVersion())));
	}

	@GetMapping("/{turnoId}/historial")
	@Operation(
			operationId = "historialTurno",
			summary = "Historial de estados de un turno",
			description = """
					Todas las transiciones del turno, de la mas vieja a la mas nueva, con actor, \
					fecha, motivo y —cuando hubo reprogramacion— el horario del que vino \
					(RF-M12-008).

					Exige `turno:read` y no `turno:manage`: leer quien cancelo y por que es parte \
					de mirar la agenda, no de operarla.

					Los turnos anteriores a la migracion `V38` tienen su evento de reserva \
					reconstruido desde la propia fila; en esos, el actor de la confirmacion viaja \
					vacio porque nunca se habia guardado.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Historial del turno"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:read` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<EventoDeTurnoResponse>> historial(
			@PathVariable long consultorioId,
			@PathVariable long turnoId) {

		return ResponseEntity.ok(
				cicloService.historial(apiActor.current(), consultorioId, turnoId).stream()
						.map(EventoDeTurnoResponse::de)
						.toList());
	}

	// =================================================================================
	// Recepcion — M13, AKINE-05.04
	// =================================================================================

	/**
	 * <p><b>Es la unica lectura de un turno que existe</b>, y por eso nace con la recepcion: hasta
	 * ahora el contrato solo publicaba slots libres y el historial de transiciones, asi que una
	 * pantalla que llegara por un enlace directo tenia que deducir el estado desde los eventos.
	 */
	@GetMapping("/{turnoId}")
	@Operation(
			summary = "Ver un turno",
			description = """
					El turno con el paciente y la oferta ya resueltos, que es lo que necesita \
					cualquier pantalla que llegue por un enlace directo.

					Exige `turno:read`. **No lleva ningun dato clinico**: la atencion es otra \
					cosa y otra pantalla (DP-05).""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El turno"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:read` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<TurnoDelDiaResponse> verTurno(
			@PathVariable long consultorioId,
			@PathVariable long turnoId) {

		return ResponseEntity.ok(TurnoDelDiaResponse.de(
				recepcionService.ver(apiActor.current(), consultorioId, turnoId)));
	}

	/**
	 * <p>El dia se resuelve en la <b>zona de la sede</b>. Un turno es un instante UTC pero "el 7 de
	 * septiembre" es una fecha local: convertir con la zona del servidor haria que la agenda
	 * empiece y termine en horas distintas segun donde este desplegado.
	 */
	@GetMapping
	@Operation(
			operationId = "delDiaTurno",
			summary = "Agenda del dia de la sede",
			description = """
					Los turnos de la sede en un dia, del mas temprano al mas tarde. Es la pantalla \
					de recepcion: quien viene hoy, quien ya llego y quien falta.

					**Incluye los cancelados, con su motivo.** No es un descuido: alguien puede \
					presentarse al mostrador con un turno que se cancelo, y una lista que los \
					esconda deja a la recepcionista sin nada que decirle.

					El dia se interpreta en la zona horaria de la sede. Exige `turno:read`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Turnos del dia"),
			@ApiResponse(
					responseCode = "400",
					description = "`fecha` ausente o mal formada",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:read` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AgendaDelDiaResponse> delDia(
			@PathVariable long consultorioId,
			@RequestParam
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
			@Parameter(description = "Dia a listar, en la zona de la sede", example = "2026-09-15")
			LocalDate fecha) {

		var vista = recepcionService.delDia(apiActor.current(), consultorioId, fecha);
		return ResponseEntity.ok(new AgendaDelDiaResponse(
				vista.fecha(),
				vista.timezone(),
				vista.turnos().stream().map(TurnoDelDiaResponse::de).toList()));
	}

	/**
	 * <p><b>Deprecado desde E-4 (DP-16).</b> Se mantiene para no romper al cliente de 05.04: abre la
	 * recepcion igual que {@code POST /{turnoId}/recepcion} y devuelve el turno con la hora de
	 * llegada.
	 */
	@PostMapping("/{turnoId}/llegada")
	@Operation(
			summary = "Registrar la llegada del paciente (deprecado)",
			deprecated = true,
			description = """
					**Deprecado desde 0.63.0**: usar `POST /{turnoId}/recepcion` (DP-16).

					Abre la recepcion del turno en `LLEGO`, igual que aquella, y devuelve el turno \
					con `llegadaEn`. **El turno ya no pasa a `EN_ESPERA`**: la espera es un estado \
					de la recepcion, no de la reserva, y el turno sigue en `RESERVADO` o \
					`CONFIRMADO`.

					Es idempotente: marcar dos veces devuelve 200 sin mover la hora.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Paciente en espera"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El turno esta cancelado o ya marcado ausente. `problemType`: `turno-transicion-no-permitida`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<TurnoResponse> registrarLlegada(
			@PathVariable long consultorioId,
			@PathVariable long turnoId) {

		var resultado = cicloDeRecepcion.registrarLlegada(apiActor.current(), consultorioId, turnoId);
		return ResponseEntity.ok(TurnoResponse.de(
				resultado.turno().conLlegada(resultado.recepcion().llegadaEn())));
	}

	@DeleteMapping("/{turnoId}/llegada")
	@Operation(
			summary = "Deshacer un check-in (deprecado)",
			deprecated = true,
			description = """
					**Deprecado desde 0.63.0**: usar `POST /{turnoId}/recepcion/anulacion` (DP-16).

					Anula la recepcion abierta del turno: la llegada no vale. La fila y su \
					historial quedan. Devuelve el turno, que no cambia de estado.

					**No es idempotente**: sin recepcion abierta responde 409 \
					(`recepcion-transicion-no-permitida`).""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Check-in revertido"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `turno:manage` en esa sede",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El turno o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "No hay recepcion abierta. `problemType`: `recepcion-transicion-no-permitida`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<TurnoResponse> deshacerLlegada(
			@PathVariable long consultorioId,
			@PathVariable long turnoId) {

		return ResponseEntity.ok(TurnoResponse.de(
				cicloDeRecepcion.deshacerLlegadaDeprecada(apiActor.current(), consultorioId, turnoId)));
	}
}
