package com.akine.activity.infrastructure;

import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseProgramadaRepositoryPort;
import com.akine.encounter.spi.TituloDeClaseGrupal;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Le dice a {@code encounter} como se llama la clase de una Sesion grupal (AKINE-08.05). Ver
 * {@link TituloDeClaseGrupal}: la interfaz es de {@code encounter} y la implementa este modulo, asi
 * la unica arista sigue siendo {@code activity -> encounter.spi}.
 *
 * <p>El titulo propio de la clase manda; si no tiene, el nombre comercial de su oferta, que es lo
 * que la semana de clases muestra en ese caso.
 */
@Component
public class TituloDeClaseParaLaSesion implements TituloDeClaseGrupal {

	private final ClaseProgramadaRepositoryPort clases;
	private final OfertaDirectory ofertas;

	public TituloDeClaseParaLaSesion(ClaseProgramadaRepositoryPort clases, OfertaDirectory ofertas) {
		this.clases = clases;
		this.ofertas = ofertas;
	}

	@Override
	public Optional<String> de(long organizationId, long consultorioId, long claseId) {
		return clases.findByIdInScope(organizationId, consultorioId, claseId)
				.flatMap(clase -> {
					if (clase.getTitulo() != null && !clase.getTitulo().isBlank()) {
						return Optional.of(clase.getTitulo());
					}
					return ofertas.find(organizationId, consultorioId, clase.getOfertaId())
							.map(OfertaSnapshot::nombreComercial);
				});
	}
}
