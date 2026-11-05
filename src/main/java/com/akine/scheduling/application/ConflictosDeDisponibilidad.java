package com.akine.scheduling.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.scheduling.application.AgendaOperativaView.BloqueoEnAgendaView;
import com.akine.scheduling.application.AgendaOperativaView.ConflictoDeAgendaView;
import com.akine.scheduling.application.AgendaOperativaView.FranjaDeAgendaView;
import com.akine.scheduling.application.AgendaOperativaView.ProfesionalDeAgendaView;
import com.akine.scheduling.spi.OcupacionExternaProbe;
import com.akine.scheduling.spi.OcupacionExternaProbe.OcupacionExterna;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Los conflictos que ya estan guardados y que las reglas de la disponibilidad central (0.158.0) ya no
 * dejan crear: se calculan al leer la agenda operativa y <b>no se modifica nada</b> (regularizarlos es una
 * decision de quien opera; ver {@code docs/diseno/AKINE-agenda-disponibilidad-central.md}).
 *
 * <ul>
 *   <li>{@code CLASE_EN_CIERRE}: una clase de esta sede con un instructor (principal o adicional) que tiene
 *       un cierre que la alcanza —un bloqueo o una ausencia suyos, o el cierre de la sede—.</li>
 *   <li>{@code DISPONIBILIDAD_DOBLE}: la persona de una columna tiene franjas a la vez en esta sede y en
 *       otra (doble oferta, tipicamente una apertura anterior a 0.158.0).</li>
 * </ul>
 */
@Component
public class ConflictosDeDisponibilidad {

	private static final Set<String> MOTIVOS_DE_AUSENCIA = Set.of("AUSENCIA", "LICENCIA", "VACACIONES");

	private final OcupacionExternaProbe clases;
	private final AgendaDelProfesional agendaDelProfesional;
	private final ConsultorioMembershipDirectory memberships;
	private final ConsultorioDirectory consultorios;
	private final DisponibilidadDirectory disponibilidad;

	public ConflictosDeDisponibilidad(
			OcupacionExternaProbe clases,
			AgendaDelProfesional agendaDelProfesional,
			ConsultorioMembershipDirectory memberships,
			ConsultorioDirectory consultorios,
			DisponibilidadDirectory disponibilidad) {
		this.clases = clases;
		this.agendaDelProfesional = agendaDelProfesional;
		this.memberships = memberships;
		this.consultorios = consultorios;
		this.disponibilidad = disponibilidad;
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public List<ConflictoDeAgendaView> de(
			long organizationId, ConsultorioSnapshot sede, List<ProfesionalDeAgendaView> columnas,
			List<BloqueoEnAgendaView> bloqueos, Instant inicio, Instant fin, LocalDate desde, LocalDate hasta) {

		List<ConflictoDeAgendaView> conflictos = new ArrayList<>();
		if (columnas.isEmpty()) {
			return conflictos;
		}
		List<Long> ids = columnas.stream().map(ProfesionalDeAgendaView::membershipId).toList();

		// Clases de esta sede con un instructor (la columna) alcanzado por un cierre.
		for (OcupacionExterna clase : clases.ocupacionDeProfesionales(organizationId, ids, inicio, fin)) {
			if (clase.consultorioId() != sede.id()) {
				continue;
			}
			for (BloqueoEnAgendaView bloqueo : bloqueos) {
				boolean alcanza = bloqueo.membershipId() == null
						|| bloqueo.membershipId() == clase.profesionalMembershipId();
				if (alcanza && clase.seCruzaCon(bloqueo.desde(), bloqueo.hasta())) {
					String causa = bloqueo.membershipId() == null
							? "CIERRE_DE_SEDE"
							: MOTIVOS_DE_AUSENCIA.contains(bloqueo.motivo()) ? "AUSENCIA" : "BLOQUEO";
					conflictos.add(new ConflictoDeAgendaView(clase.profesionalMembershipId(), null, null,
							clase.eventoId(), clase.consultorioId(), max(clase.inicio(), bloqueo.desde()),
							min(clase.fin(), bloqueo.hasta()), ConflictoDeAgendaView.CLASE_EN_CIERRE,
							bloqueo.excepcionId(), causa, null));
				}
			}
		}

		// Doble oferta: franjas de la columna que se pisan con franjas de la misma persona en otra sede.
		for (ProfesionalDeAgendaView columna : columnas) {
			if (columna.franjas().isEmpty()) {
				continue;
			}
			for (Long otra : agendaDelProfesional.deLaMismaPersona(organizationId, columna.membershipId())) {
				Optional<ConsultorioSnapshot> otraSede = memberships.find(organizationId, otra)
						.map(ConsultorioMembershipSnapshot::consultorioId)
						.filter(id -> id != null && id != sede.id())
						.flatMap(id -> consultorios.find(organizationId, id));
				if (otraSede.isEmpty()) {
					continue;
				}
				disponibilidad.efectiva(organizationId, otraSede.get(), otra, desde, hasta).stream()
						.flatMap(dia -> dia.franjas().stream())
						.forEach(franjaAlla -> {
							for (FranjaDeAgendaView franjaAca : columna.franjas()) {
								Instant desdeComun = max(franjaAca.desde(), franjaAlla.desde());
								Instant hastaComun = min(franjaAca.hasta(), franjaAlla.hasta());
								if (desdeComun.isBefore(hastaComun)) {
									conflictos.add(new ConflictoDeAgendaView(columna.membershipId(), null, null, null,
											null, desdeComun, hastaComun, ConflictoDeAgendaView.DISPONIBILIDAD_DOBLE,
											null, "DISPONIBILIDAD", otraSede.get().id()));
								}
							}
						});
			}
		}
		conflictos.sort(Comparator.comparing(ConflictoDeAgendaView::inicio));
		return conflictos;
	}

	private static Instant max(Instant a, Instant b) {
		return a.isAfter(b) ? a : b;
	}

	private static Instant min(Instant a, Instant b) {
		return a.isBefore(b) ? a : b;
	}
}
