package com.akine.encounter.application;

import com.akine.encounter.domain.Sesion;

import java.time.Instant;

/**
 * Una sesion abierta.
 *
 * @param borrador contenido opaco tal como se guardo. Ver la cabecera de {@code Sesion}
 * @param version  la que el cliente tiene que devolver al guardar. <b>Es el autosave</b>: sin ella
 *                 dos pestanas del mismo profesional se pisan en silencio
 */
public record SesionView(
		long id,
		long consultorioId,
		long historiaClinicaId,
		Long turnoId,
		long ofertaId,
		long profesionalId,
		String estado,
		Instant iniciadaEn,
		String borrador,
		Instant borradorGuardadoEn,
		long version) {

	public static SesionView de(Sesion sesion) {
		return new SesionView(
				sesion.getId(),
				sesion.getConsultorioId(),
				sesion.getHistoriaClinicaId(),
				sesion.getTurnoId(),
				sesion.getOfertaId(),
				sesion.getProfesionalMembershipId(),
				sesion.getEstado().name(),
				sesion.getIniciadaEn(),
				sesion.getBorrador(),
				sesion.getBorradorGuardadoEn(),
				sesion.getVersion());
	}
}
