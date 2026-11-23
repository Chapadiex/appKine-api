package com.akine.organization.api.dto;

import com.akine.organization.domain.PanelDePlataforma;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Estado general de la plataforma: lo que necesita atencion y lo ultimo que paso")
public record PanelDePlataformaResponse(
		@Schema(description = "Centros vigentes por estado de su suscripcion")
		CentrosDelPanelResponse centros,
		@Schema(description = "Pedidos de prueba gratis sin resolver", example = "3")
		long pedidosPendientes,
		@Schema(description = "Pruebas vencidas y las que terminan en los proximos 30 dias, la mas proxima primero")
		List<PruebaQueTerminaResponse> pruebasQueTerminan,
		@Schema(description = "Ultimos centros dados de alta")
		List<CentroRecienteResponse> ultimosCentros,
		@Schema(description = "Ultimos pedidos de prueba recibidos")
		List<PedidoRecienteResponse> ultimosPedidos,
		@Schema(description = "Ultimos cambios de estado o de plan de una suscripcion")
		List<CambioDeSuscripcionResponse> ultimosCambios) {

	public static PanelDePlataformaResponse from(PanelDePlataforma panel) {
		PanelDePlataforma.Centros c = panel.centros();
		return new PanelDePlataformaResponse(
				new CentrosDelPanelResponse(c.total(), c.activos(), c.enPrueba(), c.pruebaPorVencer(),
						c.pruebaVencida(), c.suspendidos(), c.cancelados()),
				panel.pedidosPendientes(),
				panel.pruebasQueTerminan().stream()
						.map(p -> new PruebaQueTerminaResponse(p.organizationId(), p.nombre(), p.pruebaHasta()))
						.toList(),
				panel.ultimosCentros().stream()
						.map(r -> new CentroRecienteResponse(r.organizationId(), r.nombre(), r.estado(), r.creadoEn()))
						.toList(),
				panel.ultimosPedidos().stream()
						.map(p -> new PedidoRecienteResponse(p.id(), p.nombreCentro(), p.persona(), p.estado(),
								p.recibidoEn()))
						.toList(),
				panel.ultimosCambios().stream()
						.map(m -> new CambioDeSuscripcionResponse(m.organizationId(), m.nombre(), m.desde(),
								m.hacia(), m.cuando()))
						.toList());
	}

	@Schema(description = "Centros vigentes por estado de su suscripcion")
	public record CentrosDelPanelResponse(
			@Schema(description = "Todos los centros vigentes", example = "12") long total,
			@Schema(description = "Con suscripcion activa", example = "8") long activos,
			@Schema(description = "En prueba gratis y todavia dentro del plazo", example = "3") long enPrueba,
			@Schema(description = "En prueba y terminan en los proximos 7 dias (incluidos en enPrueba)",
					example = "1") long pruebaPorVencer,
			@Schema(description = "En prueba con el plazo cumplido: solo lectura hasta contratar",
					example = "1") long pruebaVencida,
			@Schema(description = "Suspendidos", example = "0") long suspendidos,
			@Schema(description = "Cancelados", example = "0") long cancelados) {
	}

	@Schema(description = "Un centro cuya prueba termino o termina pronto")
	public record PruebaQueTerminaResponse(
			@Schema(example = "7") long organizationId,
			@Schema(example = "Centro Movimiento") String nombre,
			@Schema(description = "Fin de la prueba, instante UTC") Instant pruebaHasta) {
	}

	@Schema(description = "Un centro dado de alta hace poco")
	public record CentroRecienteResponse(
			@Schema(example = "7") long organizationId,
			@Schema(example = "Centro Movimiento") String nombre,
			@Schema(description = "Estado de su suscripcion", example = "PRUEBA") String estado,
			@Schema(description = "Alta, instante UTC") Instant creadoEn) {
	}

	@Schema(description = "Un pedido de prueba recibido hace poco")
	public record PedidoRecienteResponse(
			@Schema(example = "5") long id,
			@Schema(example = "Estudio Pilates") String nombreCentro,
			@Schema(description = "Nombre y apellido de quien pide", example = "Ana Gomez") String persona,
			@Schema(example = "PENDIENTE") String estado,
			@Schema(description = "Cuando llego, instante UTC") Instant recibidoEn) {
	}

	@Schema(description = "Un cambio de estado o de plan de una suscripcion")
	public record CambioDeSuscripcionResponse(
			@Schema(example = "7") long organizationId,
			@Schema(example = "Centro Movimiento") String nombre,
			@Schema(description = "Estado anterior; vacio en el alta", example = "PRUEBA") String desde,
			@Schema(example = "ACTIVA") String hacia,
			@Schema(description = "Cuando, instante UTC") Instant cuando) {
	}
}
