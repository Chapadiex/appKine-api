package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.scheduling.domain.EstadoTurno;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.TipoEventoTurno;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.TurnoEvento;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import com.akine.scheduling.domain.exception.TurnoConAtencionException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.AtencionProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Cancelacion, reprogramacion, ausencia e historial de Turno (M12, AKINE-05.03).
 *
 * <h2>Las tres reglas que gobiernan esta clase</h2>
 *
 * <ol>
 *   <li><b>Nada se borra.</b> RN-M12-002 y ADR-0011. Cancelar cambia el estado y pone
 *       {@code deleted_at}; la fila, su motivo y su historial quedan. Reprogramar mueve el turno y
 *       deja de donde vino en {@code turno_evento}.</li>
 *   <li><b>Cancelar libera el lugar; una ausencia no.</b> Las consultas de solapamiento filtran por
 *       {@code deletedAt IS NULL}, asi que la baja logica es exactamente lo que devuelve el hueco a
 *       la agenda. Un ausente sigue ocupando: la hora se consumio igual.</li>
 *   <li><b>El pasado es inalterable.</b> DP-04. Un turno que ya empezo no se cancela ni se mueve;
 *       lo que se registra sobre el es una ausencia.</li>
 * </ol>
 *
 * <h2>Concurrencia: cual de las tres operaciones toma el lock, y por que</h2>
 *
 * <p><b>Reprogramar SI, las otras dos no.</b> Mover un turno es una escritura de agenda —ocupa un
 * intervalo que estaba libre— y compite contra toda reserva de la sede: sin el lock de
 * {@code agenda_sede}, dos reprogramaciones al mismo hueco pasan las dos su validacion de
 * solapamiento y la agenda queda con dos turnos encima. Por eso reprogramar repite el mismo orden
 * que la reserva de 05.02:
 *
 * <pre>
 *   1. asegurar la fila de agenda   &lt;- en su PROPIA transaccion (deadlock si no)
 *   2. LOCK de agenda_sede          &lt;- ANTES de leer un solo turno
 *   3. leer el turno y validar
 *   4. UPDATE
 * </pre>
 *
 * <p>Y por eso va en {@code READ_COMMITTED}: con {@code REPEATABLE READ} InnoDB fija la foto en la
 * primera lectura consistente, que ocurre antes del lock, y la revalidacion posterior lee datos
 * viejos —el turno que la otra transaccion acaba de commitear no aparece— asi que <b>el lock no
 * sirve</b>. Es la leccion que {@code TurnoConcurrenteIT} pago en 05.02.
 *
 * <p>Cancelar y marcar ausencia <b>liberan o dejan igual</b>: ninguna de las dos puede crear un
 * solapamiento, asi que serializarlas contra toda la sede agregaria contencion sin proteger nada.
 * Lo que si aplica es el control optimista de la fila, con la version que el cliente leyo.
 */
@Service
public class CicloDeTurnoService {

	private static final Logger log = LoggerFactory.getLogger(CicloDeTurnoService.class);

	private static final String ENTIDAD = "Turno";

	private final TurnoRepositoryPort turnos;
	private final TurnoEventoRepositoryPort eventos;
	private final AgendaSedeRepositoryPort agendas;
	private final OfertaDirectory ofertas;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final AgendaSedeIniciador iniciador;
	private final RevalidadorDeSlot revalidador;
	private final AtencionProbe atenciones;
	private final AuditTrail auditTrail;
	private final AvisosDeTurno avisos;

	public CicloDeTurnoService(
			TurnoRepositoryPort turnos,
			TurnoEventoRepositoryPort eventos,
			AgendaSedeRepositoryPort agendas,
			OfertaDirectory ofertas,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AgendaSedeIniciador iniciador,
			RevalidadorDeSlot revalidador,
			AtencionProbe atenciones,
			AuditTrail auditTrail,
			AvisosDeTurno avisos) {

		this.turnos = turnos;
		this.eventos = eventos;
		this.agendas = agendas;
		this.ofertas = ofertas;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.iniciador = iniciador;
		this.revalidador = revalidador;
		this.atenciones = atenciones;
		this.auditTrail = auditTrail;
		this.avisos = avisos;
	}

	// =================================================================================
	// Cancelacion — RF-M12-004
	// =================================================================================

	/**
	 * Cancela un turno futuro con motivo obligatorio. Libera el lugar sin borrar la fila.
	 *
	 * <p><b>Sin lock de sede</b>: ver la cabecera de la clase.
	 *
	 * @throws TurnoNotAccessibleException           el turno no existe en esa sede (404)
	 * @throws TurnoConAtencionException             ya tiene una Sesion registrada (409)
	 * @throws TransicionDeTurnoNoPermitidaException ya cerro su ciclo, o ya empezo (409)
	 * @throws OptimisticLockingFailureException     la version quedo vieja (409)
	 */
	@Transactional
	public TurnoView cancelar(
			OperatingActor actor, long consultorioId, long turnoId,
			String motivo, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		Turno turno = exigirTurno(organizationId, consultorioId, turnoId);
		exigirVersion(turno, expectedVersion);
		exigirSinAtencion(organizationId, consultorioId, turno);

		EstadoTurno anterior = turno.getEstado();
		Instant ahora = Instant.now();
		turno.cancelar(motivo, actor.accountId(), ahora);
		Turno cancelado = turnos.saveAndFlush(turno);

		eventos.registrar(TurnoEvento.de(
				cancelado, TipoEventoTurno.CANCELACION, anterior,
				cancelado.getMotivoCancelacion(), actor.accountId(), ahora));
		auditar(actor, cancelado, "TURNO_CANCELADO", anterior, cancelado.getMotivoCancelacion(), ahora);
		// RF-M26-003. Sin el motivo: ver AvisosDeTurno. La oferta solo pone el nombre del servicio,
		// y si ya no resuelve el aviso sale igual, sin el.
		avisos.avisarCancelacion(cancelado, sede, ofertas
				.find(organizationId, consultorioId, cancelado.getOfertaId())
				.map(OfertaSnapshot::nombreComercial)
				.orElse(null));

		log.info("Turno cancelado: turnoId={} consultorioId={} estadoAnterior={}",
				turnoId, consultorioId, anterior);
		return TurnoView.de(cancelado);
	}

	// =================================================================================
	// Ausencia — RF-M12-007
	// =================================================================================

	/**
	 * Registra que el paciente no vino. <b>No libera el lugar y no altera ningun otro turno.</b>
	 *
	 * <p>DP-04 deroga explicitamente el comportamiento del documento historico de 2019, que borraba
	 * la serie ante la primera ausencia. Aca la ausencia es un hecho de <b>este</b> turno y de
	 * ninguno mas.
	 *
	 * <p>El motivo es opcional, a diferencia de la cancelacion: quien no vino no siempre avisa por
	 * que, y exigir una explicacion inventada por el recepcionista empeora el dato en vez de
	 * mejorarlo.
	 */
	@Transactional
	public TurnoView marcarAusente(
			OperatingActor actor, long consultorioId, long turnoId,
			String motivo, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		Turno turno = exigirTurno(organizationId, consultorioId, turnoId);
		exigirVersion(turno, expectedVersion);
		// Una atencion registrada es la prueba de que el paciente SI vino (DP-05). Marcarlo ausente
		// dejaria la agenda contradiciendo a la historia clinica.
		exigirSinAtencion(organizationId, consultorioId, turno);

		EstadoTurno anterior = turno.getEstado();
		Instant ahora = Instant.now();
		turno.marcarAusente(ahora);
		Turno ausente = turnos.saveAndFlush(turno);

		eventos.registrar(TurnoEvento.de(
				ausente, TipoEventoTurno.AUSENCIA, anterior, normalizar(motivo),
				actor.accountId(), ahora));
		auditar(actor, ausente, "TURNO_AUSENTE", anterior, normalizar(motivo), ahora);

		log.info("Ausencia registrada: turnoId={} consultorioId={}", turnoId, consultorioId);
		return TurnoView.de(ausente);
	}

	// =================================================================================
	// Reprogramacion — RF-M12-005
	// =================================================================================

	/**
	 * Mueve un turno futuro a otro intervalo. <b>Es el mismo turno</b>, con su id y su historial.
	 *
	 * <p>Toma el lock de la sede y revalida el destino con exactamente los mismos controles que una
	 * reserva: ver la cabecera de la clase y {@link RevalidadorDeSlot}.
	 *
	 * <p>La duracion se vuelve a leer de la oferta y no se conserva la del turno: mover un turno a
	 * un horario nuevo es ocupar un slot nuevo, y el slot lo define la oferta vigente hoy.
	 *
	 * @throws OfertaNotAccessibleException          la oferta del turno ya no resuelve (404)
	 * @throws OfertaNoAgendableException            la oferta esta de baja o fuera de vigencia (409)
	 * @throws SlotNoDisponibleException             el horario nuevo no existe (409)
	 * @throws SlotCompletoException                 el horario nuevo no tiene cupo (409)
	 * @throws RecursoOcupadoException               profesional o espacio tomados (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public TurnoView reprogramar(
			OperatingActor actor, long consultorioId, long turnoId, ReprogramacionCommand command) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		// PASO 0 y 1, en el mismo orden que la reserva y por los mismos motivos.
		iniciador.asegurar(organizationId, consultorioId);
		BloqueoDeAgenda.tomar(agendas, organizationId, consultorioId);

		Turno turno = exigirTurno(organizationId, consultorioId, turnoId);
		exigirVersion(turno, command.expectedVersion());
		exigirSinAtencion(organizationId, consultorioId, turno);

		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, turno.getOfertaId())
				.orElseThrow(() -> new OfertaNotAccessibleException(turno.getOfertaId()));

		Instant nuevoInicio = command.inicio();
		Instant nuevoFin = nuevoInicio.plusSeconds(oferta.duracionMinutos() * 60L);
		LocalDate fecha = nuevoInicio.atZone(ZoneId.of(sede.timezone())).toLocalDate();
		if (!oferta.vigenteEl(fecha)) {
			throw new OfertaNoAgendableException(oferta.id(), oferta.active()
					? "no esta vigente el " + fecha
					: "esta dada de baja");
		}

		RevalidadorDeSlot.Asignacion asignacion = revalidador.revalidar(new RevalidadorDeSlot.Pedido(
				organizationId, consultorioId, sede, oferta, nuevoInicio, nuevoFin,
				command.profesionalId(), turnoId));

		EstadoTurno anterior = turno.getEstado();
		Instant inicioAnterior = turno.getInicio();
		Instant finAnterior = turno.getFin();
		Instant ahora = Instant.now();

		turno.reprogramar(nuevoInicio, nuevoFin,
				asignacion.profesionalId(), asignacion.espacioId(), ahora);
		Turno movido = turnos.saveAndFlush(turno);

		eventos.registrar(TurnoEvento.reprogramacion(
				movido, anterior, inicioAnterior, finAnterior,
				command.motivo(), actor.accountId(), ahora));
		auditar(actor, movido, "TURNO_REPROGRAMADO", anterior, command.motivo(), ahora);
		// RF-M26-003. Despues del saveAndFlush: la clave idempotente lleva la version que deja
		// este cambio.
		avisos.avisarReprogramacion(movido, sede, oferta.nombreComercial(), inicioAnterior);

		log.info("Turno reprogramado: turnoId={} consultorioId={} de={} a={}",
				turnoId, consultorioId, inicioAnterior, nuevoInicio);
		return TurnoView.de(movido);
	}

	// =================================================================================
	// Recepcion — RF-M13-002, AKINE-05.04
	// =================================================================================

	/**
	 * Registra que el paciente llego al centro y lo deja en espera.
	 *
	 * <p><b>La hora la pone el servidor</b> (RN-M13-002). La hora de llegada es evidencia
	 * administrativa: si viniera del cliente, el reloj del mostrador —o cualquiera con la consola
	 * del navegador abierta— decidiria a que hora llego un paciente.
	 *
	 * <p><b>Es idempotente</b>, igual que confirmar: marcar dos veces devuelve 200 sin mover la
	 * hora ni registrar un segundo evento. El doble click en el mostrador es el caso normal.
	 *
	 * <p><b>No valida cobertura ni autorizaciones</b>, y es recableo de DP-10 y no un olvido: el
	 * plan hace depender esta etapa de 03.06 y 04.05, que quedaron fuera de alcance, y con
	 * cobertura PARTICULAR unica no hay condicion administrativa que validar. La costura para
	 * cuando existan es esta misma transaccion.
	 *
	 * <p><b>Sin lock de sede</b>: registrar una llegada no ocupa ningun intervalo nuevo, asi que no
	 * puede crear un solapamiento. Ver la cabecera de la clase.
	 *
	 * @throws TurnoNotAccessibleException           el turno no existe en esa sede (404)
	 * @throws TransicionDeTurnoNoPermitidaException esta cancelado o ya marcado ausente (409)
	 */
	@Transactional
	public TurnoView registrarLlegada(OperatingActor actor, long consultorioId, long turnoId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		Turno turno = exigirTurno(organizationId, consultorioId, turnoId);
		if (turno.getEstado() == EstadoTurno.EN_ESPERA) {
			// Idempotente: no se toca la fila ni se registra un segundo evento. Devolver el turno
			// tal como esta es lo que hace que el doble click no tenga consecuencias.
			return TurnoView.de(turno);
		}

		EstadoTurno anterior = turno.getEstado();
		Instant ahora = Instant.now();
		turno.registrarLlegada(ahora, actor.accountId());
		Turno enEspera = turnos.saveAndFlush(turno);

		eventos.registrar(TurnoEvento.de(
				enEspera, TipoEventoTurno.LLEGADA, anterior, null, actor.accountId(), ahora));
		auditar(actor, enEspera, "TURNO_LLEGADA", anterior, null, ahora);

		log.info("Llegada registrada: turnoId={} consultorioId={} estadoAnterior={}",
				turnoId, consultorioId, anterior);
		return TurnoView.de(enEspera);
	}

	/**
	 * Deshace un check-in hecho sobre el turno equivocado.
	 *
	 * <p>Existe porque marcar la llegada es un click y equivocarse tambien. Sin vuelta atras la
	 * unica salida seria cancelar un turno que nadie quiso cancelar.
	 *
	 * <p><b>No es idempotente.</b> Deshacer lo ya deshecho responde 409 y no 200 en silencio: a
	 * diferencia del check-in, aca el segundo click no es un doble click sino una operacion sobre
	 * un turno que entre medio pudo haber cambiado de estado —lo pudieron marcar ausente— y
	 * contestar 200 le haria creer al operador que revirtio algo.
	 *
	 * @throws TurnoNotAccessibleException           el turno no existe en esa sede (404)
	 * @throws TransicionDeTurnoNoPermitidaException no esta en espera (409)
	 */
	@Transactional
	public TurnoView deshacerLlegada(OperatingActor actor, long consultorioId, long turnoId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		Turno turno = exigirTurno(organizationId, consultorioId, turnoId);
		Instant ahora = Instant.now();
		turno.deshacerLlegada();
		Turno revertido = turnos.saveAndFlush(turno);

		eventos.registrar(TurnoEvento.de(
				revertido, TipoEventoTurno.LLEGADA_DESHECHA, EstadoTurno.EN_ESPERA, null,
				actor.accountId(), ahora));
		auditar(actor, revertido, "TURNO_LLEGADA_DESHECHA", EstadoTurno.EN_ESPERA, null, ahora);

		log.info("Llegada deshecha: turnoId={} consultorioId={} estadoNuevo={}",
				turnoId, consultorioId, revertido.getEstado());
		return TurnoView.de(revertido);
	}

	// =================================================================================
	// Historial — RF-M12-008
	// =================================================================================

	/**
	 * El historial de transiciones de un turno, del mas viejo al mas nuevo.
	 *
	 * <p>Exige {@code turno:read} y no {@code turno:manage}: leer quien cancelo y por que es parte
	 * de mirar la agenda, no de operarla.
	 *
	 * <p>Los turnos anteriores a la migracion {@code V38} tienen su evento de reserva —y el de
	 * confirmacion, si la hubo— reconstruidos desde la propia fila. Lo unico que esos eventos no
	 * pueden decir es quien confirmo: V30 no lo guardaba.
	 */
	@Transactional(readOnly = true)
	public List<EventoDeTurnoView> historial(
			OperatingActor actor, long consultorioId, long turnoId) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PermissionCodes.TURNO_READ,
				organizationId, consultorioId, null, Instant.now()));

		// Se exige que el turno exista y sea de esta sede ANTES de leer el historial: sin esto,
		// pedir el historial de un turno de otro tenant devolveria una lista vacia con 200, que
		// es una forma sutil de confirmar que el id no existe aca.
		exigirTurno(organizationId, consultorioId, turnoId);

		return eventos.historial(organizationId, turnoId).stream()
				.map(EventoDeTurnoView::de)
				.toList();
	}

	// =================================================================================
	// Precondiciones
	// =================================================================================

	private Turno exigirTurno(long organizationId, long consultorioId, long turnoId) {
		return turnos.findByIdInScope(organizationId, consultorioId, turnoId)
				.orElseThrow(() -> new TurnoNotAccessibleException(turnoId));
	}

	/**
	 * <p>Se compara la version ANTES de mutar, y no se delega en el {@code @Version} de JPA: sin
	 * este control, el segundo en cancelar pisaria en silencio un motivo distinto del primero, o
	 * cancelaria un turno que entre medio se movio a otro horario.
	 */
	private static void exigirVersion(Turno turno, long expectedVersion) {
		if (turno.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"El turno " + turno.getId() + " cambio desde que se leyo: version "
							+ expectedVersion + " contra " + turno.getVersion());
		}
	}

	/** Ver {@link TurnoConAtencionException}: la Sesion es la prueba de la atencion (DP-05). */
	private void exigirSinAtencion(long organizationId, long consultorioId, Turno turno) {
		if (atenciones.tieneAtencion(organizationId, consultorioId, turno.getId())) {
			throw new TurnoConAtencionException(turno.getId());
		}
	}

	private static String normalizar(String motivo) {
		return motivo == null || motivo.isBlank() ? null : motivo.strip();
	}

	// =================================================================================
	// Auditoria
	// =================================================================================

	/**
	 * <p>Se escribe DENTRO de la transaccion, como todo el resto del sistema: un registro
	 * post-commit que falla deja la transicion hecha y sin rastro, y DP-04 exige auditoria.
	 *
	 * <p>Convive con {@code turno_evento} y no lo duplica: aquella tabla es del dominio de M12 y la
	 * consulta la pantalla del turno; esta es transversal y la consulta un administrador buscando
	 * que hizo una cuenta.
	 */
	private void auditar(
			OperatingActor actor, Turno turno, String evento,
			EstadoTurno anterior, String motivo, Instant ahora) {

		auditTrail.record(new AuditEntry(
				turno.getOrganizationId(),
				turno.getConsultorioId(),
				actor.accountId(),
				evento,
				ENTIDAD,
				turno.getId(),
				anterior.name(),
				turno.getEstado().name(),
				Map.of("personaId", String.valueOf(turno.getPersonaId()),
						"inicio", String.valueOf(turno.getInicio())),
				motivo,
				null,
				ahora));
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException(
					"El ciclo de vida de un turno requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	/** Cancelar, mover y marcar ausencia comprometen un recurso del centro: {@code turno:manage}. */
	private void exigirGestion(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.TURNO_MANAGE,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}
}
