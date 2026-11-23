package com.akine.organization.application;

import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;

import java.time.Instant;
import java.util.Set;

/**
 * Lo que un centro tiene contratado (18/11/2026).
 *
 * @param estado      el estado guardado de la suscripcion ({@code PRUEBA}, {@code ACTIVA}...)
 * @param pruebaHasta fin de la prueba gratis; {@code null} fuera de prueba
 * @param soloLectura la suscripcion no admite cargas: suspendida o prueba vencida
 * @param maxSedes    tope de sedes del plan; {@code null} = sin tope
 * @param definidaPorPlataforma el centro lo dio de alta (o lo ajusto) la plataforma; si no, es un
 *                    centro autoregistrado o anterior al 18/11/2026 y tiene todo
 * @param altaPorPlataforma el centro nacio del alta de la plataforma (en prueba), no del
 *                    autoregistro; es el que recibe la guia de primeros pasos
 */
public record ContratacionView(
		long organizationId,
		String planCode,
		String planNombre,
		Integer maxSedes,
		Set<Servicio> servicios,
		Set<Adicional> adicionales,
		SubscriptionStatus estado,
		Instant pruebaHasta,
		boolean soloLectura,
		boolean definidaPorPlataforma,
		boolean altaPorPlataforma,
		long version) {

	public ContratacionView {
		servicios = Set.copyOf(servicios);
		adicionales = Set.copyOf(adicionales);
	}
}
