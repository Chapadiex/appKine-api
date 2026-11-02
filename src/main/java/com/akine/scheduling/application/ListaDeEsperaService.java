package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.person.spi.ContactoDePersona;
import com.akine.person.spi.ContactoDirectory;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.scheduling.domain.EntradaDeListaDeEspera;
import com.akine.scheduling.domain.EstadoListaDeEspera;
import com.akine.scheduling.domain.OrigenDeTurno;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.EntradaDeEsperaNotAccessibleException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.ListaDeEsperaRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.domain.OrigenDeCancelacion;
import com.akine.scheduling.domain.exception.EntradaDeEsperaCerradaException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Lista de espera de turnos (TURNO.md §20, TURNO - Tecnico §31 y §32).
 *
 * <p>No ocupa la agenda ni toma su lock: anotarse no reserva nada. <b>Asignar crea un turno comun</b>,
 * por {@link TurnoService#reservar}, con todas las validaciones de cualquier otro: que alguien este
 * en la lista no le da un lugar que no existe.
 *
 * <p>Permisos: {@code turno:read} para ver y {@code turno:manage} para operar. La especificacion sugiere
 * un {@code WAITING_LIST_MANAGE} propio, y hoy lo tendrian exactamente los mismos roles que dan turnos;
 * se separa el dia que eso deje de ser cierto.
 */
@Service
public class ListaDeEsperaService {

	private static final String ENTIDAD = "ListaDeEspera";

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ListaDeEsperaService.class);

	private final ListaDeEsperaRepositoryPort entradas;
	private final CicloDeTurnoService ciclo;
	private final TurnoRepositoryPort turnos;
	private final TurnoService turnoService;
	private final ContextoClinicoDeTurno contextoClinico;
	private final ConsultorioDirectory consultorios;
	private final PacienteDirectory pacientes;
	private final ContactoDirectory contactos;
	private final OfertaDirectory ofertas;
	private final NombresDeAgenda nombres;
	private final SoporteEnAgenda soporte;
	private final AuditTrail auditTrail;
	private final TransactionTemplate transaccion;

	public ListaDeEsperaService(
			ListaDeEsperaRepositoryPort entradas,
			TurnoRepositoryPort turnos,
			TurnoService turnoService,
			CicloDeTurnoService ciclo,
			ContextoClinicoDeTurno contextoClinico,
			ConsultorioDirectory consultorios,
			PacienteDirectory pacientes,
			ContactoDirectory contactos,
			OfertaDirectory ofertas,
			NombresDeAgenda nombres,
			SoporteEnAgenda soporte,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager) {

		this.entradas = entradas;
		this.ciclo = ciclo;
		this.turnos = turnos;
		this.turnoService = turnoService;
		this.contextoClinico = contextoClinico;
		this.consultorios = consultorios;
		this.pacientes = pacientes;
		this.contactos = contactos;
		this.ofertas = ofertas;
		this.nombres = nombres;
		this.soporte = soporte;
		this.auditTrail = auditTrail;
		this.transaccion = new TransactionTemplate(transactionManager);
	}

	@Transactional
	public EntradaDeEsperaView anotar(
			OperatingActor actor, long consultorioId, AltaEnListaDeEsperaCommand command) {

		long organizationId = exigirContexto(actor);
		exigirSede(organizationId, consultorioId);
		soporte.exigir(actor, PermissionCodes.TURNO_MANAGE, organizationId, consultorioId);
		ofertas.find(organizationId, consultorioId, command.ofertaId())
				.orElseThrow(() -> new OfertaNotAccessibleException(command.ofertaId()));
		PacienteSnapshot paciente = pacientes.find(organizationId, command.personaId())
				.orElseThrow(() -> new PersonaNotAccessibleException(command.personaId()));
		if (!paciente.activa() || !paciente.esPacienteVigente()) {
			throw new PersonaSinPerfilPacienteException(command.personaId());
		}
		ContextoClinicoDeTurno.Resuelto contexto = contextoClinico.resolver(
				organizationId, command.personaId(), command.ofertaId(), command.casoId(), command.planId());

		Instant ahora = Instant.now();
		EntradaDeListaDeEspera entrada = entradas.save(new EntradaDeListaDeEspera(
				organizationId, consultorioId, command.personaId(), command.ofertaId(), command.profesionalId(),
				contexto.casoId(), contexto.planId(), command.fechaDesde(), command.fechaHasta(), command.dias(),
				command.horaDesde(), command.horaHasta(), command.prioridad(), command.notas(),
				actor.accountId(), ahora));
		auditar(actor, entrada, "LISTA_DE_ESPERA_ALTA", null, null, ahora);
		return componer(organizationId, consultorioId, List.of(entrada)).getFirst();
	}

	@Transactional(readOnly = true)
	public EntradasDeEsperaPagina listar(
			OperatingActor actor, long consultorioId, EstadoListaDeEspera estado, Long ofertaId,
			Long personaId, int pagina, int tamano) {

		long organizationId = exigirContexto(actor);
		exigirSede(organizationId, consultorioId);
		soporte.exigir(actor, PermissionCodes.TURNO_READ, organizationId, consultorioId);
		long total = entradas.contar(organizationId, consultorioId, estado, ofertaId, personaId);
		List<EntradaDeListaDeEspera> lote = total == 0 ? List.of()
				: entradas.listar(organizationId, consultorioId, estado, ofertaId, personaId, pagina, tamano);
		return new EntradasDeEsperaPagina(componer(organizationId, consultorioId, lote), total);
	}

	/**
	 * Quienes esperan un lugar como ese, los mas urgentes primero (TURNO - Tecnico §32). Sirve para un
	 * horario que se libero —el del turno recien cancelado— o para uno cualquiera de la agenda.
	 *
	 * @param turnoId si viene, el horario sale de ese turno y lo demas se ignora
	 */
	@Transactional(readOnly = true)
	public List<EntradaDeEsperaView> coincidencias(
			OperatingActor actor, long consultorioId, Long turnoId, Long ofertaId, Instant inicio, Long profesionalId) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSede(organizationId, consultorioId);
		soporte.exigir(actor, PermissionCodes.TURNO_READ, organizationId, consultorioId);

		long oferta;
		Instant desde;
		Instant hasta;
		Long profesional;
		if (turnoId != null) {
			Turno turno = turnos.findByIdInScope(organizationId, consultorioId, turnoId)
					.orElseThrow(() -> new TurnoNotAccessibleException(turnoId));
			oferta = turno.getOfertaId();
			desde = turno.getInicio();
			hasta = turno.getFin();
			profesional = turno.getProfesionalMembershipId();
		} else {
			if (ofertaId == null || inicio == null) {
				throw new IllegalArgumentException("Hace falta un turno, o una oferta y un horario");
			}
			OfertaSnapshot snapshot = ofertas.find(organizationId, consultorioId, ofertaId)
					.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));
			oferta = ofertaId;
			desde = inicio;
			hasta = inicio.plusSeconds(snapshot.duracionCon(
					ofertas.duracionesPropias(organizationId, consultorioId, ofertaId), profesionalId) * 60L);
			profesional = profesionalId;
		}

		ZoneId zona = ZoneId.of(sede.timezone());
		LocalDate fecha = desde.atZone(zona).toLocalDate();
		LocalTime horaInicio = desde.atZone(zona).toLocalTime();
		LocalTime horaFin = hasta.atZone(zona).toLocalTime();
		List<EntradaDeListaDeEspera> compatibles = entradas
				.findActivasDeLaOferta(organizationId, consultorioId, oferta).stream()
				.filter(entrada -> entrada.admite(oferta, profesional, fecha, horaInicio, horaFin))
				.sorted(Comparator
						.comparing((EntradaDeListaDeEspera entrada) -> entrada.getPrioridad().ordinal()).reversed()
						.thenComparing(EntradaDeListaDeEspera::getCreadaEn))
				.toList();
		return componer(organizationId, consultorioId, compatibles);
	}

	/**
	 * Le da el turno. La reserva corre primero y en su propia transaccion, porque es la que serializa la
	 * agenda; si despues falla marcar la entrada, reintentar con la misma version devuelve el mismo
	 * turno gracias a la clave de idempotencia, en vez de reservarle otro.
	 *
	 * <p>Si lo que cambio es la entrada —otro puesto la descarto o la asigno mientras tanto—, el turno
	 * recien dado se cancela: nadie lo pidio ya, y dejarlo vivo seria un turno sin vinculo con la lista
	 * mientras el operador lee un error.
	 */
	public EntradaDeEsperaView asignar(
			OperatingActor actor, long consultorioId, long entradaId, Instant inicio, Long profesionalId,
			Long espacioId, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSede(organizationId, consultorioId);
		soporte.exigir(actor, PermissionCodes.TURNO_MANAGE, organizationId, consultorioId);

		EntradaDeListaDeEspera leida = exigirEntrada(organizationId, consultorioId, entradaId);
		exigirVersion(leida, expectedVersion);
		leida.exigirActiva();

		var reserva = turnoService.reservar(actor, consultorioId, leida.getOfertaId(), new ReservaCommand(
				leida.getPersonaId(), inicio, profesionalId,
				"lista-de-espera:" + entradaId + ":" + expectedVersion + ":" + inicio.getEpochSecond(), null, espacioId,
				leida.getCasoId(), leida.getPlanId(), OrigenDeTurno.LISTA_DE_ESPERA, null));

		try {
			return transaccion.execute(estado -> {
				EntradaDeListaDeEspera entrada = exigirEntrada(organizationId, consultorioId, entradaId);
				exigirVersion(entrada, expectedVersion);
				Instant ahora = Instant.now();
				entrada.asignar(reserva.turno().id(), actor.accountId(), ahora);
				EntradaDeListaDeEspera guardada = entradas.saveAndFlush(entrada);
				auditar(actor, guardada, "LISTA_DE_ESPERA_ASIGNADA", EstadoListaDeEspera.ACTIVA, null, ahora);
				return componer(organizationId, consultorioId, List.of(guardada)).getFirst();
			});
		} catch (OptimisticLockingFailureException | EntradaDeEsperaCerradaException cambio) {
			// Solo si lo creo este pedido. Con la clave repetida —el doble click— el turno es el del
			// pedido que gano, que ya marco la entrada: cancelarlo la dejaria ASIGNADA a un turno cancelado.
			if (reserva.creado()) {
				try {
					ciclo.cancelar(actor, consultorioId, reserva.turno().id(),
							"La entrada de la lista de espera cambio mientras se asignaba",
							OrigenDeCancelacion.INSTITUCION, reserva.turno().version());
				} catch (RuntimeException sinCancelar) {
					// Lo que se le contesta sigue siendo el conflicto; el turno que quedo vivo, al log.
					log.error("Turno {} de la lista de espera {} no se pudo cancelar", reserva.turno().id(), entradaId, sinCancelar);
					cambio.addSuppressed(sinCancelar);
				}
			}
			throw cambio;
		}
	}

	@Transactional
	public EntradaDeEsperaView descartar(
			OperatingActor actor, long consultorioId, long entradaId, String motivo, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSede(organizationId, consultorioId);
		soporte.exigir(actor, PermissionCodes.TURNO_MANAGE, organizationId, consultorioId);

		EntradaDeListaDeEspera entrada = exigirEntrada(organizationId, consultorioId, entradaId);
		exigirVersion(entrada, expectedVersion);
		Instant ahora = Instant.now();
		entrada.descartar(motivo, actor.accountId(), ahora);
		EntradaDeListaDeEspera guardada = entradas.saveAndFlush(entrada);
		auditar(actor, guardada, "LISTA_DE_ESPERA_DESCARTADA", EstadoListaDeEspera.ACTIVA, motivo, ahora);
		return componer(organizationId, consultorioId, List.of(guardada)).getFirst();
	}

	private List<EntradaDeEsperaView> componer(
			long organizationId, long consultorioId, List<EntradaDeListaDeEspera> lote) {

		if (lote.isEmpty()) {
			return List.of();
		}
		List<Long> personas = lote.stream().map(EntradaDeListaDeEspera::getPersonaId).distinct().toList();
		Map<Long, PacienteSnapshot> pacienteDe = pacientes.findAll(organizationId, personas);
		Map<Long, ContactoDePersona> contactoDe = contactos.findAll(organizationId, personas);
		Map<Long, OfertaSnapshot> ofertaDe = ofertas.findTodas(organizationId, consultorioId,
				lote.stream().map(EntradaDeListaDeEspera::getOfertaId).distinct().toList());
		Map<Long, String> profesionalDe = nombres.profesionales(organizationId,
				lote.stream().map(EntradaDeListaDeEspera::getProfesionalMembershipId)
						.filter(Objects::nonNull).collect(Collectors.toSet()));
		return lote.stream().map(entrada -> {
			PacienteSnapshot paciente = pacienteDe.get(entrada.getPersonaId());
			ContactoDePersona contacto = contactoDe.get(entrada.getPersonaId());
			OfertaSnapshot oferta = ofertaDe.get(entrada.getOfertaId());
			return new EntradaDeEsperaView(
					entrada.getId(),
					entrada.getPersonaId(),
					RecepcionService.nombreDe(paciente),
					RecepcionService.documentoDe(paciente),
					contacto == null ? null : contacto.telefono(),
					entrada.getOfertaId(),
					oferta == null ? "(oferta no disponible)" : oferta.nombreComercial(),
					entrada.getProfesionalMembershipId(),
					entrada.getProfesionalMembershipId() == null ? null : profesionalDe.get(entrada.getProfesionalMembershipId()),
					entrada.getCasoId(),
					entrada.getPlanId(),
					entrada.getFechaDesde(),
					entrada.getFechaHasta(),
					entrada.dias().stream().map(DayOfWeek::getValue).sorted().toList(),
					entrada.getHoraDesde(),
					entrada.getHoraHasta(),
					entrada.getPrioridad().name(),
					entrada.getNotas(),
					entrada.getEstado().name(),
					entrada.getTurnoId(),
					entrada.getMotivoCierre(),
					entrada.getCreadaEn(),
					entrada.getVersion());
		}).toList();
	}

	private void auditar(
			OperatingActor actor, EntradaDeListaDeEspera entrada, String evento,
			EstadoListaDeEspera anterior, String motivo, Instant ahora) {

		auditTrail.record(new AuditEntry(
				entrada.getOrganizationId(), entrada.getConsultorioId(), actor.accountId(), evento, ENTIDAD,
				entrada.getId(), anterior == null ? null : anterior.name(), entrada.getEstado().name(),
				Map.of("personaId", String.valueOf(entrada.getPersonaId()),
						"ofertaId", String.valueOf(entrada.getOfertaId())),
				motivo, null, ahora));
	}

	private EntradaDeListaDeEspera exigirEntrada(long organizationId, long consultorioId, long entradaId) {
		return entradas.findByIdInScope(organizationId, consultorioId, entradaId)
				.orElseThrow(() -> new EntradaDeEsperaNotAccessibleException(entradaId));
	}

	private static void exigirVersion(EntradaDeListaDeEspera entrada, long expectedVersion) {
		if (entrada.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"La entrada " + entrada.getId() + " cambio desde que se leyo");
		}
	}

	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La lista de espera requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private ConsultorioSnapshot exigirSede(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}
}
