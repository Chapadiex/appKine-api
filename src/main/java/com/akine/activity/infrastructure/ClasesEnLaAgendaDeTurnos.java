package com.akine.activity.infrastructure;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.scheduling.spi.EventoExternoDeAgenda;
import com.akine.scheduling.spi.OcupacionExternaProbe;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Le contesta a M12 por las clases: si ocupan un recurso, y como se ven en la grilla.
 *
 * <h2>Por que esta clase es la mitad que faltaba</h2>
 *
 * <p>{@code ClaseService} ya consulta los turnos antes de programar una clase. Sin esta clase la
 * simetria no existiria: se podria <b>reservar un turno encima de una clase</b> y nadie se
 * enteraria, porque {@code RevalidadorDeSlot} solo miraria turnos. Las dos mitades son necesarias y
 * ninguna alcanza sola.
 *
 * <p>La direccion de la dependencia la fija la inversion: las dos interfaces viven en
 * {@code scheduling.spi} —el que pregunta declara el contrato— y esta clase las implementa. Asi la
 * unica arista de compilacion sigue siendo {@code activity -> scheduling}, y ArchUnit no ve ningun
 * ciclo. Mismo patron que {@code encounter.EncounterAtencionProbe}.
 *
 * <h2>El contrato de concurrencia</h2>
 *
 * <p>{@link #profesionalOcupado} y {@link #espacioOcupado} solo son una garantia si quien las llama
 * las llama <b>bajo el lock de {@code agenda_sede}</b>. Fuera de ahi son una foto. Ver
 * {@link OcupacionExternaProbe}.
 */
@Component
public class ClasesEnLaAgendaDeTurnos implements OcupacionExternaProbe, EventoExternoDeAgenda {

	/** Discriminador de los eventos de M28 en la agenda unificada (RF-M12-013). */
	public static final String TIPO_CLASE = "CLASE";

	private final ClaseProgramadaRepository clases;
	private final OfertaDirectory ofertas;

	public ClasesEnLaAgendaDeTurnos(ClaseProgramadaRepository clases, OfertaDirectory ofertas) {
		this.clases = clases;
		this.ofertas = ofertas;
	}

	// =================================================================================
	// Exclusion — camino de escritura, bajo el lock
	// =================================================================================

	@Override
	public boolean profesionalOcupado(
			long organizationId, long profesionalMembershipId, Instant inicio, Instant fin) {

		return !clases.findVivasDeProfesionalQueCruzan(
				organizationId, profesionalMembershipId, inicio, fin).isEmpty();
	}

	@Override
	public boolean espacioOcupado(long organizationId, long espacioId, Instant inicio, Instant fin) {
		return !clases.findVivasDeEspacioQueCruzan(organizationId, espacioId, inicio, fin).isEmpty();
	}

	// =================================================================================
	// Proyeccion — camino de lectura, sin transaccion
	// =================================================================================

	/**
	 * <p><b>Sin lista de participantes</b>, y no por omision: la seguridad de la etapa exige vista
	 * publica sin participantes, y esta proyeccion alimenta la agenda que ve todo el mostrador.
	 *
	 * <p>La capacidad que viaja es la <b>efectiva</b>, ya resuelta aca: M12 no conoce las reglas de
	 * M28 y no tiene con que calcularla. La ocupacion es 0 hasta 08.02.
	 */
	@Override
	public List<EventoDeAgendaExterno> enVentana(
			long organizationId, long consultorioId, Instant desde, Instant hasta) {

		return clases.findDeLaSedeEnVentana(organizationId, consultorioId, desde, hasta).stream()
				.map(clase -> proyectar(organizationId, consultorioId, clase))
				.toList();
	}

	private EventoDeAgendaExterno proyectar(
			long organizationId, long consultorioId, ClaseProgramada clase) {

		int capacidad = clase.getCapacidad();
		String nombreOferta = null;
		var oferta = ofertas.find(organizationId, consultorioId, clase.getOfertaId());
		if (oferta.isPresent()) {
			nombreOferta = oferta.get().nombreComercial();
			capacidad = Math.min(capacidad, oferta.get().capacidad());
		}
		return new EventoDeAgendaExterno(
				TIPO_CLASE,
				clase.getId(),
				clase.getInicio(),
				clase.getFin(),
				clase.getEstado().name(),
				clase.getOfertaId(),
				nombreOferta,
				clase.getTitulo(),
				clase.getProfesionalMembershipId(),
				clase.getEspacioId(),
				capacidad,
				0);
	}
}
