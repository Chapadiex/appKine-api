package com.akine.offering.infrastructure;

import com.akine.offering.application.OfertaHonorarioClaseService;
import com.akine.offering.spi.HonorariosDeClase;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/** Implementacion de {@link HonorariosDeClase}: delega en el servicio, que no autoriza nada. */
@Component
class OfferingHonorariosDeClase implements HonorariosDeClase {

	private final OfertaHonorarioClaseService honorarios;

	OfferingHonorariosDeClase(OfertaHonorarioClaseService honorarios) {
		this.honorarios = honorarios;
	}

	@Override
	public List<HonorarioDeClase> activosDe(long organizationId, Collection<Long> ofertaIds) {
		return honorarios.activosDe(organizationId, ofertaIds);
	}
}
