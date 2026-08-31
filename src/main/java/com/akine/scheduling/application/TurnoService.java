package com.akine.scheduling.application;

import com.akine.offering.spi.HabilitacionSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.spi.DisponibilidadDirectory.DiaDisponible;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.Turno;
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
import java.util.List;
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
 * <h2>Lo que esta etapa NO hace</h2>
 *
 * <p>No cancela, no reprograma y no registra ausencias: eso es 05.03, que el Paquete B de DP-10
 * dejo afuera. <b>Un turno reservado hoy no se puede deshacer desde ninguna pantalla</b>, y eso hay
 * que saberlo antes de cargar datos de demostracion.
 *
 * <p>Tampoco notifica. La regla "un fallo de email no revierte la reserva" ya la garantiza el
 * outbox transaccional de M26 y nada de aca la rompe, pero cablear un tipo de notificacion nuevo
 * toca las plantillas de {@code notification} y el {@code SecureLinkResolver} de {@code identity},
 * que tiene un defecto abierto conocido. Se difiere a 05.03 con esa razon declarada.
 */
@Service
public class TurnoService {

	private static final Logger log = LoggerFactory.getLogger(TurnoService.class);

	private final TurnoRepositoryPort turnos;
	private final AgendaSedeRepositoryPort agendas;
	private final OfertaDirectory ofertas;
	private final DisponibilidadDirectory disponibilidad;
	private final EspacioDirectory espacios;
	private final PacienteDirectory pacientes;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final AgendaSedeIniciador iniciador;

	public TurnoService(
			TurnoRepositoryPort turnos,
			AgendaSedeRepositoryPort agendas,
			OfertaDirectory ofertas,
			DisponibilidadDirectory disponibilidad,
			EspacioDirectory espacios,
			PacienteDirectory pacientes,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AgendaSedeIniciador iniciador) {

		this.turnos = turnos;
		this.agendas = agendas;
		this.ofertas = ofertas;
		this.disponibilidad = disponibilidad;
		this.espacios = espacios;
		this.pacientes = pacientes;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.iniciador = iniciador;
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
	public TurnoView reservar(
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
			return resolverReintento(yaCreado.get(), command, consultorioId, ofertaId);
		}

		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, ofertaId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));

		ZoneId zona = ZoneId.of(sede.timezone());
		Instant inicio = command.inicio();
		Instant fin = inicio.plusSeconds(oferta.duracionMinutos() * 60L);
		LocalDate fecha = inicio.atZone(zona).toLocalDate();

		if (!oferta.vigenteEl(fecha)) {
			throw new OfertaNoAgendableException(ofertaId, oferta.active()
					? "no esta vigente el " + fecha
					: "esta dada de baja");
		}

		exigirPacienteVigente(organizationId, command.personaId());

		Long profesionalId = resolverProfesional(
				organizationId, consultorioId, oferta, command, sede, inicio, fin);

		// PASO 4. Cupo primero: es una sola consulta y descarta el caso mas frecuente —el slot
		// grupal lleno— sin recorrer habilitaciones ni espacios.
		long ocupados = turnos.contarVivosEnSlot(organizationId, ofertaId, inicio);
		if (ocupados >= oferta.capacidad()) {
			throw new SlotCompletoException(oferta.capacidad());
		}

		if (profesionalId != null
				&& !turnos.findVivosDeProfesionalQueCruzan(
						organizationId, profesionalId, inicio, fin).isEmpty()) {
			// Para una oferta GRUPAL este control es correcto igual: los turnos del mismo slot
			// tienen el mismo inicio, y este predicado los encontraria. Por eso se excluyen los de
			// la misma oferta y hora, que no son un conflicto sino el grupo.
			if (hayConflictoRealDeProfesional(organizationId, profesionalId, ofertaId, inicio, fin)) {
				throw new RecursoOcupadoException("profesional");
			}
		}

		Long espacioId = oferta.requiereEspacio()
				? elegirEspacio(organizationId, consultorioId, ofertaId, inicio, fin)
				: null;

		Instant ahora = Instant.now();
		Turno turno = turnos.save(new Turno(
				organizationId, consultorioId, ofertaId, command.personaId(),
				profesionalId, espacioId, inicio, fin,
				actor.accountId(), ahora,
				command.idempotencyKey(),
				command.idempotencyKey() == null ? null : command.huella(consultorioId, ofertaId)));

		log.info("Turno reservado: turnoId={} consultorioId={} ofertaId={} personaId={} inicio={}",
				turno.getId(), consultorioId, ofertaId, command.personaId(), inicio);

		return TurnoView.de(turno);
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

		turno.confirmar(Instant.now());
		log.info("Turno confirmado: turnoId={} consultorioId={}", turnoId, consultorioId);
		return TurnoView.de(turnos.save(turno));
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
	// Revalidacion del slot
	// =================================================================================

	/**
	 * Resuelve y revalida el profesional del turno.
	 *
	 * <p>Tres controles, y ninguno lo puede hacer el cliente: que este habilitado para la oferta y
	 * vigente ese dia (02.07), y que el intervalo pedido caiga DENTRO de una franja de su
	 * disponibilidad efectiva. El tercero es el que atrapa el caso de RN-M12-004: la pantalla
	 * mostro el slot hace cinco minutos y desde entonces alguien cerro el dia.
	 */
	private Long resolverProfesional(
			long organizationId,
			long consultorioId,
			OfertaSnapshot oferta,
			ReservaCommand command,
			ConsultorioSnapshot sede,
			Instant inicio,
			Instant fin) {

		if (!oferta.requiereProfesional()) {
			return null;
		}
		if (command.profesionalId() == null) {
			throw new SlotNoDisponibleException("la oferta exige profesional y no se indico ninguno");
		}

		boolean habilitado = ofertas
				.profesionalesHabilitados(organizationId, consultorioId, oferta.id()).stream()
				.filter(habilitacion -> habilitacion.recursoId() == command.profesionalId())
				.anyMatch(habilitacion -> habilitacion.vigenteEn(inicio));
		if (!habilitado) {
			throw new SlotNoDisponibleException(
					"el profesional ya no esta habilitado para esta oferta en esa fecha");
		}

		LocalDate fecha = inicio.atZone(ZoneId.of(sede.timezone())).toLocalDate();
		boolean dentroDeFranja = disponibilidad
				.efectiva(organizationId, sede, command.profesionalId(), fecha, fecha.plusDays(1))
				.stream()
				.flatMap(dia -> dia.franjas().stream())
				// Contiene, no se cruza: media consulta fuera del horario no es un turno valido.
				.anyMatch(franja -> !franja.desde().isAfter(inicio) && !franja.hasta().isBefore(fin));
		if (!dentroDeFranja) {
			throw new SlotNoDisponibleException(
					"el profesional ya no atiende en ese horario");
		}

		return command.profesionalId();
	}

	/**
	 * {@code true} si el profesional tiene otro turno que se cruza y que NO es del mismo grupo.
	 *
	 * <p>Sin esta distincion, la segunda inscripcion a una clase grupal fallaria por "profesional
	 * ocupado" contra la primera: los turnos de un mismo slot grupal comparten profesional, oferta
	 * y hora a proposito. Lo que es conflicto es cualquier OTRO turno que se cruce.
	 */
	private boolean hayConflictoRealDeProfesional(
			long organizationId, long profesionalId, long ofertaId, Instant inicio, Instant fin) {

		return turnos.findVivosDeProfesionalQueCruzan(organizationId, profesionalId, inicio, fin)
				.stream()
				.anyMatch(otro -> !(otro.getOfertaId() == ofertaId && otro.getInicio().equals(inicio)));
	}

	/**
	 * Elige el primer espacio habilitado, en servicio y libre en ese intervalo.
	 *
	 * <p><b>El primero y no el mejor.</b> Cualquier criterio de reparto —el menos usado, el mas
	 * chico que alcance— exige leer mas estado dentro del lock que serializa toda la sede, y no
	 * hay ninguna regla de negocio que lo pida. Un criterio se agrega despues sin cambiar la
	 * estructura; la contencion no se saca.
	 *
	 * <p>El orden es el que devuelve el directorio, que es estable: la asignacion es determinista
	 * para el mismo estado de la base.
	 */
	private Long elegirEspacio(
			long organizationId, long consultorioId, long ofertaId, Instant inicio, Instant fin) {

		List<HabilitacionSnapshot> habilitados =
				ofertas.espaciosHabilitados(organizationId, consultorioId, ofertaId);

		for (HabilitacionSnapshot habilitacion : habilitados) {
			if (!habilitacion.vigenteEn(inicio)) {
				continue;
			}
			Optional<EspacioSnapshot> espacio =
					espacios.find(organizationId, habilitacion.recursoId(), inicio);
			boolean utilizable = espacio
					.filter(EspacioSnapshot::active)
					.filter(EspacioSnapshot::enServicio)
					.filter(candidato -> candidato.consultorioId() == consultorioId)
					.isPresent();
			if (!utilizable) {
				continue;
			}
			if (turnos.findVivosDeEspacioQueCruzan(
					organizationId, habilitacion.recursoId(), inicio, fin).isEmpty()) {
				return habilitacion.recursoId();
			}
		}
		// Se distingue de SlotNoDisponible: el hueco existe y el profesional atiende; lo que falta
		// es un box. La pantalla puede ofrecer otro horario en vez de mandar a recargar la agenda.
		throw new RecursoOcupadoException("espacio");
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
