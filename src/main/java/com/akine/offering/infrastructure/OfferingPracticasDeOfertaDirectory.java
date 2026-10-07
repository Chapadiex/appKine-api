package com.akine.offering.infrastructure;

import com.akine.offering.spi.PracticaDeOferta;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Adaptador de {@link PracticasDeOfertaDirectory} sobre {@code oferta_practica} (A-9).
 *
 * <p>Resuelve la oferta primero, con la sede en el {@code WHERE}, igual que
 * {@link OfferingOfertaDirectory}: las filas de {@code oferta_practica} se filtran por
 * {@code (organization_id, oferta_id)}, y sin ese paso una oferta de otra sede del mismo tenant
 * responderia.
 */
@Component
public class OfferingPracticasDeOfertaDirectory implements PracticasDeOfertaDirectory {

	private final OfertaRepository ofertas;
	private final OfertaPracticaRepository practicas;

	public OfferingPracticasDeOfertaDirectory(
			OfertaRepository ofertas, OfertaPracticaRepository practicas) {
		this.ofertas = ofertas;
		this.practicas = practicas;
	}

	@Override
	public List<PracticaDeOferta> practicasHabilitadas(
			long organizationId, long consultorioId, long ofertaId) {

		if (ofertas.findByIdAndOrganizationIdAndConsultorioId(ofertaId, organizationId, consultorioId)
				.isEmpty()) {
			return List.of();
		}
		return practicas
				.findAllByOrganizationIdAndOfertaIdAndActiveOrderByIdAsc(organizationId, ofertaId, true)
				.stream()
				.map(fila -> new PracticaDeOferta(fila.getPracticaId(), fila.isPrincipal()))
				.toList();
	}

	@Override
	public Optional<Long> practicaPrincipal(long organizationId, long consultorioId, long ofertaId) {
		return practicasHabilitadas(organizationId, consultorioId, ofertaId).stream()
				.filter(PracticaDeOferta::principal)
				.map(PracticaDeOferta::practicaId)
				.findFirst();
	}
}
