package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.platform.spi.listado.OrdenDeListado;
import com.akine.platform.spi.listado.TextoDeBusqueda;
import com.akine.platform.spi.transaccion.FilaLockPrevia;
import com.akine.scheduling.domain.AlcanceDeSerie;
import com.akine.scheduling.domain.EstadoDeSerie;
import com.akine.scheduling.domain.OcurrenciaOmitida;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.TipoEventoTurno;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.TurnoEvento;
import com.akine.scheduling.domain.TurnoSerie;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.OcurrenciaSinLugarException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SerieNotAccessibleException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import com.akine.scheduling.domain.exception.CantidadConfirmadaDesactualizadaException;
import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.OcurrenciaOmitidaRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoSerieRepositoryPort;
import com.akine.scheduling.spi.AtencionProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Series de turnos: alta, lectura y operaciones con alcance (AKINE E-3, DP-04, ADR-0011).
 *
 * <h2>La serie es la regla; los turnos son la verdad</h2>
 *
 * <p>Cada ocurrencia es un {@link Turno} pleno, con estado, version e historial propios. Esta clase
 * los crea juntos y los opera juntos, pero <b>nunca</b> les cambia la identidad: cancelar por alcance
 * es la cancelacion de 05.03 aplicada a N turnos, y reprogramar por alcance los MUEVE, no los
 * reemplaza. Las dos transiciones viven en {@link CicloDeTurnoService} y aca se reusan, para que no
 * haya dos copias que diverjan.
 *
 * <h2>Todo o nada</h2>
 *
 * <p>RF-M12-002: si la operacion no admite modo parcial, no deja cambios parciales. Si una sola
 * ocurrencia no tiene lugar, la transaccion entera hace rollback y el 409 nombra esa ocurrencia
 * ({@link OcurrenciaSinLugarException}).
 *
 * <p><b>Salvo los feriados</b> (U-22, DP-43): una ocurrencia que cae en un dia que la sede cierra
 * por feriado no se reserva y queda registrada como omitida ({@link OcurrenciaOmitida}), en vez de
 * hacer fallar la serie entera. Cualquier otra causa sigue siendo todo o nada.
 *
 * <h2>Concurrencia</h2>
 *
 * <p>Las tres escrituras siguen el orden de 05.02 sin un solo cambio: la fila de {@code agenda_sede}
 * asegurada en su propia transaccion, {@code FOR UPDATE} antes de leer un solo turno, y
 * {@code READ_COMMITTED} para que la revalidacion vea lo que otra transaccion acaba de commitear.
 * Cancelar por alcance <b>tambien</b> toma el lock, a diferencia de la cancelacion suelta: el
 * conjunto afectado tiene que ser estable entre el calculo y la escritura —es lo que promete la
 * cantidad confirmada— y lo unico que puede meter o sacar un turno del alcance es una
 * reprogramacion, que toma ese lock.
 */
@Service
public class SerieDeTurnosService {

	private static final Logger log = LoggerFactory.getLogger(SerieDeTurnosService.class);

	private static final String ENTIDAD = "TurnoSerie";

	private final TurnoSerieRepositoryPort series;
	private final TurnoRepositoryPort turnos;
	private final TurnoEventoRepositoryPort eventos;
	private final AgendaSedeRepositoryPort agendas;
	private final OfertaDirectory ofertas;
	private final PacienteDirectory pacientes;
	private final ConsultorioDirectory consultorios;
	private final SoporteEnAgenda soporte;
	private final AgendaSedeIniciador iniciador;
	private final RevalidadorDeSlot revalidador;
	private final AtencionProbe atenciones;
	private final CicloDeTurnoService ciclo;
	private final AuditTrail auditTrail;
	private final AvisosDeTurno avisos;
	private final RegistroDeRecepcion recepciones;
	private final OcurrenciaOmitidaRepositoryPort omisiones;
	private final TransactionTemplate transaccionReadCommitted;
	private final ConsultorioMembershipDirectory membresias;
	private final AccountIdentityDirectory identidades;

	public SerieDeTurnosService(
			TurnoSerieRepositoryPort series,
			TurnoRepositoryPort turnos,
			TurnoEventoRepositoryPort eventos,
			AgendaSedeRepositoryPort agendas,
			OfertaDirectory ofertas,
			PacienteDirectory pacientes,
			ConsultorioDirectory consultorios,
			SoporteEnAgenda soporte,
			AgendaSedeIniciador iniciador,
			RevalidadorDeSlot revalidador,
			AtencionProbe atenciones,
			CicloDeTurnoService ciclo,
			AuditTrail auditTrail,
			AvisosDeTurno avisos,
			RegistroDeRecepcion recepciones,
			OcurrenciaOmitidaRepositoryPort omisiones,
			PlatformTransactionManager transactionManager,
			ConsultorioMembershipDirectory membresias,
			AccountIdentityDirectory identidades) {

		this.membresias = membresias;
		this.identidades = identidades;

		this.series = series;
		this.turnos = turnos;
		this.eventos = eventos;
		this.agendas = agendas;
		this.ofertas = ofertas;
		this.pacientes = pacientes;
		this.consultorios = consultorios;
		this.soporte = soporte;
		this.iniciador = iniciador;
		this.revalidador = revalidador;
		this.atenciones = atenciones;
		this.ciclo = ciclo;
		this.auditTrail = auditTrail;
		this.avisos = avisos;
		this.recepciones = recepciones;
		this.omisiones = omisiones;
		this.transaccionReadCommitted = FilaLockPrevia.transaccionDeNegocio(
				transactionManager, Isolation.READ_COMMITTED);
	}

	// =================================================================================
	// Alta — RF-M12-002 sobre una regla (DP-04)
	// =================================================================================

	/**
	 * Crea la serie y reserva TODAS sus ocurrencias, o ninguna, salvo las que caen en un feriado que
	 * la sede cierra: esas se omiten y vuelven en {@link SerieView#omitidas()} (U-22).
	 *
	 * @throws IllegalArgumentException        la regla no produce turnos, produce demasiados, o la
	 *                                         primera ocurrencia ya paso (400)
	 * @throws OcurrenciaSinLugarException     una ocurrencia no tiene lugar: no se creo nada (409)
	 * @throws IdempotencyKeyConflictException misma clave, otro pedido (409)
	 */
	public ResultadoDeSerie crear(OperatingActor actor, long consultorioId, AltaDeSerieCommand command) {
		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirPermiso(actor, organizationId, consultorioId, PermissionCodes.TURNO_MANAGE);

		iniciador.asegurar(organizationId, consultorioId);

		return transaccionReadCommitted.execute(estado -> crearEnTransaccion(
				actor, consultorioId, command, organizationId, sede));
	}

	private ResultadoDeSerie crearEnTransaccion(
			OperatingActor actor, long consultorioId, AltaDeSerieCommand command,
			long organizationId, ConsultorioSnapshot sede) {

		BloqueoDeAgenda.tomarParaOcupar(agendas, consultorios, organizationId, consultorioId);

		// Bajo el lock, como en la reserva: fuera de el dos peticiones con la misma clave leerian
		// "no existe" las dos.
		if (command.idempotencyKey() != null) {
			Optional<TurnoSerie> yaCreada = series.findByIdempotencyKey(organizationId, command.idempotencyKey());
			if (yaCreada.isPresent()) {
				if (!command.huella(consultorioId).equals(yaCreada.get().getRequestHash())) {
					throw new IdempotencyKeyConflictException(command.idempotencyKey());
				}
				return new ResultadoDeSerie(vista(yaCreada.get()), false);
			}
		}

		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, command.ofertaId())
				.orElseThrow(() -> new OfertaNotAccessibleException(command.ofertaId()));
		exigirPacienteVigente(organizationId, command.personaId());

		ZoneId zona = ZoneId.of(sede.timezone());
		Instant ahora = Instant.now();
		List<Instant> inicios = command.regla().ocurrencias().stream()
				.map(local -> local.atZone(zona).toInstant())
				.toList();
		if (!inicios.getFirst().isAfter(ahora)) {
			throw new IllegalArgumentException(
					"La primera ocurrencia de la serie ya paso: el pasado no se reserva");
		}

		// U-22: las ocurrencias que caen en un dia que la sede cierra por feriado se OMITEN, en vez
		// de hacer fallar la serie entera. Todo o nada sigue valiendo para cualquier otra causa.
		Set<LocalDate> feriados = revalidador.feriadosQueCierran(
				organizationId, sede, oferta, command.profesionalId(),
				fechaLocal(inicios.getFirst(), zona), fechaLocal(inicios.getLast(), zona).plusDays(1));
		List<Instant> aReservar = inicios.stream()
				.filter(inicio -> !feriados.contains(fechaLocal(inicio, zona)))
				.toList();
		if (aReservar.isEmpty()) {
			// Una serie sin un solo turno no es una serie: no se crea nada, como en todo o nada.
			throw new OcurrenciaSinLugarException(inicios.getFirst(), new SlotNoDisponibleException(
					"todas las ocurrencias de la serie caen en dias que la sede cierra por feriado"));
		}

		TurnoSerie serie = series.save(new TurnoSerie(
				organizationId, consultorioId, command.ofertaId(), command.personaId(),
				command.profesionalId(), command.regla(), sede.timezone(), aReservar.size(),
				actor.accountId(), ahora, command.idempotencyKey(),
				command.idempotencyKey() == null ? null : command.huella(consultorioId)));

		List<OcurrenciaOmitida> omitidas = inicios.stream()
				.filter(inicio -> feriados.contains(fechaLocal(inicio, zona)))
				.map(inicio -> omisiones.registrar(new OcurrenciaOmitida(
						serie, inicio, fechaLocal(inicio, zona), OcurrenciaOmitida.Motivo.FERIADO)))
				.toList();

		List<Turno> creados = new ArrayList<>();
		for (Instant inicio : aReservar) {
			Instant fin = inicio.plusSeconds(oferta.duracionMinutos() * 60L);
			RevalidadorDeSlot.Asignacion asignacion = sinLugarNombraLaOcurrencia(inicio, () -> {
				exigirVigencia(oferta, inicio, zona);
				return revalidador.revalidar(new RevalidadorDeSlot.Pedido(
						organizationId, consultorioId, sede, oferta, inicio, fin,
						command.profesionalId(), null));
			});

			Turno turno = new Turno(
					organizationId, consultorioId, command.ofertaId(), command.personaId(),
					asignacion.profesionalId(), asignacion.espacioId(), inicio, fin,
					actor.accountId(), ahora, null, null);
			turno.vincularASerie(serie.getId());
			// save y no saveAndFlush alcanza: con IDENTITY el INSERT sale ya, y la revalidacion de
			// la ocurrencia siguiente lo ve.
			Turno creado = turnos.save(turno);
			eventos.registrar(TurnoEvento.de(
					creado, TipoEventoTurno.RESERVA, null, null, actor.accountId(), ahora));
			// RF-M26-002, uno por ocurrencia: ver el diseno de E-3, §7.
			avisos.avisarReserva(creado, sede, oferta.nombreComercial());
			creados.add(creado);
		}

		Map<String, String> detalles = detalles(
				"ofertaId", String.valueOf(command.ofertaId()),
				"personaId", String.valueOf(command.personaId()),
				"cantidad", String.valueOf(creados.size()),
				"turnoIds", ids(creados));
		if (!omitidas.isEmpty()) {
			detalles.put("omitidasPorFeriado", omitidas.stream()
					.map(omitida -> omitida.getFecha().toString())
					.collect(Collectors.joining(",")));
		}
		auditar(actor, serie, "TURNO_SERIE_CREADA", null, detalles, ahora);

		log.info("Serie de turnos creada: serieId={} consultorioId={} ofertaId={} ocurrencias={} omitidas={}",
				serie.getId(), consultorioId, command.ofertaId(), creados.size(), omitidas.size());
		return new ResultadoDeSerie(SerieView.de(serie,
				creados.stream().map(TurnoView::de).toList(),
				omitidas.stream().map(OcurrenciaOmitidaView::de).toList()), true);
	}

	private static LocalDate fechaLocal(Instant inicio, ZoneId zona) {
		return inicio.atZone(zona).toLocalDate();
	}

	// =================================================================================
	// Lectura
	// =================================================================================

	@Transactional(readOnly = true)
	public SerieView ver(OperatingActor actor, long consultorioId, long serieId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirPermiso(actor, organizationId, consultorioId, PermissionCodes.TURNO_READ);
		return vista(exigirSerie(organizationId, consultorioId, serieId));
	}

	/**
	 * La bandeja de series de una sede, mas nuevas primero (AKINE E-8).
	 *
	 * <p>Cuatro consultas por pagina, ninguna por fila: las series (recortadas en la base), el
	 * total, los turnos de las series de la pagina y los pacientes. Las ofertas se piden una vez por
	 * oferta distinta. El estado es derivado de los turnos ({@link EstadoDeSerie}) y se calcula con
	 * el mismo instante con el que se filtro.
	 *
	 * <p>Una persona de otro tenant en {@code personaId} no es un error: el filtro va con la
	 * organizacion del contexto y la pagina sale vacia, como cualquier filtro que no encuentra nada.
	 *
	 * @throws ConsultorioNoAccesibleException la sede no existe o es de otro tenant (404)
	 */
	@Transactional(readOnly = true)
	public SeriePagina listar(
			OperatingActor actor, long consultorioId, Long personaId, EstadoDeSerie estado,
			int pagina, int tamano) {
		return listar(actor, consultorioId, personaId, estado, null, Optional.empty(), pagina, tamano);
	}

	/** DP-45: los campos por los que se ordena la bandeja. */
	public static final Set<String> ORDEN_DE_SERIES = Set.of("creadaEn", "fechaDesde", "fechaHasta");

	/**
	 * La bandeja con busqueda y orden (DP-45, punto 1).
	 *
	 * <p>{@code q} busca "contiene", sin mayusculas ni acentos, en el apellido, el nombre y el
	 * documento del paciente, en el nombre comercial de la oferta y en el nombre del profesional.
	 * Los tres viven en otros modulos ({@code person}, {@code offering}, {@code identity}) y se
	 * preguntan por su {@code spi}: con {@code q} se leen todas las series del filtro, se resuelven
	 * los nombres en lote —una consulta por modulo, no una por fila— y la pagina se corta en
	 * memoria. Sin {@code q} el camino es el de siempre, recortado en la base.
	 */
	@Transactional(readOnly = true)
	public SeriePagina listar(
			OperatingActor actor, long consultorioId, Long personaId, EstadoDeSerie estado,
			String q, Optional<OrdenDeListado> orden, int pagina, int tamano) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirPermiso(actor, organizationId, consultorioId, PermissionCodes.TURNO_READ);

		Instant ahora = Instant.now();
		long total;
		List<TurnoSerie> deLaPagina;
		if (TextoDeBusqueda.hay(q)) {
			List<TurnoSerie> coinciden = coincidenConElTexto(organizationId, consultorioId,
					series.todas(organizationId, consultorioId, personaId, estado, ahora), q);
			total = coinciden.size();
			deLaPagina = orden.map(pedido -> coinciden.stream()
							.sorted(pedido.aplicar(comparadorDeSeries(pedido.campo()),
									Comparator.comparingLong(TurnoSerie::getId)))
							.toList())
					.orElse(coinciden).stream()
					.skip((long) pagina * tamano)
					.limit(tamano)
					.toList();
		} else {
			total = series.contar(organizationId, consultorioId, personaId, estado, ahora);
			if (total == 0 || (long) pagina * tamano >= total) {
				return new SeriePagina(List.of(), total);
			}
			deLaPagina = series.listar(
					organizationId, consultorioId, personaId, estado, ahora, orden, pagina, tamano);
		}
		if (deLaPagina.isEmpty()) {
			return new SeriePagina(List.of(), total);
		}

		Map<Long, List<Turno>> turnosDe = turnos
				.findDeLasSeries(organizationId, deLaPagina.stream().map(TurnoSerie::getId).toList())
				.stream()
				.collect(Collectors.groupingBy(Turno::getSerieId));
		Map<Long, PacienteSnapshot> personas = pacientes.findAll(
				organizationId, deLaPagina.stream().map(TurnoSerie::getPersonaId).distinct().toList());
		Map<Long, String> nombreDeOferta = new HashMap<>();

		List<SerieResumenView> filas = deLaPagina.stream()
				.map(serie -> resumen(serie, turnosDe.getOrDefault(serie.getId(), List.of()),
						personas.get(serie.getPersonaId()),
						nombreDeOferta.computeIfAbsent(serie.getOfertaId(), ofertaId -> ofertas
								.find(organizationId, consultorioId, ofertaId)
								.map(OfertaSnapshot::nombreComercial)
								.orElse("(oferta no disponible)")),
						ahora))
				.toList();
		return new SeriePagina(filas, total);
	}


	/**
	 * DP-45: las series cuyo paciente, oferta o profesional contienen {@code q}. Tres consultas en
	 * lote, una por modulo duenio del nombre, sin importar cuantas series haya.
	 */
	private List<TurnoSerie> coincidenConElTexto(
			long organizationId, long consultorioId, List<TurnoSerie> candidatas, String q) {

		if (candidatas.isEmpty()) {
			return candidatas;
		}
		Map<Long, PacienteSnapshot> personas = pacientes.findAll(organizationId,
				candidatas.stream().map(TurnoSerie::getPersonaId).distinct().toList());
		Map<Long, OfertaSnapshot> ofertasDeLaSede = ofertas.findTodas(organizationId, consultorioId,
				candidatas.stream().map(TurnoSerie::getOfertaId).distinct().toList());
		Map<Long, ConsultorioMembershipSnapshot> profesionales = membresias.findTodas(organizationId,
				candidatas.stream().map(TurnoSerie::getProfesionalMembershipId)
						.filter(java.util.Objects::nonNull).distinct().toList());
		Map<Long, AccountIdentity> cuentas = identidades.identidadesDe(profesionales.values().stream()
				.map(ConsultorioMembershipSnapshot::accountId).distinct().toList());

		return candidatas.stream()
				.filter(serie -> {
					PacienteSnapshot persona = personas.get(serie.getPersonaId());
					OfertaSnapshot oferta = ofertasDeLaSede.get(serie.getOfertaId());
					ConsultorioMembershipSnapshot profesional = serie.getProfesionalMembershipId() == null
							? null : profesionales.get(serie.getProfesionalMembershipId());
					AccountIdentity cuenta = profesional == null ? null : cuentas.get(profesional.accountId());
					return TextoDeBusqueda.algunoContiene(q,
							persona == null ? null : persona.apellido(),
							persona == null ? null : persona.nombre(),
							persona == null ? null : persona.numeroDocumento(),
							oferta == null ? null : oferta.nombreComercial(),
							cuenta == null ? null : cuenta.nombreCompleto());
				})
				.toList();
	}

	/** El comparador ascendente de cada campo de {@link #ORDEN_DE_SERIES}. */
	private static Comparator<TurnoSerie> comparadorDeSeries(String campo) {
		return switch (campo) {
			case "creadaEn" -> Comparator.comparing(TurnoSerie::getCreadaEn,
					Comparator.nullsLast(Comparator.naturalOrder()));
			case "fechaDesde" -> Comparator.comparing((TurnoSerie s) -> s.getRegla().desde(),
					Comparator.nullsLast(Comparator.naturalOrder()));
			case "fechaHasta" -> Comparator.comparing((TurnoSerie s) -> s.getRegla().hasta(),
					Comparator.nullsLast(Comparator.naturalOrder()));
			default -> throw new IllegalStateException("Campo de orden sin comparador: " + campo);
		};
	}
	/**
	 * Una fila de la bandeja. "Pendiente" y el estado son exactamente el predicado del filtro de
	 * estado en la base ({@link EstadoDeSerie#de}, DP-20), con el mismo instante. Si divergieran,
	 * una serie filtrada como VIGENTE o CANCELADA podria mostrarse con otro estado.
	 */
	static SerieResumenView resumen(
			TurnoSerie serie, List<Turno> deLaSerie, PacienteSnapshot paciente, String ofertaNombre,
			Instant ahora) {

		List<Turno> pendientes = deLaSerie.stream()
				.filter(turno -> EstadoDeSerie.esPendiente(turno, ahora))
				.toList();
		Instant proximo = pendientes.stream().map(Turno::getInicio).min(Comparator.naturalOrder()).orElse(null);
		SerieView regla = SerieView.de(serie, List.of());
		return new SerieResumenView(
				regla.id(), regla.consultorioId(), regla.personaId(),
				RecepcionService.nombreDe(paciente), RecepcionService.documentoDe(paciente),
				regla.ofertaId(), ofertaNombre, regla.profesionalId(), regla.frecuencia(),
				regla.diasSemana(), regla.hora(), regla.fechaDesde(), regla.fechaHasta(),
				regla.cantidad(), regla.timezone(), regla.creadaEn(),
				deLaSerie.size(), pendientes.size(), proximo,
				EstadoDeSerie.de(deLaSerie, ahora));
	}

	/**
	 * Lo que tocaria una operacion con ese alcance, sin tocar nada. Es el insumo de la confirmacion
	 * explicita: la pantalla muestra afectados y omitidos y el operador confirma la cantidad.
	 */
	@Transactional(readOnly = true)
	public AlcanceDeSerieView previsualizar(
			OperatingActor actor, long consultorioId, long serieId, AlcanceDeSerie alcance, Long turnoId) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirPermiso(actor, organizationId, consultorioId, PermissionCodes.TURNO_READ);
		if (alcance == null) {
			throw new IllegalArgumentException("El alcance es obligatorio");
		}
		TurnoSerie serie = exigirSerie(organizationId, consultorioId, serieId);
		SeleccionDeAlcance seleccion = seleccionar(serie, alcance, turnoId, Instant.now());
		return AlcanceDeSerieView.de(serieId, alcance, turnoId, seleccion.afectados(), seleccion.omitidos());
	}

	// =================================================================================
	// Cancelacion con alcance — RF-M12-004, DP-04
	// =================================================================================

	/**
	 * Cancela los turnos pendientes del alcance. Cada uno pasa por la MISMA cancelacion de 05.03:
	 * motivo, actor, baja logica que libera el lugar, evento, auditoria y aviso.
	 *
	 * @throws CantidadConfirmadaDesactualizadaException la cantidad confirmada ya no es la real (409)
	 * @throws TransicionDeTurnoNoPermitidaException no queda ningun turno pendiente en el alcance (409)
	 */
	public AlcanceDeSerieView cancelar(
			OperatingActor actor, long consultorioId, long serieId, OperacionDeSerieCommand command) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirPermiso(actor, organizationId, consultorioId, PermissionCodes.TURNO_MANAGE);

		iniciador.asegurar(organizationId, consultorioId);

		return transaccionReadCommitted.execute(estado -> cancelarEnTransaccion(
				actor, consultorioId, serieId, command, organizationId, sede));
	}

	private AlcanceDeSerieView cancelarEnTransaccion(
			OperatingActor actor, long consultorioId, long serieId,
			OperacionDeSerieCommand command, long organizationId, ConsultorioSnapshot sede) {

		BloqueoDeAgenda.tomar(agendas, organizationId, consultorioId);

		TurnoSerie serie = exigirSerie(organizationId, consultorioId, serieId);
		Instant ahora = Instant.now();
		SeleccionDeAlcance seleccion = seleccionar(serie, command.alcance(), command.turnoId(), ahora);
		exigirConfirmacion(seleccion, command, serie);

		List<Turno> cancelados = seleccion.afectados().stream()
				.map(turno -> ciclo.aplicarCancelacion(actor, sede, turno, command.motivo(), ahora))
				.toList();

		auditarOperacion(actor, serie, "TURNO_SERIE_CANCELADA", command, cancelados, ahora);
		log.info("Serie cancelada por alcance: serieId={} alcance={} turnos={}",
				serieId, command.alcance(), cancelados.size());
		return AlcanceDeSerieView.de(
				serieId, command.alcance(), command.turnoId(), cancelados, seleccion.omitidos());
	}

	// =================================================================================
	// Reprogramacion con alcance — RF-M12-005, RN-M12-003
	// =================================================================================

	/**
	 * Mueve los turnos pendientes del alcance con el mismo desplazamiento que lleva al pivote a su
	 * horario nuevo. Cada uno conserva su id y su historial (05.03: reprogramar MUEVE).
	 *
	 * <p>El desplazamiento se mide en <b>hora local</b>: de "lunes 09:00" a "martes 10:00" es un dia
	 * y una hora, y aplicado a cada turno en hora local no corre una ocurrencia por un cambio de
	 * horario de verano. Un turno que ya se habia movido solo conserva su diferencia.
	 *
	 * @throws OcurrenciaSinLugarException       un destino no tiene lugar: no se movio nada (409)
	 * @throws CantidadConfirmadaDesactualizadaException la cantidad confirmada ya no es la real (409)
	 */
	public AlcanceDeSerieView reprogramar(
			OperatingActor actor, long consultorioId, long serieId, OperacionDeSerieCommand command) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirPermiso(actor, organizationId, consultorioId, PermissionCodes.TURNO_MANAGE);
		if (command.turnoId() == null || command.inicio() == null) {
			throw new IllegalArgumentException(
					"Reprogramar una serie necesita el turno pivote y su horario nuevo");
		}

		iniciador.asegurar(organizationId, consultorioId);

		return transaccionReadCommitted.execute(estado -> reprogramarEnTransaccion(
				actor, consultorioId, serieId, command, organizationId, sede));
	}

	private AlcanceDeSerieView reprogramarEnTransaccion(
			OperatingActor actor, long consultorioId, long serieId,
			OperacionDeSerieCommand command, long organizationId, ConsultorioSnapshot sede) {

		BloqueoDeAgenda.tomarParaOcupar(agendas, consultorios, organizationId, consultorioId);

		TurnoSerie serie = exigirSerie(organizationId, consultorioId, serieId);
		Instant ahora = Instant.now();
		SeleccionDeAlcance seleccion = seleccionar(serie, command.alcance(), command.turnoId(), ahora);
		exigirConfirmacion(seleccion, command, serie);

		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, serie.getOfertaId())
				.orElseThrow(() -> new OfertaNotAccessibleException(serie.getOfertaId()));

		ZoneId zona = ZoneId.of(sede.timezone());
		Turno pivote = pivoteDe(serie, command.turnoId());
		Duration desplazamiento = Duration.between(
				pivote.getInicio().atZone(zona).toLocalDateTime(),
				command.inicio().atZone(zona).toLocalDateTime());
		if (desplazamiento.isZero() && command.profesionalId() == null) {
			throw new IllegalArgumentException("El horario nuevo es el mismo que el actual");
		}

		List<Turno> movidos = new ArrayList<>();
		for (Turno turno : ordenDeProceso(seleccion.afectados(), desplazamiento)) {
			LocalDateTime destinoLocal = turno.getInicio().atZone(zona).toLocalDateTime().plus(desplazamiento);
			Instant destino = destinoLocal.atZone(zona).toInstant();
			Long profesionalId = command.profesionalId() != null
					? command.profesionalId()
					: turno.getProfesionalMembershipId();
			movidos.add(sinLugarNombraLaOcurrencia(destino, () -> ciclo.aplicarReprogramacion(
					actor, sede, oferta, turno, destino, profesionalId, command.motivo(), ahora)));
		}
		movidos.sort(Comparator.comparing(Turno::getInicio).thenComparing(Turno::getId));

		auditarOperacion(actor, serie, "TURNO_SERIE_REPROGRAMADA", command, movidos, ahora);
		log.info("Serie reprogramada por alcance: serieId={} alcance={} turnos={} desplazamiento={}",
				serieId, command.alcance(), movidos.size(), desplazamiento);
		return AlcanceDeSerieView.de(
				serieId, command.alcance(), command.turnoId(), movidos, seleccion.omitidos());
	}

	/**
	 * Hacia adelante se mueve primero el ultimo; hacia atras, primero el primero.
	 *
	 * <p>Mover cuatro lunes una semana para adelante lleva cada turno al lugar que todavia ocupa su
	 * hermano siguiente. En orden cronologico el primero chocaria contra el segundo, que no se movio;
	 * en orden inverso cada destino ya quedo libre cuando le toca.
	 */
	static List<Turno> ordenDeProceso(List<Turno> afectados, Duration desplazamiento) {
		Comparator<Turno> cronologico = Comparator.comparing(Turno::getInicio).thenComparing(Turno::getId);
		return afectados.stream()
				.sorted(desplazamiento.isNegative() ? cronologico : cronologico.reversed())
				.toList();
	}

	// =================================================================================
	// Seleccion y confirmacion
	// =================================================================================

	private SeleccionDeAlcance seleccionar(
			TurnoSerie serie, AlcanceDeSerie alcance, Long turnoId, Instant ahora) {

		List<Turno> deLaSerie = turnos.findDeLaSerie(serie.getOrganizationId(), serie.getId());
		Turno pivote = turnoId == null ? null : deLaSerie.stream()
				.filter(turno -> turno.getId().equals(turnoId))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException(
						"El turno " + turnoId + " no pertenece a la serie " + serie.getId()));
		if (pivote == null && alcance != AlcanceDeSerie.TODA_LA_SERIE) {
			throw new IllegalArgumentException(
					"El alcance " + alcance + " se cuenta desde un turno: falta turnoId");
		}
		return SeleccionDeAlcance.calcular(deLaSerie, alcance, pivote, ahora,
				turno -> atenciones.tieneAtencion(
						turno.getOrganizationId(), turno.getConsultorioId(), turno.getId()),
				// La llegada consta —abierta o RETIRADO (U-21)—: no se cancela ni se mueve por lote.
				turno -> recepciones.llegadaConsta(turno.getOrganizationId(), turno.getId()));
	}

	private Turno pivoteDe(TurnoSerie serie, long turnoId) {
		return turnos.findByIdInScope(serie.getOrganizationId(), serie.getConsultorioId(), turnoId)
				.filter(turno -> serie.getId().equals(turno.getSerieId()))
				.orElseThrow(() -> new IllegalArgumentException(
						"El turno " + turnoId + " no pertenece a la serie " + serie.getId()));
	}

	/**
	 * La confirmacion explicita de DP-04, hecha contrato: la cantidad que el operador vio tiene que
	 * ser la que hay bajo el lock. Si alguien movio, cancelo o atendio un turno del alcance entre la
	 * previsualizacion y el click, 409 y no se toca nada.
	 */
	private static void exigirConfirmacion(
			SeleccionDeAlcance seleccion, OperacionDeSerieCommand command, TurnoSerie serie) {

		if (seleccion.afectados().isEmpty()) {
			throw new TransicionDeTurnoNoPermitidaException(command.turnoId(),
					"no queda ningun turno pendiente de la serie " + serie.getId() + " en ese alcance");
		}
		if (seleccion.afectados().size() != command.cantidadConfirmada()) {
			// Conflicto de negocio, no de concurrencia (DP-21): ver la excepcion.
			throw new CantidadConfirmadaDesactualizadaException(serie.getId(),
					String.valueOf(command.alcance()), seleccion.afectados().size(),
					command.cantidadConfirmada());
		}
	}

	// =================================================================================
	// Precondiciones
	// =================================================================================

	/**
	 * Las cuatro causas por las que una ocurrencia no entra se re-lanzan nombrando su instante. Las
	 * demas —un turno con atencion, una transicion imposible— no son "falta de lugar" y pasan tal
	 * cual.
	 */
	private static <T> T sinLugarNombraLaOcurrencia(Instant inicio, java.util.function.Supplier<T> paso) {
		try {
			return paso.get();
		} catch (SlotNoDisponibleException | SlotCompletoException
				| RecursoOcupadoException | OfertaNoAgendableException sinLugar) {
			throw new OcurrenciaSinLugarException(inicio, sinLugar);
		}
	}

	private static void exigirVigencia(OfertaSnapshot oferta, Instant inicio, ZoneId zona) {
		var fecha = inicio.atZone(zona).toLocalDate();
		if (!oferta.vigenteEl(fecha)) {
			throw new OfertaNoAgendableException(oferta.id(), oferta.active()
					? "no esta vigente el " + fecha
					: "esta dada de baja");
		}
	}

	/** RF-M07-010: la serie, como la reserva, no activa perfiles de paciente en silencio. */
	private void exigirPacienteVigente(long organizationId, long personaId) {
		PacienteSnapshot paciente = pacientes.find(organizationId, personaId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));
		if (!paciente.activa() || !paciente.esPacienteVigente()) {
			throw new PersonaSinPerfilPacienteException(personaId);
		}
	}

	private TurnoSerie exigirSerie(long organizationId, long consultorioId, long serieId) {
		return series.findByIdInScope(organizationId, consultorioId, serieId)
				.orElseThrow(() -> new SerieNotAccessibleException(serieId));
	}

	private SerieView vista(TurnoSerie serie) {
		return SerieView.de(serie,
				turnos.findDeLaSerie(serie.getOrganizationId(), serie.getId())
						.stream().map(TurnoView::de).toList(),
				omisiones.deLaSerie(serie.getOrganizationId(), serie.getId())
						.stream().map(OcurrenciaOmitidaView::de).toList());
	}

	// =================================================================================
	// Auditoria
	// =================================================================================

	/**
	 * Ademas de la auditoria de cada turno, una entrada de la SERIE con el alcance: es lo que DP-04
	 * pide registrar ("actor, motivo, fecha y alcance") y lo que ningun evento de un turno solo
	 * puede contar.
	 */
	private void auditarOperacion(
			OperatingActor actor, TurnoSerie serie, String evento,
			OperacionDeSerieCommand command, List<Turno> afectados, Instant ahora) {

		Map<String, String> detalles = detalles(
				"alcance", command.alcance().name(),
				"cantidad", String.valueOf(afectados.size()),
				"turnoIds", ids(afectados));
		if (command.turnoId() != null) {
			detalles.put("turnoPivoteId", String.valueOf(command.turnoId()));
		}
		auditar(actor, serie, evento, command.motivo(), detalles, ahora);
	}

	private void auditar(
			OperatingActor actor, TurnoSerie serie, String evento, String motivo,
			Map<String, String> detalles, Instant ahora) {

		auditTrail.record(new AuditEntry(
				serie.getOrganizationId(), serie.getConsultorioId(), actor.accountId(),
				evento, ENTIDAD, serie.getId(), null, null, detalles, motivo, null, ahora));
	}

	private static Map<String, String> detalles(String... claveValor) {
		Map<String, String> mapa = new LinkedHashMap<>();
		for (int i = 0; i < claveValor.length; i += 2) {
			mapa.put(claveValor[i], claveValor[i + 1]);
		}
		return mapa;
	}

	private static String ids(List<Turno> lote) {
		return lote.stream().map(turno -> String.valueOf(turno.getId())).collect(Collectors.joining(","));
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("Las series de turnos requieren un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private void exigirPermiso(
			OperatingActor actor, long organizationId, long consultorioId, String permiso) {
		soporte.exigir(actor, permiso, organizationId, consultorioId);
	}
}
