package com.akine.clinical.application;

import com.akine.clinical.domain.PlanEvento;

import java.time.Instant;

/**
 * Una transicion del plan, tal como sale de la aplicacion (RF-M11-007).
 *
 * <p>{@link #detalle} nunca lleva contenido clinico: dice que se modificaron los objetivos, no
 * cuales. Lo que si lleva es {@link #numeroVersion}, que ata la modificacion a su contenido sin
 * copiarlo — quien quiera leerlo pasa por la consulta de versiones, con su propio acceso auditado.
 */
public record PlanEventoView(
		long id,
		String tipo,
		String estadoAnterior,
		String estadoNuevo,
		String motivo,
		String detalle,
		Integer numeroVersion,
		Instant ocurrioEn,
		long actorCuentaId) {

	public static PlanEventoView de(PlanEvento evento) {
		return new PlanEventoView(
				evento.getId(),
				evento.getTipo().name(),
				evento.getEstadoAnterior() == null ? null : evento.getEstadoAnterior().name(),
				evento.getEstadoNuevo().name(),
				evento.getMotivo(),
				evento.getDetalle(),
				evento.getNumeroVersion(),
				evento.getOcurrioEn(),
				evento.getActorCuentaId());
	}
}
