package com.akine.contracting.application;

import com.akine.contracting.domain.ConvenioArancel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un arancel tal como sale de la capa de aplicacion.
 *
 * <p>Mismo criterio que {@link ConvenioView}: {@code estado} y {@code vigente} son dos cosas y
 * viajan las dos. Un arancel ACTIVO cuya ventana ya paso es el historico que RN-M16-003 protege, y
 * la grilla de vigencias necesita mostrarlo con su periodo para que se entienda por que una
 * prestacion vieja se cobro a otro precio.
 */
public record ArancelView(
		long id,
		long convenioId,
		long practicaId,
		BigDecimal importeTotal,
		BigDecimal importeFinanciador,
		BigDecimal coseguro,
		String moneda,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean vigente,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static ArancelView de(ConvenioArancel arancel, LocalDate fecha) {
		return new ArancelView(
				arancel.getId(),
				arancel.getConvenioId(),
				arancel.getPracticaId(),
				arancel.getImporteTotal(),
				arancel.getImporteFinanciador(),
				arancel.getCoseguro(),
				arancel.getMoneda(),
				arancel.getVigenciaDesde(),
				arancel.getVigenciaHasta(),
				arancel.vigencia().cubre(fecha),
				arancel.isActive() ? "ACTIVO" : "INACTIVO",
				arancel.getDeletedAt(),
				arancel.getDeactivationReason(),
				arancel.getVersion());
	}
}
