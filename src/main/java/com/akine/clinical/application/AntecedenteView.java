package com.akine.clinical.application;

import com.akine.clinical.domain.AntecedenteClinico;

import java.time.Instant;

/**
 * Un antecedente tal como sale del servicio.
 *
 * <p>Trae tambien los campos de la baja porque los antecedentes dados de baja siguen siendo
 * consultables: la regla maestra 10 no admite que un dato clinico desaparezca, y una vista que
 * ocultara el motivo dejaria al historico sin poder explicarse.
 */
public record AntecedenteView(
		long id,
		String tipo,
		String descripcion,
		Instant registradoEn,
		long registradoPor,
		boolean vigente,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static AntecedenteView de(AntecedenteClinico antecedente) {
		return new AntecedenteView(
				antecedente.getId(),
				antecedente.getTipo().name(),
				antecedente.getDescripcion(),
				antecedente.getRegistradoEn(),
				antecedente.getRegistradoPor(),
				antecedente.isVigente(),
				antecedente.getDeletedAt(),
				antecedente.getDeactivationReason(),
				antecedente.getVersion());
	}
}
