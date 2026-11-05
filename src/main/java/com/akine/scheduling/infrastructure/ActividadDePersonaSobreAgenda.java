package com.akine.scheduling.infrastructure;

import com.akine.platform.spi.problem.ConflictoDeAgenda;
import com.akine.resource.spi.ActividadDePersonaProbe;
import com.akine.scheduling.spi.EventoExternoDeAgenda;
import com.akine.scheduling.spi.OcupacionDePersona;
import com.akine.scheduling.spi.OcupacionExternaProbe;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Contesta {@link ActividadDePersonaProbe} con la logica central de {@code scheduling}
 * ({@link OcupacionDePersona}): la apertura y el cierre de {@code resource} preguntan lo mismo que el
 * turno y la clase (disponibilidad central, contrato 0.158.0).
 */
@Component
class ActividadDePersonaSobreAgenda implements ActividadDePersonaProbe {

	private static final String CANCELADA = "CANCELADA";

	private final OcupacionDePersona ocupacion;
	private final OcupacionExternaProbe clases;
	private final EventoExternoDeAgenda eventos;

	ActividadDePersonaSobreAgenda(
			OcupacionDePersona ocupacion, OcupacionExternaProbe clases, EventoExternoDeAgenda eventos) {
		this.ocupacion = ocupacion;
		this.clases = clases;
		this.eventos = eventos;
	}

	@Override
	public List<ConflictoDeAgenda> actividadReservada(
			long organizationId, long consultorioId, long membershipId, Instant inicio, Instant fin) {
		return ocupacion.actividadReservada(
				OcupacionDePersona.Consulta.de(organizationId, consultorioId, membershipId, inicio, fin));
	}

	@Override
	public List<ClaseAfectada> clasesDeLaSede(
			long organizationId, long consultorioId, Long membershipId, Instant inicio, Instant fin) {
		if (membershipId == null) {
			return eventos.enVentana(organizationId, consultorioId, inicio, fin).stream()
					.filter(evento -> !CANCELADA.equals(evento.estado()))
					.filter(evento -> evento.inicio().isBefore(fin) && inicio.isBefore(evento.fin()))
					.map(evento -> new ClaseAfectada(evento.eventoId(), null, evento.inicio(), evento.fin()))
					.toList();
		}
		return clases.ocupacionDeProfesionales(organizationId, Set.of(membershipId), inicio, fin).stream()
				.filter(clase -> clase.consultorioId() == consultorioId)
				.map(clase -> new ClaseAfectada(clase.eventoId(), membershipId, clase.inicio(), clase.fin()))
				.toList();
	}
}
