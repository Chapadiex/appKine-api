package com.akine.organization.domain;

import java.time.Instant;
import java.util.List;

/**
 * Estado general de la plataforma para su panel de control (19/11/2026). Todo sale de la base: no
 * hay indicadores calculados a mano ni datos de ejemplo.
 *
 * @param centros            centros vigentes por estado de su suscripcion
 * @param pedidosPendientes  pedidos de prueba gratis sin resolver
 * @param pruebasQueTerminan pruebas que terminan dentro del horizonte, las vencidas primero
 */
public record PanelDePlataforma(
		Centros centros,
		long pedidosPendientes,
		List<PruebaQueTermina> pruebasQueTerminan,
		List<CentroReciente> ultimosCentros,
		List<PedidoReciente> ultimosPedidos,
		List<CambioDeSuscripcion> ultimosCambios) {

	public PanelDePlataforma {
		pruebasQueTerminan = List.copyOf(pruebasQueTerminan);
		ultimosCentros = List.copyOf(ultimosCentros);
		ultimosPedidos = List.copyOf(ultimosPedidos);
		ultimosCambios = List.copyOf(ultimosCambios);
	}

	/**
	 * @param enPrueba        en prueba y todavia vigente
	 * @param pruebaPorVencer en prueba y termina dentro de la ventana corta (incluida en enPrueba)
	 * @param pruebaVencida   en prueba con el plazo cumplido: solo lectura hasta que se contrate
	 */
	public record Centros(long total, long activos, long enPrueba, long pruebaPorVencer, long pruebaVencida,
			long suspendidos, long cancelados) {
	}

	public record PruebaQueTermina(long organizationId, String nombre, Instant pruebaHasta) {
	}

	public record CentroReciente(long organizationId, String nombre, String estado, Instant creadoEn) {
	}

	public record PedidoReciente(long id, String nombreCentro, String persona, String estado, Instant recibidoEn) {
	}

	public record CambioDeSuscripcion(long organizationId, String nombre, String desde, String hacia,
			Instant cuando) {
	}
}
