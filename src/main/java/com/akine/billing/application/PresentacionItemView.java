package com.akine.billing.application;

import com.akine.billing.domain.EstadoItemPresentacion;
import com.akine.billing.domain.PresentacionItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Una prestacion dentro del lote, tal como sale del backend.
 *
 * <p><b>PHI minima</b> (RNF-M21-001 y §32): viaja el {@code personaId} y el concepto congelado, y
 * <b>no</b> el nombre del paciente ni su documento. El lote que se le manda al financiador si los
 * lleva; el listado de la pantalla no los necesita, y exponerlos ahi seria filtrar el padron entero
 * a cualquiera que pueda ver una bandeja.
 */
public record PresentacionItemView(
		long id,
		long obligacionId,
		EstadoItemPresentacion estado,
		BigDecimal importePresentado,
		BigDecimal importeDebitado,
		String motivoDebito,
		Instant debitadoEn,
		long personaId,
		long sesionId,
		LocalDate fechaPrestacion,
		String concepto,
		Instant incluidoEn,
		long version) {

	public static PresentacionItemView de(PresentacionItem item) {
		return new PresentacionItemView(
				item.getId(),
				item.getObligacionId(),
				item.getEstado(),
				item.getImportePresentado(),
				item.getImporteDebitado(),
				item.getMotivoDebito(),
				item.getDebitadoEn(),
				item.getSnapshotPersonaId(),
				item.getSnapshotSesionId(),
				item.getSnapshotFechaPrestacion(),
				item.getSnapshotConcepto(),
				item.getIncluidoEn(),
				item.getVersion());
	}
}
