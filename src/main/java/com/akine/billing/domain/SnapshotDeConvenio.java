package com.akine.billing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * La copia del convenio y del arancel que se aplicaron a una prestacion (AKINE F-4,
 * RN-M16-003/004/008).
 *
 * <p>Es lo que {@code billing} guarda de {@code contracting.spi.ArancelCongelado} y de la cobertura
 * del paciente: un valor, no un puntero. Se escribe una vez al devengar y despues <b>nadie vuelve a
 * preguntarle nada a {@code contracting}</b>: subir el arancel en enero no puede cambiar lo que se
 * le reclamo al financiador en diciembre.
 *
 * <p>Los tres {@code requeria*} son los requisitos documentales del convenio el dia de la
 * prestacion: es lo que RF-M21-003 necesita para validar un lote.
 *
 * @param coberturaId       la cobertura del paciente que se aplico
 * @param credencialVencida la credencial estaba vencida ese dia. Alerta, no excluye (B-2)
 * @param vigenteEl         dia de la prestacion en la zona de la sede, contra el que se resolvio
 * @param capturadoEn       instante UTC en que se congelo
 */
public record SnapshotDeConvenio(
		long convenioId,
		String convenioCodigo,
		String convenioNombre,
		long planId,
		long arancelId,
		long practicaId,
		long coberturaId,
		BigDecimal importeTotal,
		BigDecimal importeFinanciador,
		BigDecimal coseguro,
		boolean requeriaOrden,
		boolean requeriaAutorizacion,
		boolean requeriaCredencial,
		boolean credencialVencida,
		LocalDate vigenteEl,
		Instant capturadoEn) {

	public SnapshotDeConvenio {
		Objects.requireNonNull(convenioCodigo, "convenioCodigo");
		Objects.requireNonNull(convenioNombre, "convenioNombre");
		Objects.requireNonNull(importeTotal, "importeTotal");
		Objects.requireNonNull(importeFinanciador, "importeFinanciador");
		Objects.requireNonNull(coseguro, "coseguro");
		Objects.requireNonNull(vigenteEl, "vigenteEl");
		Objects.requireNonNull(capturadoEn, "capturadoEn");
		if (importeFinanciador.add(coseguro).compareTo(importeTotal) != 0) {
			// ck_arancel_partes_suman_total (V43) lo garantiza en el origen y
			// ck_obligacion_snapshot_partes_suman (V77) en la copia. Aca se atrapa antes para
			// decir cual es el problema en vez de dejar reventar una constraint.
			throw new IllegalArgumentException("Las partes del arancel no suman el total: "
					+ importeFinanciador + " + " + coseguro + " != " + importeTotal);
		}
	}

	/** La parte que le toca a cada concepto. PARTICULAR no tiene parte en un convenio. */
	public BigDecimal parteDe(ConceptoObligacion concepto) {
		return switch (concepto) {
			case FINANCIADOR -> importeFinanciador;
			case COSEGURO -> coseguro;
			case PARTICULAR -> throw new IllegalArgumentException(
					"Una obligacion particular no sale de un convenio");
		};
	}
}
