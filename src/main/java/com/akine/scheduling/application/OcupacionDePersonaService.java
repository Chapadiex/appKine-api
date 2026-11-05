package com.akine.scheduling.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.platform.spi.problem.ConflictoDeAgenda;
import com.akine.platform.spi.problem.ConflictoDeAgenda.Tipo;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.spi.DisponibilidadDirectory.CierreDeAgenda;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.OcupacionDePersona;
import com.akine.scheduling.spi.OcupacionExternaProbe;
import com.akine.scheduling.spi.OcupacionExternaProbe.OcupacionExterna;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Implementacion de {@link OcupacionDePersona}: la unica respuesta a "esta persona puede tomar esta
 * obligacion" (disponibilidad central, contrato 0.158.0). Ver la interfaz.
 *
 * <p>Las memberships de la persona salen de {@code agenda_profesional} sin lock
 * ({@link AgendaDelProfesional#deLaMismaPersona}): quien serializa ya tomo la fila antes de preguntar.
 */
@Service
public class OcupacionDePersonaService implements OcupacionDePersona {

	private static final Set<String> MOTIVOS_DE_AUSENCIA = Set.of("AUSENCIA", "LICENCIA", "VACACIONES");

	private final TurnoRepositoryPort turnos;
	private final OcupacionExternaProbe clases;
	private final AgendaDelProfesional agendaDelProfesional;
	private final DisponibilidadDirectory disponibilidad;
	private final ConsultorioDirectory consultorios;

	public OcupacionDePersonaService(
			TurnoRepositoryPort turnos,
			OcupacionExternaProbe clases,
			AgendaDelProfesional agendaDelProfesional,
			DisponibilidadDirectory disponibilidad,
			ConsultorioDirectory consultorios) {
		this.turnos = turnos;
		this.clases = clases;
		this.agendaDelProfesional = agendaDelProfesional;
		this.disponibilidad = disponibilidad;
		this.consultorios = consultorios;
	}

	@Override
	public List<ConflictoDeAgenda> actividadReservada(Consulta consulta) {
		Set<Long> persona = agendaDelProfesional.deLaMismaPersona(consulta.organizationId(), consulta.membershipId());
		Sedes sedes = new Sedes(consulta.organizationId());
		List<ConflictoDeAgenda> conflictos = new ArrayList<>();

		for (Turno turno : turnos.findVivosDeProfesionalesQueCruzan(
				consulta.organizationId(), persona, consulta.inicio(), consulta.fin())) {
			if (Objects.equals(turno.getId(), consulta.turnoExcluidoId()) || esDelGrupo(turno, consulta)) {
				continue;
			}
			conflictos.add(sedes.conflicto(Tipo.TURNO, turno.getId(), turno.getConsultorioId(),
					consulta.consultorioId(), turno.getInicio(), turno.getFin()));
		}
		for (OcupacionExterna clase : clases.ocupacionDeProfesionales(
				consulta.organizationId(), persona, consulta.inicio(), consulta.fin())) {
			if (Objects.equals(clase.eventoId(), consulta.claseExcluidaId())) {
				continue;
			}
			conflictos.add(sedes.conflicto(Tipo.CLASE, clase.eventoId(), clase.consultorioId(),
					consulta.consultorioId(), clase.inicio(), clase.fin()));
		}
		conflictos.sort(Comparator.comparing(ConflictoDeAgenda::desde));
		return conflictos.stream().distinct().toList();
	}

	@Override
	public List<ConflictoDeAgenda> cierres(Consulta consulta) {
		Optional<ConsultorioSnapshot> sede = consultorios == null
				? Optional.empty()
				: consultorios.find(consulta.organizationId(), consulta.consultorioId());
		if (sede.isEmpty()) {
			return List.of();
		}
		ZoneId zona = ZoneId.of(sede.get().timezone());
		LocalDate desde = consulta.inicio().atZone(zona).toLocalDate();
		LocalDate hasta = consulta.fin().atZone(zona).toLocalDate().plusDays(1);
		List<ConflictoDeAgenda> conflictos = new ArrayList<>();
		for (CierreDeAgenda cierre : disponibilidad.cierresEnVentana(
				consulta.organizationId(), consulta.consultorioId(), desde, hasta)) {
			if (cierre.membershipId() != null && cierre.membershipId() != consulta.membershipId()) {
				continue;
			}
			Tipo tipo = cierre.membershipId() == null
					? Tipo.CIERRE_DE_SEDE
					: MOTIVOS_DE_AUSENCIA.contains(cierre.motivo()) ? Tipo.AUSENCIA : Tipo.BLOQUEO;
			for (Instant[] tramo : tramos(cierre, desde, hasta, zona)) {
				if (tramo[0].isBefore(consulta.fin()) && consulta.inicio().isBefore(tramo[1])) {
					conflictos.add(new ConflictoDeAgenda(tipo, cierre.id(), sede.get().id(), sede.get().name(),
							true, tramo[0], tramo[1], sede.get().timezone(), null, null));
				}
			}
		}
		conflictos.sort(Comparator.comparing(ConflictoDeAgenda::desde));
		return List.copyOf(conflictos);
	}

	/** Un tramo por dia de un cierre, dentro de {@code [desde, hasta)}, ya en instantes. */
	static List<Instant[]> tramos(CierreDeAgenda cierre, LocalDate desde, LocalDate hasta, ZoneId zona) {
		List<Instant[]> tramos = new ArrayList<>();
		LocalDate primero = cierre.fechaDesde().isAfter(desde) ? cierre.fechaDesde() : desde;
		LocalDate ultimo = cierre.fechaHasta().isBefore(hasta) ? cierre.fechaHasta() : hasta;
		for (LocalDate dia = primero; dia.isBefore(ultimo); dia = dia.plusDays(1)) {
			tramos.add(new Instant[] {
					instante(dia, cierre.horaDesde(), zona, false), instante(dia, cierre.horaHasta(), zona, true)});
		}
		return tramos;
	}

	private static Instant instante(LocalDate dia, LocalTime hora, ZoneId zona, boolean esFin) {
		if (hora == null || (esFin && (hora.equals(LocalTime.MIDNIGHT) || hora.equals(LocalTime.MAX)))) {
			return (esFin ? dia.plusDays(1) : dia).atStartOfDay(zona).toInstant();
		}
		return dia.atTime(hora).atZone(zona).toInstant();
	}

	private static boolean esDelGrupo(Turno turno, Consulta consulta) {
		return consulta.ofertaDelGrupo() != null
				&& consulta.ofertaDelGrupo().equals(turno.getOfertaId())
				&& Objects.equals(turno.getProfesionalMembershipId(), consulta.membershipId())
				&& turno.getInicio().equals(consulta.inicio());
	}

	/** Nombre y zona de cada sede, leidos una sola vez por consulta. */
	private final class Sedes {

		private final long organizationId;
		private final Map<Long, Optional<ConsultorioSnapshot>> leidas = new HashMap<>();

		Sedes(long organizationId) {
			this.organizationId = organizationId;
		}

		ConflictoDeAgenda conflicto(Tipo tipo, long eventoId, Long consultorioId, long consultorioDeLaOperacion,
				Instant inicio, Instant fin) {
			Optional<ConsultorioSnapshot> sede = consultorioId == null || consultorios == null
					? Optional.empty()
					: leidas.computeIfAbsent(consultorioId, id -> consultorios.find(organizationId, id));
			return new ConflictoDeAgenda(tipo, eventoId, consultorioId,
					sede.map(ConsultorioSnapshot::name).orElse(null),
					consultorioId != null && consultorioId == consultorioDeLaOperacion,
					inicio, fin, sede.map(ConsultorioSnapshot::timezone).orElse(null), null, null);
		}
	}
}
