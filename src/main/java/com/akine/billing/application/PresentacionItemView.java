package com.akine.billing.application;

import com.akine.billing.domain.EstadoItemPresentacion;
import com.akine.billing.domain.PresentacionItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Una prestacion dentro del lote, tal como sale del backend.
 *
 * <p><b>PHI.</b> El listado de lotes y la bandeja de elegibles no traen renglones. El
 * <b>detalle</b> de un lote si trae, desde el paso 3 de obras sociales simples, lo que la obra
 * social pide para liquidar: nombre, documento, afiliado, plan, practica y autorizacion
 * ({@link RenglonesLegibles}). Ningun dato clinico. Se calculan al leer: en un renglon armado con
 * {@link #de} vienen nulos.
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
		long version,
		String pacienteNombre,
		String pacienteDocumento,
		String numeroAfiliado,
		String planNombre,
		String practicaCodigo,
		String practicaNombre,
		String autorizacionNumero) {

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
				item.getVersion(),
				null, null, null, null, null, null, null);
	}

	/** El mismo renglon con los datos que lo vuelven legible para la obra social. */
	@SuppressWarnings("java:S107")
	public PresentacionItemView conDatos(
			String pacienteNombre,
			String pacienteDocumento,
			String numeroAfiliado,
			String planNombre,
			String practicaCodigo,
			String practicaNombre,
			String autorizacionNumero) {
		return new PresentacionItemView(id, obligacionId, estado, importePresentado,
				importeDebitado, motivoDebito, debitadoEn, personaId, sesionId, fechaPrestacion,
				concepto, incluidoEn, version, pacienteNombre, pacienteDocumento, numeroAfiliado,
				planNombre, practicaCodigo, practicaNombre, autorizacionNumero);
	}
}
