package com.akine.scheduling.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.scheduling.application.AgendaOperativaView.BloqueoEnAgendaView;
import com.akine.scheduling.application.AgendaOperativaView.ClaseEnAgendaView;
import com.akine.scheduling.application.AgendaOperativaView.EspacioDeAgendaView;
import com.akine.scheduling.application.AgendaOperativaView.FranjaDeAgendaView;
import com.akine.scheduling.application.AgendaOperativaView.ProfesionalDeAgendaView;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.spi.EventoExternoDeAgenda;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * La agenda que se opera: por dia o por semana, por profesional o por box (TURNO - Tecnico §14).
 *
 * <p><b>Una llamada por profesional a la disponibilidad efectiva</b>, para la ventana entera: cada una
 * lee bloques, excepciones y feriados, y no hay forma agregada por sede. Por eso la ventana tiene un
 * tope corto: es lo que dibuja una pantalla, no un reporte.
 *
 * <p><b>Los profesionales son los de la sede mas los que tienen turnos en la ventana.</b> Un turno de
 * alguien que dejo de tener el rol sigue ocupando una fila real de la agenda y no puede desaparecer.
 */
@Service
public class AgendaOperativaService {

	/** Una semana y un poco: lo que entra en una pantalla. */
	public static final int VENTANA_MAXIMA_DIAS = 14;
	private static final String ROL_PROFESIONAL = "PROFESIONAL";
	private static final String CLASE_CANCELADA = "CANCELADA";

	private final RecepcionService recepcion;
	private final ConsultorioDirectory consultorios;
	private final ConsultorioMembershipDirectory memberships;
	private final DisponibilidadDirectory disponibilidad;
	private final EspacioDirectory espacios;
	private final EventoExternoDeAgenda eventosExternos;
	private final NombresDeAgenda nombres;
	private final SoporteEnAgenda soporte;
	private final OcupacionEntreSedes ocupacionEntreSedes;
	private final ConflictosDeDisponibilidad conflictosDeDisponibilidad;

	public AgendaOperativaService(
			RecepcionService recepcion,
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			DisponibilidadDirectory disponibilidad,
			EspacioDirectory espacios,
			EventoExternoDeAgenda eventosExternos,
			NombresDeAgenda nombres,
			SoporteEnAgenda soporte,
			OcupacionEntreSedes ocupacionEntreSedes) {
		this(recepcion, consultorios, memberships, disponibilidad, espacios, eventosExternos, nombres, soporte,
				ocupacionEntreSedes, null);
	}

	@org.springframework.beans.factory.annotation.Autowired
	@SuppressWarnings("checkstyle:ParameterNumber")
	public AgendaOperativaService(
			RecepcionService recepcion,
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			DisponibilidadDirectory disponibilidad,
			EspacioDirectory espacios,
			EventoExternoDeAgenda eventosExternos,
			NombresDeAgenda nombres,
			SoporteEnAgenda soporte,
			OcupacionEntreSedes ocupacionEntreSedes,
			ConflictosDeDisponibilidad conflictosDeDisponibilidad) {

		this.conflictosDeDisponibilidad = conflictosDeDisponibilidad;
		this.ocupacionEntreSedes = ocupacionEntreSedes;
		this.recepcion = recepcion;
		this.consultorios = consultorios;
		this.memberships = memberships;
		this.disponibilidad = disponibilidad;
		this.espacios = espacios;
		this.eventosExternos = eventosExternos;
		this.nombres = nombres;
		this.soporte = soporte;
	}

	/**
	 * @param hasta         EXCLUSIVO
	 * @param profesionalId acota turnos, clases y columnas a ese profesional
	 * @param ofertaId      acota los turnos a esa prestacion. Las franjas no cambian: siguen siendo
	 *                      el horario del profesional
	 * @param espacioId     acota los turnos a ese box
	 */
	@Transactional(readOnly = true)
	public AgendaOperativaView ver(
			OperatingActor actor, long consultorioId, LocalDate desde, LocalDate hasta,
			Long profesionalId, Long ofertaId, Long espacioId, boolean incluirCancelados) {
		return ver(actor, consultorioId, desde, hasta, profesionalId, ofertaId, espacioId, incluirCancelados,
				FiltroDeTurnos.NINGUNO);
	}

	/**
	 * La agenda acotada ademas por estado operativo y por persona (checklist de agenda §16). Cada turno
	 * vivo trae {@code fueraDeDisponibilidad}, derivado al leer contra las mismas franjas que dibujan
	 * las columnas: los que un bloqueo dejo afuera siguen visibles como "pendiente de reubicacion".
	 */
	@Transactional(readOnly = true)
	public AgendaOperativaView ver(
			OperatingActor actor, long consultorioId, LocalDate desde, LocalDate hasta,
			Long profesionalId, Long ofertaId, Long espacioId, boolean incluirCancelados,
			FiltroDeTurnos filtro) {

		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La agenda requiere un contexto de trabajo activo");
		}
		long organizationId = actor.contextOrganizationId();
		ConsultorioSnapshot sede = consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
		soporte.exigir(actor, PermissionCodes.TURNO_READ, organizationId, consultorioId);
		VentanaDeAgenda.exigirValida(desde, hasta, VENTANA_MAXIMA_DIAS);

		ZoneId zona = ZoneId.of(sede.timezone());
		Instant inicio = desde.atStartOfDay(zona).toInstant();
		Instant fin = hasta.atStartOfDay(zona).toInstant();

		// La version de siete argumentos y el filtro aca: el calendario y la agenda filtran igual, y la
		// recepcion no tiene por que conocer el box.
		var turnosLeidos = recepcion.delRango(actor, consultorioId, desde, hasta, profesionalId, ofertaId,
						filtro.incluyeCancelados(incluirCancelados))
				.turnos().stream()
				.filter(turno -> espacioId == null || espacioId.equals(turno.turno().espacioId()))
				.filter(turno -> filtro.admitePersona(turno.turno().personaId()))
				.filter(turno -> filtro.admite(turno.turno()))
				.toList();

		Set<Long> profesionales = new LinkedHashSet<>();
		if (profesionalId != null) {
			profesionales.add(profesionalId);
		} else {
			memberships.findPorRolEnSede(organizationId, consultorioId, ROL_PROFESIONAL)
					.forEach(membership -> profesionales.add(membership.membershipId()));
			turnosLeidos.stream().map(turno -> turno.turno().profesionalId()).filter(Objects::nonNull)
					.forEach(profesionales::add);
		}
		Map<Long, String> nombresDe = nombres.profesionales(organizationId, profesionales);
		List<ProfesionalDeAgendaView> columnas = profesionales.stream()
				.map(id -> new ProfesionalDeAgendaView(id, nombresDe.getOrDefault(id, "#" + id),
						disponibilidad.efectiva(organizationId, sede, id, desde, hasta).stream()
								.flatMap(dia -> dia.franjas().stream())
								.map(franja -> new FranjaDeAgendaView(franja.desde(), franja.hasta()))
								.toList()))
				.sorted(Comparator.comparing(ProfesionalDeAgendaView::nombre))
				.toList();

		Map<Long, List<FranjaDeAgendaView>> franjasDe = new HashMap<>();
		columnas.forEach(columna -> franjasDe.put(columna.membershipId(), columna.franjas()));
		var turnos = turnosLeidos.stream()
				.map(turno -> turno.conFueraDeDisponibilidad(fueraDeDisponibilidad(turno, franjasDe)))
				.toList();

		List<EspacioDeAgendaView> boxes = espacios.enServicio(organizationId, consultorioId, inicio, fin).stream()
				.filter(espacio -> espacio.active() && espacio.enServicio())
				.map(espacio -> new EspacioDeAgendaView(espacio.id(), espacio.name(), espacio.tipo()))
				.sorted(Comparator.comparing(EspacioDeAgendaView::nombre))
				.toList();

		List<ClaseEnAgendaView> clases = eventosExternos.enVentana(organizationId, consultorioId, inicio, fin).stream()
				.filter(evento -> !CLASE_CANCELADA.equals(evento.estado()))
				.filter(evento -> profesionalId == null || profesionalId.equals(evento.profesionalId()))
				.map(evento -> new ClaseEnAgendaView(evento.eventoId(), evento.inicio(), evento.fin(), evento.estado(),
						evento.titulo(), evento.ofertaNombre(), evento.profesionalId(), evento.espacioId(),
						evento.capacidad(), evento.ocupados()))
				.toList();

		List<BloqueoEnAgendaView> bloqueos = new ArrayList<>();
		for (DisponibilidadDirectory.CierreDeAgenda cierre
				: disponibilidad.cierresEnVentana(organizationId, consultorioId, desde, hasta)) {
			if (profesionalId != null && cierre.membershipId() != null && !profesionalId.equals(cierre.membershipId())) {
				continue;
			}
			for (LocalDate dia = max(desde, cierre.fechaDesde()); dia.isBefore(min(hasta, cierre.fechaHasta())); dia = dia.plusDays(1)) {
				bloqueos.add(new BloqueoEnAgendaView(cierre.id(), cierre.membershipId(), cierre.motivo(), cierre.notas(),
						instante(dia, cierre.horaDesde(), zona, false),
						instante(dia, cierre.horaHasta(), zona, true)));
			}
		}

		// Agenda de clases y avisos (0.156.0): lo que la misma persona tiene en otras sedes, para que la
		// celda no se vea libre, y los choques turno–clase que ya estaban guardados.
		OcupacionEntreSedes.Resultado entreSedes = ocupacionEntreSedes.de(organizationId, consultorioId,
				columnas.stream().map(ProfesionalDeAgendaView::membershipId).toList(), inicio, fin);
		// Disponibilidad central (0.158.0): tambien las clases con un instructor en cierre y la doble oferta
		// entre sedes que ya estaban guardadas. Se marcan, no se corrigen.
		List<AgendaOperativaView.ConflictoDeAgendaView> conflictos = new ArrayList<>(entreSedes.conflictos());
		if (conflictosDeDisponibilidad != null) {
			conflictos.addAll(conflictosDeDisponibilidad.de(organizationId, sede, columnas, bloqueos, inicio, fin,
					desde, hasta));
			conflictos.sort(Comparator.comparing(AgendaOperativaView.ConflictoDeAgendaView::inicio));
		}
		return new AgendaOperativaView(desde, hasta, sede.timezone(), columnas, boxes, turnos, clases, bloqueos,
				entreSedes.ocupaciones(), List.copyOf(conflictos));
	}

	/** Dia completo sin hora; una hora de fin a medianoche (24:00 en M05) es el inicio del dia siguiente. */
	/** Los estados operativos de un turno que todavia espera ocurrir: los unicos que se reubican. */
	private static final Set<String> PENDIENTES = Set.of("RESERVADO", "CONFIRMADO");

	/**
	 * {@code true} si el turno espera ocurrir y ninguna franja de su profesional lo contiene entero;
	 * {@code null} si no aplica (sin profesional, o ya en la sala, atendido, ausente o cancelado). Las
	 * franjas contiguas se unen antes de mirar, igual que el simulador de impacto de M05: un turno de
	 * 11:30 a 12:30 entre un bloque de 9 a 12 y una apertura de 12 a 14 esta cubierto.
	 */
	static Boolean fueraDeDisponibilidad(TurnoDeCalendarioView vista, Map<Long, List<FranjaDeAgendaView>> franjasDe) {
		TurnoDelDiaView turno = vista.turno();
		String estado = turno.operativo() == null ? turno.estado() : turno.operativo().estadoOperativo();
		if (turno.profesionalId() == null || !PENDIENTES.contains(estado)) {
			return null;
		}
		List<FranjaDeAgendaView> franjas = franjasDe.getOrDefault(turno.profesionalId(), List.of()).stream()
				.sorted(Comparator.comparing(FranjaDeAgendaView::desde))
				.toList();
		Instant desdeUnido = null;
		Instant hastaUnido = null;
		for (FranjaDeAgendaView franja : franjas) {
			if (hastaUnido != null && !franja.desde().isAfter(hastaUnido)) {
				hastaUnido = franja.hasta().isAfter(hastaUnido) ? franja.hasta() : hastaUnido;
				continue;
			}
			if (contiene(desdeUnido, hastaUnido, turno)) {
				return false;
			}
			desdeUnido = franja.desde();
			hastaUnido = franja.hasta();
		}
		return !contiene(desdeUnido, hastaUnido, turno);
	}

	private static boolean contiene(Instant desde, Instant hasta, TurnoDelDiaView turno) {
		return desde != null && !turno.inicio().isBefore(desde) && !turno.fin().isAfter(hasta);
	}

	private static Instant instante(LocalDate dia, LocalTime hora, ZoneId zona, boolean esFin) {
		if (hora == null || (esFin && hora.equals(LocalTime.MIDNIGHT)) || (esFin && hora.equals(LocalTime.MAX))) {
			return (esFin ? dia.plusDays(1) : dia).atStartOfDay(zona).toInstant();
		}
		return dia.atTime(hora).atZone(zona).toInstant();
	}

	private static LocalDate max(LocalDate a, LocalDate b) {
		return a.isAfter(b) ? a : b;
	}

	private static LocalDate min(LocalDate a, LocalDate b) {
		return a.isBefore(b) ? a : b;
	}
}
