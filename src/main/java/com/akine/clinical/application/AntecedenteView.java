package com.akine.clinical.application;

import com.akine.clinical.domain.AntecedenteClinico;

import java.time.Instant;
import java.util.Map;

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
		long version,
		String origen,
		String cargadoPorNombre,
		Long revisadoPor,
		String revisadoPorNombre,
		Instant revisadoEn,
		String zona,
		Short anio,
		String lugar,
		String codigo) {

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
				antecedente.getVersion(),
				antecedente.getOrigen().name(),
				null,
				antecedente.getRevisadoPor(),
				null,
				antecedente.getRevisadoEn(),
				antecedente.getZona(),
				antecedente.getAnio(),
				antecedente.getLugar(),
				antecedente.getCodigo() == null ? null : antecedente.getCodigo().name());
	}

	/** La misma vista con los nombres de quien lo cargo y quien lo reviso (DP-47). Ausente = null. */
	public AntecedenteView conNombres(Map<Long, String> nombres) {
		return new AntecedenteView(id, tipo, descripcion, registradoEn, registradoPor, vigente, deletedAt,
				deactivationReason, version, origen, nombres.get(registradoPor), revisadoPor,
				revisadoPor == null ? null : nombres.get(revisadoPor), revisadoEn, zona, anio, lugar, codigo);
	}
}
