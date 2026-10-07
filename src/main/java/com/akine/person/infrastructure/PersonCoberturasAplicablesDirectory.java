package com.akine.person.infrastructure;

import com.akine.person.application.CoberturasAplicablesService;
import com.akine.person.spi.CoberturaAplicable;
import com.akine.person.spi.CoberturaNoAplicable;
import com.akine.person.spi.CoberturasAplicablesDirectory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Implementacion de {@link CoberturasAplicablesDirectory}: el borde por donde {@code person}
 * responde que coberturas aplican. Devuelve solo records del {@code spi}, nunca entities.
 */
@Component
public class PersonCoberturasAplicablesDirectory implements CoberturasAplicablesDirectory {

	private final CoberturasAplicablesService servicio;

	public PersonCoberturasAplicablesDirectory(CoberturasAplicablesService servicio) {
		this.servicio = servicio;
	}

	@Override
	public List<CoberturaAplicable> aplicables(
			long organizationId, long consultorioId, long personaId, long practicaId,
			Long ofertaId, LocalDate fecha) {
		return servicio.aplicables(
				organizationId, consultorioId, personaId, practicaId, ofertaId, fecha);
	}

	@Override
	public List<CoberturaNoAplicable> noAplicables(
			long organizationId, long consultorioId, long personaId, long practicaId,
			Long ofertaId, LocalDate fecha) {
		return servicio.noAplicables(
				organizationId, consultorioId, personaId, practicaId, ofertaId, fecha);
	}
}
