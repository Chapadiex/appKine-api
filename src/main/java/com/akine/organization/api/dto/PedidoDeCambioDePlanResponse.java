package com.akine.organization.api.dto;

import com.akine.organization.application.PedidoDeCambioDePlanView;
import com.akine.organization.domain.PedidoDeCambioDePlan;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Schema(description = "Pedido de cambio de plan de un centro, con la contratacion que tenia al pedir")
public record PedidoDeCambioDePlanResponse(
		long id,
		long organizationId,
		@Schema(description = "Nombre del centro") String organizationNombre,
		@Schema(description = "Plan pedido", example = "CRECIMIENTO") String planCode,
		@Schema(description = "Nombre del plan pedido", example = "Plan Intermedio") String planNombre,
		List<Servicio> servicios,
		List<Adicional> adicionales,
		@Schema(description = "Plan que tenia al pedir", example = "BASE") String planCodeAnterior,
		@Schema(description = "Nombre del plan que tenia al pedir", example = "Plan Base") String planNombreAnterior,
		List<Servicio> serviciosAnteriores,
		List<Adicional> adicionalesAnteriores,
		String comentario,
		PedidoDeCambioDePlan.Estado estado,
		@Schema(description = "Motivo, si se rechazo") String motivoRechazo,
		@Schema(description = "Cuando se pidio") Instant pedidoEn,
		@Schema(description = "Cuando se resolvio") Instant resueltoEn,
		long version,
		@Schema(description = "Topes del plan pedido que el centro hoy supera; solo en PENDIENTE. Con alguno, "
				+ "no se puede aprobar hasta que el centro se ajuste")
		List<ExcesoDeTopeResponse> excesos) {

	public static PedidoDeCambioDePlanResponse from(PedidoDeCambioDePlanView p) {
		return new PedidoDeCambioDePlanResponse(p.id(), p.organizationId(), p.organizationNombre(),
				p.planCode(), p.planNombre(), ordenados(p.servicios()), ordenados(p.adicionales()),
				p.planCodeAnterior(), p.planNombreAnterior(), ordenados(p.serviciosAnteriores()),
				ordenados(p.adicionalesAnteriores()), p.comentario(), p.estado(), p.motivoRechazo(),
				p.pedidoEn(), p.resueltoEn(), p.version(),
				p.excesos().stream().map(e -> new ExcesoDeTopeResponse(e.limitCode(), e.tope(), e.uso())).toList());
	}

	private static <E extends Enum<E>> List<E> ordenados(Set<E> valores) {
		return valores.stream().sorted().toList();
	}
}
