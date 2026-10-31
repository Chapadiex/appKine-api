package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.ContactoDePersona;
import com.akine.person.spi.CoberturasAplicablesDirectory;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.transaccion.FilaLockPrevia;
import com.akine.scheduling.domain.EstadoTurno;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.TipoEventoTurno;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.TurnoEvento;
import com.akine.scheduling.domain.exception.CoberturaNoAccesibleException;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.ContextoClinicoInvalidoException;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
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
 *   0. oferta, persona, cobertura,     &lt;- ANTES del lock: no leen turnos (docs/carga.md §10).
 *      habilitacion, franja, contacto     Sus rechazos se GUARDAN y se lanzan en el paso 3
 *   1. LOCK de agenda_sede            &lt;- ANTES de leer un solo turno
 *   2. idempotencia
 *   3. rechazos del paso 0, en el orden de siempre
 *   4. cupo y solapamiento
 *   5. INSERT
 * </pre>
 *
 * <p><b>El paso 0 no debilita nada que el lock protegia.</b> El lock de {@code agenda_sede} lo
 * toman quienes ocupan la agenda —reservas, reprogramaciones, series y clases—; nadie que cambie
 * una oferta, una persona, una cobertura, una habilitacion o una disponibilidad lo toma. En
 * {@code READ_COMMITTED} esas lecturas ven lo ultimo commiteado al momento de leer, bajo el lock o
 * antes, y ninguna quedaba protegida hasta el commit. Moverlas afuera saca diez sentencias de la
 * seccion que serializa todas las reservas de la sede. Sus rechazos se lanzan despues de la
 * idempotencia para que un reintento de un turno ya creado siga devolviendo ese turno.
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

	/** Ancho de {@code turno_evento.motivo} (V38). El motivo completo queda en el turno. */
	private static final int LARGO_DE_MOTIVO_DE_EVENTO = 300;

	private static final Logger log = LoggerFactory.getLogger(TurnoService.class);

	private final TurnoRepositoryPort turnos;
	private final TurnoEventoRepositoryPort eventos;
	private final AgendaSedeRepositoryPort agendas;
	private final OfertaDirectory ofertas;
	private final PacienteDirectory pacientes;
	private final CoberturasAplicablesDirectory coberturas;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final AgendaSedeIniciador iniciador;
	private final RevalidadorDeSlot revalidador;
	private final AvisosDeTurno avisos;
	private final ContextoClinicoDeTurno contextoClinico;
	private final SesionesDelPlan sesionesDelPlan;
	private final AuditTrail auditTrail;
	private final TransactionTemplate transaccionDeReserva;

	public TurnoService(
			TurnoRepositoryPort turnos,
			TurnoEventoRepositoryPort eventos,
			AgendaSedeRepositoryPort agendas,
			OfertaDirectory ofertas,
			PacienteDirectory pacientes,
			CoberturasAplicablesDirectory coberturas,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AgendaSedeIniciador iniciador,
			RevalidadorDeSlot revalidador,
			AvisosDeTurno avisos,
			ContextoClinicoDeTurno contextoClinico,
			SesionesDelPlan sesionesDelPlan,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager) {

		this.turnos = turnos;
		this.eventos = eventos;
		this.agendas = agendas;
		this.ofertas = ofertas;
		this.pacientes = pacientes;
		this.coberturas = coberturas;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.iniciador = iniciador;
		this.revalidador = revalidador;
		this.avisos = avisos;
		this.contextoClinico = contextoClinico;
		this.sesionesDelPlan = sesionesDelPlan;
		this.auditTrail = auditTrail;
		this.transaccionDeReserva = FilaLockPrevia.transaccionDeNegocio(
				transactionManager, Isolation.READ_COMMITTED);
	}

	/**
	 * Reserva un turno. Ver la cabecera de la clase para el orden y su motivo.
	 *
	 * @throws ConsultorioNoAccesibleException      sede inexistente o de otro tenant (404)
	 * @throws OfertaNotAccessibleException         oferta inexistente en esa sede (404)
	 * @throws PersonaNotAccessibleException        persona inexistente en el tenant (404)
	 * @throws OfertaNoAgendableException           oferta de baja o fuera de vigencia (409)
	 * @throws PersonaSinPerfilPacienteException    la persona no es paciente (409)
	 * @throws CoberturaNoAccesibleException        cobertura elegida que no es de la persona o no
	 *                                              esta vigente el dia del turno (404)
	 * @throws SlotNoDisponibleException            el hueco dejo de existir (409)
	 * @throws SlotCompletoException                sin cupo (409)
	 * @throws RecursoOcupadoException              profesional o espacio ya tomados (409)
	 * @throws IdempotencyKeyConflictException      misma clave, pedido distinto (409)
	 */
	public ResultadoDeReserva reservar(
			OperatingActor actor, long consultorioId, long ofertaId, ReservaCommand command) {

		// Sin transaccion hasta despues del PASO 0. Estas lecturas no necesitan el lock: antes
		// corrian en la misma transaccion READ_COMMITTED, donde cada sentencia ya veia lo ultimo
		// commiteado, asi que leerlas en la suya no cambia lo que ven.
		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);
		if (command.esSobreturno()) {
			// El permiso se pide aparte de turno:manage: dar un turno es de recepcion y pisar uno es de
			// quien puede autorizarlo (TURNO.md §21). El backend lo exige aunque la pantalla esconda la
			// accion.
			exigirSobreturno(actor, organizationId, consultorioId);
		}

		// PASO 0. En su PROPIA transaccion y ANTES de abrir la de la reserva: crearla dentro de
		// esta deadlockea entre las primeras reservas concurrentes de una sede (AgendaSedeIniciador),
		// y abrirla anidada retiene dos conexiones por pedido y traba el pool (FilaLockPrevia).
		iniciador.asegurar(organizationId, consultorioId);

		ResultadoDeReserva resultado = transaccionDeReserva.execute(estado -> reservarBajoLock(
				actor, organizationId, sede, consultorioId, ofertaId, command));

		// Despues del commit y no adentro: el log escribe en la consola, y adentro lo hacia con el
		// lock de la agenda tomado. El reintento idempotente no loguea, como antes.
		if (resultado.creado()) {
			log.info("Turno reservado: turnoId={} consultorioId={} ofertaId={} personaId={} inicio={}",
					resultado.turno().id(), consultorioId, ofertaId, command.personaId(), command.inicio());
		}
		return resultado;
	}

	private static String motivoDeEventoDeSobreturno(String motivo) {
		String texto = "Sobreturno: " + motivo;
		return texto.length() <= LARGO_DE_MOTIVO_DE_EVENTO ? texto : texto.substring(0, LARGO_DE_MOTIVO_DE_EVENTO);
	}

	/**
	 * Lo que la reserva lee ANTES del lock (paso 0 de la cabecera).
	 *
	 * <p>Los rechazos de negocio no se lanzan al leer: se guardan en {@link #rechazo} y se lanzan bajo
	 * el lock, despues de la idempotencia y en el orden de siempre. La oferta va aparte porque su 404
	 * sale antes del control de horario pasado y los demas despues.
	 */
	private static final class Preparacion {

		/** {@code null}: la oferta no existe en la sede. */
		private OfertaSnapshot oferta;
		/** Con la duracion de quien atiende. {@code null} si la oferta no existe. */
		private Instant fin;
		private RevalidadorDeSlot.Previa previa;
		private ContactoDePersona contacto;
		private ContextoClinicoDeTurno.Resuelto contexto = ContextoClinicoDeTurno.Resuelto.NINGUNO;
		/** El primer rechazo de negocio, o {@code null}. */
		private RuntimeException rechazo;
	}

	/**
	 * Las lecturas que no tocan turnos, en la transaccion de la reserva y antes del lock.
	 *
	 * <p>En la misma transaccion y no en una propia: es {@code READ_COMMITTED}, asi que cada
	 * sentencia ve lo ultimo commiteado igual que bajo el lock, y una transaccion aparte costaria
	 * otra vuelta al pool. Ninguna es una lectura con lock: nada de esto puede quedar esperando a
	 * otra reserva ni escalar a un deadlock con ella.
	 *
	 * <p><b>Solo se guardan los rechazos de negocio</b>, que lanza este mismo codigo y no un metodo
	 * transaccional: no marcan la transaccion ni rompen la sesion de Hibernate. Un error tecnico
	 * —{@code DataAccessException}, conexion caida, deadlock— se propaga en el acto: seguir con una
	 * sesion que ya lanzo no esta soportado, y en el camino idempotente terminaria en un 200 despues
	 * de un error.
	 */
	private Preparacion preparar(long organizationId, ConsultorioSnapshot sede, long consultorioId,
			long ofertaId, ReservaCommand command) {

		Preparacion preparacion = new Preparacion();
		preparacion.oferta = ofertas.find(organizationId, consultorioId, ofertaId).orElse(null);
		if (preparacion.oferta == null) {
			return preparacion;
		}
		OfertaSnapshot oferta = preparacion.oferta;
		Instant inicio = command.inicio();
		// La reserva no asigna profesional: lo trae el pedido, y sin el una oferta que lo requiere se
		// rechaza mas abajo. No hay forma de que este fin salga con la duracion de otro.
		Instant fin = inicio.plusSeconds(oferta.duracionCon(
				ofertas.duracionesPropias(organizationId, consultorioId, ofertaId), command.profesionalId()) * 60L);
		preparacion.fin = fin;
		LocalDate fecha = inicio.atZone(ZoneId.of(sede.timezone())).toLocalDate();

		try {
			if (!oferta.vigenteEl(fecha)) {
				throw new OfertaNoAgendableException(ofertaId, oferta.active()
						? "no esta vigente el " + fecha
						: "esta dada de baja");
			}

			exigirPacienteVigente(organizationId, command.personaId());

			// AKINE-05.02, "cobertura elegida". Solo pertenencia y vigencia ese dia: si aplica a la
			// practica lo decide la recepcion (RF-M13-003), que observa sin bloquear (RN-M13-003).
			// Rechazarlo aca por convenio haria que un arancel cargado tarde impida reservar.
			if (command.coberturaId() != null && !coberturas.vigenteDeLaPersona(
					organizationId, command.personaId(), command.coberturaId(), fecha)) {
				throw new CoberturaNoAccesibleException(command.coberturaId());
			}

			// Caso y plan: de esta persona, vigentes y con esta prestacion. Las sesiones que le quedan
			// al plan se miran bajo el lock, porque ahi si se leen turnos.
			preparacion.contexto = contextoClinico.resolver(
					organizationId, command.personaId(), ofertaId, command.casoId(), command.planId());

			// La mitad de la revalidacion que no lee turnos: habilitacion y franja del profesional,
			// u horario de la sede. Ver RevalidadorDeSlot#prevalidar.
			preparacion.previa = revalidador.prevalidar(new RevalidadorDeSlot.Pedido(
					organizationId, consultorioId, sede, oferta, inicio, fin,
					command.profesionalId(), null));
		} catch (OfertaNoAgendableException | PersonaNotAccessibleException
				| PersonaSinPerfilPacienteException | CoberturaNoAccesibleException
				| ContextoClinicoInvalidoException | SlotNoDisponibleException rechazo) {
			preparacion.rechazo = rechazo;
			return preparacion;
		}

		preparacion.contacto = avisos.contactoDe(organizationId, command.personaId());
		return preparacion;
	}

	private ResultadoDeReserva reservarBajoLock(OperatingActor actor,
			long organizationId, ConsultorioSnapshot sede, long consultorioId, long ofertaId,
			ReservaCommand command) {

		// PASO 0. Antes del lock: no lee turnos. Ver la cabecera.
		Preparacion preparacion = preparar(organizationId, sede, consultorioId, ofertaId, command);

		// PASO 1. Antes de leer un solo turno. Ver la cabecera.
		BloqueoDeAgenda.tomarParaOcupar(agendas, consultorios, organizationId, consultorioId);

		// PASO 2. Bajo el lock, para que dos peticiones con la misma clave no creen dos turnos.
		// Fuera del lock las dos leerian "no existe" antes de que ninguna inserte.
		Optional<Turno> yaCreado = command.idempotencyKey() == null
				? Optional.empty()
				: turnos.findByIdempotencyKey(organizationId, command.idempotencyKey());
		if (yaCreado.isPresent()) {
			return new ResultadoDeReserva(
					resolverReintento(yaCreado.get(), command, consultorioId, ofertaId), false);
		}

		// PASO 3. Los rechazos del paso 0, en el orden en que se evaluaban bajo el lock.
		OfertaSnapshot oferta = preparacion.oferta;
		if (oferta == null) {
			throw new OfertaNotAccessibleException(ofertaId);
		}

		Instant inicio = command.inicio();
		Instant fin = preparacion.fin;

		// Defecto encontrado en E-3. Reprogramar ya rechazaba un destino pasado y la reserva no: el
		// motor de 05.01 dibuja tambien los slots de hoy que ya pasaron, y el revalidador solo mira
		// que el hueco exista. Un turno reservado en el pasado nace inalterable (DP-04) y un error de
		// fecha solo podia cerrarse marcando AUSENTE a un paciente que nunca falto. Va DESPUES de la
		// idempotencia: el reintento de un turno ya creado cuya hora paso sigue devolviendo ese turno.
		if (!inicio.isAfter(Instant.now())) {
			throw new SlotNoDisponibleException("el horario ya paso: un turno se reserva hacia adelante");
		}

		// Vigencia de la oferta, paciente, cobertura y la mitad de la revalidacion sin turnos.
		if (preparacion.rechazo != null) {
			throw preparacion.rechazo;
		}

		// PASO 4. Los mismos controles que aplica una reprogramacion, y en el mismo lugar: las dos
		// son escrituras de agenda. Ver RevalidadorDeSlot.
		RevalidadorDeSlot.Asignacion asignacion = revalidador.revalidarBajoLock(
				new RevalidadorDeSlot.Pedido(
						organizationId, consultorioId, sede, oferta, inicio, fin,
						command.profesionalId(), null, command.espacioId(), command.esSobreturno()),
				preparacion.previa);

		// Bajo el lock y despues del lugar: dos programaciones simultaneas del mismo plan se
		// serializan aca, y un horario ocupado se informa antes que un plan sin sesiones.
		if (preparacion.contexto.plan() != null) {
			// Se relee aca: lo realizado del paso 0 es de antes del lock, y una sesion del plan que cierra
			// mientras se espera deja de contar como programada sin haberse contado como realizada.
			ContextoClinicoDeTurno.Resuelto vigente = contextoClinico.resolver(
					organizationId, command.personaId(), ofertaId, command.casoId(), command.planId());
			sesionesDelPlan.exigirDisponibles(organizationId, vigente.plan(), ofertaId, 1);
		}

		Instant ahora = Instant.now();
		Turno nuevo = conCobertura(new Turno(
				organizationId, consultorioId, ofertaId, command.personaId(),
				asignacion.profesionalId(), asignacion.espacioId(), inicio, fin,
				actor.accountId(), ahora,
				command.idempotencyKey(),
				command.idempotencyKey() == null ? null : command.huella(consultorioId, ofertaId)),
				command.coberturaId());
		if (preparacion.contexto.casoId() != null) {
			nuevo.asociarContexto(preparacion.contexto.casoId(), preparacion.contexto.planId());
		}
		if (command.origen() != null) {
			nuevo.declararOrigen(command.origen());
		}
		if (command.esSobreturno()) {
			nuevo.autorizarSobreturno(command.sobreturnoMotivo(), actor.accountId());
		}
		Turno turno = turnos.save(nuevo);

		eventos.registrar(TurnoEvento.de(
				turno, TipoEventoTurno.RESERVA, null,
				turno.isSobreturno() ? motivoDeEventoDeSobreturno(turno.getSobreturnoMotivo()) : null,
				actor.accountId(), ahora));
		if (turno.isSobreturno()) {
			auditTrail.record(new AuditEntry(
					organizationId, consultorioId, actor.accountId(), "TURNO_SOBRETURNO", "Turno", turno.getId(),
					null, turno.getEstado().name(),
					Map.of("personaId", String.valueOf(turno.getPersonaId()),
							"inicio", String.valueOf(turno.getInicio())),
					turno.getSobreturnoMotivo(), null, ahora));
		}
		// RF-M26-002. En esta misma transaccion: si la reserva hace rollback, el aviso tambien.
		// El reintento idempotente de arriba retorna antes y no encola de nuevo.
		avisos.avisarReserva(turno, sede, oferta.nombreComercial(), preparacion.contacto);

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

	/**
	 * Asocia un turno vivo a un caso y un plan, o se los quita: la primera consulta que ya tiene caso,
	 * o el turno que se dio sin pensar en el plan. <b>Bajo el lock de la sede</b>, aunque no ocupe nada:
	 * gasta una sesion del plan, y dos pedidos simultaneos no pueden gastar la misma.
	 */
	public TurnoView asociarContexto(
			OperatingActor actor, long consultorioId, long turnoId, Long casoId, Long planId,
			long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);
		iniciador.asegurar(organizationId, consultorioId);
		return transaccionDeReserva.execute(estado -> {
			BloqueoDeAgenda.tomarParaOcupar(agendas, consultorios, organizationId, consultorioId);
			return asociarContextoBajoLock(actor, organizationId, consultorioId, turnoId, casoId, planId, expectedVersion);
		});
	}

	private TurnoView asociarContextoBajoLock(
			OperatingActor actor, long organizationId, long consultorioId, long turnoId, Long casoId, Long planId,
			long expectedVersion) {

		Turno turno = turnos.findByIdInScope(organizationId, consultorioId, turnoId)
				.orElseThrow(() -> new TurnoNotAccessibleException(turnoId));
		if (turno.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"El turno " + turnoId + " cambio desde que se leyo");
		}
		ContextoClinicoDeTurno.Resuelto contexto = contextoClinico.resolver(
				organizationId, turno.getPersonaId(), turno.getOfertaId(), casoId, planId);
		if (contexto.plan() != null && !contexto.planId().equals(turno.getPlanId())) {
			sesionesDelPlan.exigirDisponibles(organizationId, contexto.plan(), turno.getOfertaId(), 1);
		}

		EstadoTurno anterior = turno.getEstado();
		Instant ahora = Instant.now();
		turno.asociarContexto(contexto.casoId(), contexto.planId());
		Turno guardado = turnos.saveAndFlush(turno);
		eventos.registrar(TurnoEvento.de(
				guardado, TipoEventoTurno.CAMBIO_DE_CONTEXTO, anterior, null, actor.accountId(), ahora));
		return TurnoView.de(guardado);
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

	private void exigirSobreturno(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.TURNO_SOBRETURNO,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}

	private static Turno conCobertura(Turno turno, Long coberturaId) {
		if (coberturaId != null) {
			turno.elegirCobertura(coberturaId);
		}
		return turno;
	}
}
