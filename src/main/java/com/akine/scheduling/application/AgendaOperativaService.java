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

	public AgendaOperativaService(
			RecepcionService recepcion,
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			DisponibilidadDirectory disponibilidad,
			EspacioDirectory espacios,
			EventoExternoDeAgenda eventosExternos,
			NombresDeAgenda nombres,
			SoporteEnAgenda soporte) {

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

		var turnos = recepcion.delRango(actor, consultorioId, desde, hasta, profesionalId, ofertaId, incluirCancelados)
				.turnos().stream()
				.filter(turno -> espacioId == null || espacioId.equals(turno.turno().espacioId()))
				.toList();

		Set<Long> profesionales = new LinkedHashSet<>();
		if (profesionalId != null) {
			profesionales.add(profesionalId);
		} else {
			memberships.findPorRolEnSede(organizationId, consultorioId, ROL_PROFESIONAL)
					.forEach(membership -> profesionales.add(membership.membershipId()));
			turnos.stream().map(turno -> turno.turno().profesionalId()).filter(Objects::nonNull)
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

		return new AgendaOperativaView(desde, hasta, sede.timezone(), columnas, boxes, turnos, clases, bloqueos);
	}

	/** Dia completo sin hora; una hora de fin a medianoche (24:00 en M05) es el inicio del dia siguiente. */
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
