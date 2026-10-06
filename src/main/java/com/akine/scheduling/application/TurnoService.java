package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.scheduling.domain.EstadoTurno;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.TipoEventoTurno;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.TurnoEvento;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Reserva y confirmacion de turnos (M12, RF-M12-002 y RF-M12-003).
 *
 * <h2>El unico lugar del sistema donde la concurrencia decide la correctitud</h2>
 *
 * <p>Dos recepcionistas mirando la misma pantalla tienen que poder apretar confirmar a la vez y que
 * <b>una sola</b> reserva gane. La exclusion NO la da ningun unique de la base y no puede darla:
 * un unique compara igualdad, y dos turnos se pisan cuando sus INTERVALOS se cruzan. Un turno de
 * 09:00 a 10:00 y otro de 09:30 a 10:00 no comparten un solo valor de columna, y el caso es real
 * porque dos ofertas con duraciones distintas producen slots que no caen en la misma grilla.
 *
 * <p>MySQL 8.4 no tiene exclusion constraints —son de PostgreSQL— asi que la regla la hace cumplir
 * la validacion de esta clase, y lo que la hace confiable es el orden:
 *
 * <pre>
 *   1. LOCK de agenda_sede            &lt;- ANTES de leer un solo turno
 *   2. idempotencia
 *   3. oferta, persona, slot
 *   4. cupo y solapamiento
 *   5. INSERT
 * </pre>
 *
 * <p><b>El paso 1 va primero y no es negociable.</b> Leer los turnos y despues tomar el lock es una
 * escalada S -&gt; X sobre las mismas filas: dos transacciones concurrentes se quedan cada una con
 * su lock compartido esperando el exclusivo de la otra, y eso es un deadlock. Esta escrito en el
 * diseno de 02.04 y vale aca sin un solo cambio.
 *
 * <h2>Lo que se revalida, y por que no alcanza con lo que mando el cliente</h2>
 *
 * <p>Entre que la pantalla dibujo la agenda y el usuario apreto confirmar pudo cambiar todo: el
 * horario del profesional, un feriado, la vigencia de la oferta o de la habilitacion. El motor de
 * 05.01 no persiste slots, asi que la unica forma de saber que el hueco sigue existiendo es
 * recalcularlo DENTRO de esta transaccion. RN-M12-004.
 *
 * <h2>Que hace esta clase y que hace su vecina</h2>
 *
 * <p>Aca vive lo que <b>crea</b> la reserva: reservar y confirmar. Cancelar, reprogramar, registrar
 * ausencia y leer el historial son {@link CicloDeTurnoService}, que 05.03 agrego al lado en vez de
 * adentro. Las dos comparten el lock de la sede y el {@link RevalidadorDeSlot}, que es lo unico que
 * tenian que compartir.
 *
 * <p>Las dos notifican al paciente por el outbox transaccional de M26 (RF-M26-002/003, AKINE E-5),
 * dentro de la misma transaccion de la operacion: ver {@link AvisosDeTurno}.
 */
@Service
public class TurnoService {

	private static final Logger log = LoggerFactory.getLogger(TurnoService.class);

	private final TurnoRepositoryPort turnos;
	private final TurnoEventoRepositoryPort eventos;
	private final AgendaSedeRepositoryPort agendas;
	private final OfertaDirectory ofertas;
	private final PacienteDirectory pacientes;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final AgendaSedeIniciador iniciador;
	private final RevalidadorDeSlot revalidador;
	private final AvisosDeTurno avisos;

	public TurnoService(
			TurnoRepositoryPort turnos,
			TurnoEventoRepositoryPort eventos,
			AgendaSedeRepositoryPort agendas,
			OfertaDirectory ofertas,
			PacienteDirectory pacientes,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AgendaSedeIniciador iniciador,
			RevalidadorDeSlot revalidador,
			AvisosDeTurno avisos) {

		this.turnos = turnos;
		this.eventos = eventos;
		this.agendas = agendas;
		this.ofertas = ofertas;
		this.pacientes = pacientes;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.iniciador = iniciador;
		this.revalidador = revalidador;
		this.avisos = avisos;
	}

	/**
	 * Reserva un turno. Ver la cabecera de la clase para el orden y su motivo.
	 *
	 * @throws ConsultorioNoAccesibleException      sede inexistente o de otro tenant (404)
	 * @throws OfertaNotAccessibleException         oferta inexistente en esa sede (404)
	 * @throws PersonaNotAccessibleException        persona inexistente en el tenant (404)
	 * @throws OfertaNoAgendableException           oferta de baja o fuera de vigencia (409)
	 * @throws PersonaSinPerfilPacienteException    la persona no es paciente (409)
	 * @throws SlotNoDisponibleException            el hueco dejo de existir (409)
	 * @throws SlotCompletoException                sin cupo (409)
	 * @throws RecursoOcupadoException              profesional o espacio ya tomados (409)
	 * @throws IdempotencyKeyConflictException      misma clave, pedido distinto (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ResultadoDeReserva reservar(
			OperatingActor actor, long consultorioId, long ofertaId, ReservaCommand command) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		// PASO 0. En su PROPIA transaccion: crearla dentro de esta deadlockea entre las
		// primeras reservas concurrentes de una sede. Ver AgendaSedeIniciador.
		iniciador.asegurar(organizationId, consultorioId);

		// PASO 1. Antes de leer nada. Ver la cabecera.
		BloqueoDeAgenda.tomar(agendas, organizationId, consultorioId);

		// PASO 2. Bajo el lock, para que dos peticiones con la misma clave no creen dos turnos.
		// Fuera del lock las dos leerian "no existe" antes de que ninguna inserte.
		Optional<Turno> yaCreado = command.idempotencyKey() == null
				? Optional.empty()
				: turnos.findByIdempotencyKey(organizationId, command.idempotencyKey());
		if (yaCreado.isPresent()) {
			return new ResultadoDeReserva(
					resolverReintento(yaCreado.get(), command, consultorioId, ofertaId), false);
		}

		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, ofertaId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));

		ZoneId zona = ZoneId.of(sede.timezone());
		Instant inicio = command.inicio();
		Instant fin = inicio.plusSeconds(oferta.duracionMinutos() * 60L);
		LocalDate fecha = inicio.atZone(zona).toLocalDate();

		// Defecto encontrado en E-3. Reprogramar ya rechazaba un destino pasado y la reserva no: el
		// motor de 05.01 dibuja tambien los slots de hoy que ya pasaron, y el revalidador solo mira
		// que el hueco exista. Un turno reservado en el pasado nace inalterable (DP-04) y un error de
		// fecha solo podia cerrarse marcando AUSENTE a un paciente que nunca falto. Va DESPUES de la
		// idempotencia: el reintento de un turno ya creado cuya hora paso sigue devolviendo ese turno.
		if (!inicio.isAfter(Instant.now())) {
			throw new SlotNoDisponibleException("el horario ya paso: un turno se reserva hacia adelante");
		}

		if (!oferta.vigenteEl(fecha)) {
			throw new OfertaNoAgendableException(ofertaId, oferta.active()
					? "no esta vigente el " + fecha
					: "esta dada de baja");
		}

		exigirPacienteVigente(organizationId, command.personaId());

		// PASO 4. Los mismos controles que aplica una reprogramacion, y en el mismo lugar: las dos
		// son escrituras de agenda. Ver RevalidadorDeSlot.
		RevalidadorDeSlot.Asignacion asignacion = revalidador.revalidar(new RevalidadorDeSlot.Pedido(
				organizationId, consultorioId, sede, oferta, inicio, fin,
				command.profesionalId(), null));

		Instant ahora = Instant.now();
		Turno turno = turnos.save(new Turno(
				organizationId, consultorioId, ofertaId, command.personaId(),
				asignacion.profesionalId(), asignacion.espacioId(), inicio, fin,
				actor.accountId(), ahora,
				command.idempotencyKey(),
				command.idempotencyKey() == null ? null : command.huella(consultorioId, ofertaId)));

		eventos.registrar(TurnoEvento.de(
				turno, TipoEventoTurno.RESERVA, null, null, actor.accountId(), ahora));
		// RF-M26-002. En esta misma transaccion: si la reserva hace rollback, el aviso tambien.
		// El reintento idempotente de arriba retorna antes y no encola de nuevo.
		avisos.avisarReserva(turno, sede, oferta.nombreComercial());

		log.info("Turno reservado: turnoId={} consultorioId={} ofertaId={} personaId={} inicio={}",
				turno.getId(), consultorioId, ofertaId, command.personaId(), inicio);

		return new ResultadoDeReserva(TurnoView.de(turno), true);
	}

	/**
	 * Confirma una reserva.
	 *
	 * <p><b>No toma el lock de la sede.</b> Confirmar no cambia que lugar esta ocupado —el turno ya
	 * lo ocupaba desde que se reservo— asi que no compite con ninguna otra reserva. Serializarlo
	 * contra toda la sede solo agregaria contencion sin proteger nada. El control de concurrencia
	 * que si aplica es el optimista de la fila, que JPA hace solo.
	 */
	@Transactional
	public TurnoView confirmar(OperatingActor actor, long consultorioId, long turnoId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		Turno turno = turnos.findByIdInScope(organizationId, consultorioId, turnoId)
				.orElseThrow(() -> new TurnoNotAccessibleException(turnoId));

		EstadoTurno anterior = turno.getEstado();
		Instant ahora = Instant.now();
		turno.confirmar(ahora);
		Turno confirmado = turnos.saveAndFlush(turno);

		// Solo si hubo transicion: confirmar es idempotente, y registrar un evento por cada doble
		// click llenaria el historial de filas que no cuentan ningun hecho nuevo.
		if (anterior != confirmado.getEstado()) {
			eventos.registrar(TurnoEvento.de(
					confirmado, TipoEventoTurno.CONFIRMACION, anterior, null,
					actor.accountId(), ahora));
		}

		log.info("Turno confirmado: turnoId={} consultorioId={}", turnoId, consultorioId);
		return TurnoView.de(confirmado);
	}

	// =================================================================================
	// Idempotencia
	// =================================================================================

	/**
	 * Decide que hacer con una clave de idempotencia ya usada.
	 *
	 * <p>Mismo pedido, mismo turno: se devuelve el que ya existe, que es toda la promesa de la
	 * idempotencia y lo que resuelve el doble click. Pedido distinto: <b>409 explicito</b>, porque
	 * devolver el turno anterior le haria creer al cliente que reservo el nuevo.
	 */
	private static TurnoView resolverReintento(
			Turno existente, ReservaCommand command, long consultorioId, long ofertaId) {

		String huella = command.huella(consultorioId, ofertaId);
		if (!huella.equals(existente.getRequestHash())) {
			throw new IdempotencyKeyConflictException(command.idempotencyKey());
		}
		return TurnoView.de(existente);
	}
	// =================================================================================
	// Precondiciones
	// =================================================================================

	/**
	 * Exige que la persona exista en el tenant y tenga perfil de paciente vigente.
	 *
	 * <p><b>Este camino no crea el perfil.</b> RF-M07-010 y la decision estructural de 03.01:
	 * Persona y Paciente son cosas distintas y activarlo es una accion propia con su actor. Un
	 * turno que active perfiles en silencio convertiria en paciente a cualquiera que alguna vez
	 * fue anotado en una agenda.
	 */
	private void exigirPacienteVigente(long organizationId, long personaId) {
		PacienteSnapshot paciente = pacientes.find(organizationId, personaId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));
		if (!paciente.activa() || !paciente.esPacienteVigente()) {
			throw new PersonaSinPerfilPacienteException(personaId);
		}
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La reserva de turnos requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	/**
	 * Exige {@code turno:manage} con la sede como alcance.
	 *
	 * <p>Es distinto de {@code turno:read}, que alcanza para mirar la agenda. Reservar compromete
	 * un recurso del centro y no es una lectura.
	 */
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
