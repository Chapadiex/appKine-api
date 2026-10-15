package com.akine.activity.application;

import com.akine.activity.domain.PaseServicio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un pase vendido, con su snapshot congelado.
 *
 * @param estado  el guardado: {@code ACTIVO}, {@code AGOTADO}, {@code VENCIDO} o {@code ANULADO}
 * @param vigente si hoy (en la zona de la sede) el pase puede cubrir una asistencia: activo, con
 *                saldo y dentro de su vigencia. El vencimiento por fecha se evalua al leer: el job
 *                que escribe {@code VENCIDO} es de 08.07
 */
public record PaseView(
		long id,
		long consultorioId,
		long personaId,
		long productoId,
		String nombre,
		BigDecimal precio,
		String moneda,
		long ofertaId,
		int creditosIniciales,
		int creditosDisponibles,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		String estado,
		boolean vigente,
		Instant vendidoEn,
		long vendidoPorCuentaId,
		long version) {

	public static PaseView de(PaseServicio p, LocalDate hoy) {
		return de(p, p.getCreditosDisponibles(), p.getEstado().name(), p.getVersion(), hoy);
	}

	/** Con saldo y estado leidos del motor despues de un UPDATE nativo. */
	public static PaseView de(PaseServicio p, int saldo, String estado, long version, LocalDate hoy) {
		boolean vigente = "ACTIVO".equals(estado) && saldo > 0 && p.vigenteEl(hoy);
		return new PaseView(
				p.getId(), p.getConsultorioId(), p.getPersonaId(), p.getProductoId(),
				p.getSnapshotNombre(), p.getSnapshotPrecio(), p.getSnapshotMoneda(),
				p.getSnapshotOfertaId(), p.getCreditosIniciales(), saldo, p.getVigenciaDesde(),
				p.getVigenciaHasta(), estado, vigente, p.getVendidoEn(), p.getVendidoPorCuentaId(),
				version);
	}
}
