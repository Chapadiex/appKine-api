package com.akine.organization.api.dto;

import com.akine.organization.application.ContratacionView;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Lo que el centro tiene contratado: plan, servicios, adicionales y estado de la prueba")
public record ContratacionResponse(
		@Schema(description = "Codigo del plan", example = "CRECIMIENTO")
		String planCode,
		@Schema(description = "Nombre del plan", example = "Plan Intermedio")
		String planNombre,
		@Schema(description = "Tope de sedes del plan; ausente = sin tope", example = "3")
		Integer maxSedes,
		@Schema(description = "Tope de profesionales del plan (cuentas con rol PROFESIONAL); ausente = sin tope", example = "6")
		Integer maxProfesionales,
		@Schema(description = "Servicios contratados, al menos uno")
		List<Servicio> servicios,
		@Schema(description = "Adicionales contratados")
		List<Adicional> adicionales,
		@Schema(description = "Estado guardado de la suscripcion", example = "PRUEBA")
		SubscriptionStatus estado,
		@Schema(description = "Fin de la prueba gratis, instante UTC. Solo en PRUEBA")
		Instant pruebaHasta,
		@Schema(description = "El centro no puede cargar nada: suscripcion suspendida o prueba vencida. "
				+ "Las lecturas siguen")
		boolean soloLectura,
		@Schema(description = "El centro lo dio de alta o lo ajusto la plataforma. Sin esto es un centro "
				+ "autoregistrado o anterior al 18/11/2026, y tiene los dos servicios y la IA")
		boolean definidaPorPlataforma,
		@Schema(description = "El centro nacio del alta de la plataforma (en prueba gratis), no del "
				+ "autoregistro. Es el que recibe la guia de primeros pasos aunque despues contrate")
		boolean altaPorPlataforma,
		@Schema(description = "Version de la suscripcion, para cambiar la contratacion", example = "0")
		long version) {

	public static ContratacionResponse from(ContratacionView view) {
		return new ContratacionResponse(
				view.planCode(), view.planNombre(), view.maxSedes(), view.maxProfesionales(),
				view.servicios().stream().sorted().toList(),
				view.adicionales().stream().sorted().toList(),
				view.estado(), view.pruebaHasta(), view.soloLectura(), view.definidaPorPlataforma(),
				view.altaPorPlataforma(), view.version());
	}
}
