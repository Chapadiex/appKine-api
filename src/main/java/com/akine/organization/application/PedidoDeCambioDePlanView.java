package com.akine.organization.application;

import com.akine.organization.domain.PedidoDeCambioDePlan;
import com.akine.organization.domain.exception.PedidoExcedeLimitesException.Exceso;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Un pedido de cambio de plan, con los nombres que la pantalla muestra (25/11/2026).
 *
 * @param planNombre         nombre del plan pedido; el codigo si el plan ya no esta en el catalogo
 * @param planNombreAnterior nombre del plan que el centro tenia al pedir
 * @param excesos            topes del plan pedido que el centro hoy supera; solo en PENDIENTE
 */
public record PedidoDeCambioDePlanView(
		long id,
		long organizationId,
		String organizationNombre,
		String planCode,
		String planNombre,
		Set<Servicio> servicios,
		Set<Adicional> adicionales,
		String planCodeAnterior,
		String planNombreAnterior,
		Set<Servicio> serviciosAnteriores,
		Set<Adicional> adicionalesAnteriores,
		String comentario,
		PedidoDeCambioDePlan.Estado estado,
		String motivoRechazo,
		Instant pedidoEn,
		Instant resueltoEn,
		long version,
		List<Exceso> excesos) {

	public PedidoDeCambioDePlanView {
		excesos = List.copyOf(excesos);
		servicios = Set.copyOf(servicios);
		adicionales = Set.copyOf(adicionales);
		serviciosAnteriores = Set.copyOf(serviciosAnteriores);
		adicionalesAnteriores = Set.copyOf(adicionalesAnteriores);
	}

	static PedidoDeCambioDePlanView de(PedidoDeCambioDePlan p, String organizationNombre, String planNombre,
			String planNombreAnterior, List<Exceso> excesos) {
		return new PedidoDeCambioDePlanView(p.getId(), p.getOrganizationId(), organizationNombre,
				p.getPlanCode(), planNombre, p.getServicios(), p.getAdicionales(),
				p.getPlanCodeAnterior(), planNombreAnterior, p.getServiciosAnteriores(),
				p.getAdicionalesAnteriores(), p.getComentario(), p.getEstado(), p.getMotivoRechazo(),
				p.getCreatedAt(), p.getResueltoEn(), p.getVersion(), excesos);
	}
}
