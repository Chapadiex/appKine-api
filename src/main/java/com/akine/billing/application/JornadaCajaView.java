package com.akine.billing.application;

import com.akine.billing.domain.JornadaCaja;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Una jornada de caja tal como se lee.
 *
 * <p>{@code saldoTeorico} es el saldo <b>en efectivo</b> que deberia haber en el cajon ahora
 * (RF-M20-005). No incluye tarjetas ni transferencias: esa plata nunca estuvo ahi, y sumarla haria
 * que el conteo no cuadrara nunca. Lo que entro por los otros medios se ve en
 * {@code totalesPorMedio}.
 *
 * <p>Todos los importes son {@link BigDecimal}: ningun numero de punto flotante toca este camino.
 *
 * @param saldoTeorico       el arqueable de ahora. En una jornada cerrada queda igual al congelado
 * @param diferencia         {@code declarado - teorico} al cerrar. Positiva sobra, negativa falta
 * @param totalesPorMedio    vacio en los listados; poblado en el detalle
 */
public record JornadaCajaView(
		long id,
		long consultorioId,
		LocalDate fechaNegocio,
		String moneda,
		String estado,
		BigDecimal saldoInicial,
		BigDecimal saldoTeorico,
		Instant abiertaEn,
		long abiertaPorCuentaId,
		Instant cerradaEn,
		Long cerradaPorCuentaId,
		BigDecimal saldoTeoricoCierre,
		BigDecimal saldoDeclarado,
		BigDecimal diferencia,
		String motivoDiferencia,
		List<TotalPorMedio> totalesPorMedio) {

	/**
	 * Cuanto movio cada medio en esta jornada, con signo.
	 *
	 * <p>Existe para que la pantalla pueda decir "120.000 en total, de los cuales 60.000 por
	 * tarjeta y no estan en el cajon". Sin este desglose, un arqueo de 60.000 sobre un dia de
	 * 120.000 parece un faltante gigante.
	 */
	public record TotalPorMedio(String medio, BigDecimal total, boolean afectaArqueo) {
	}

	public static JornadaCajaView de(JornadaCaja jornada) {
		return de(jornada, List.of());
	}

	public static JornadaCajaView de(JornadaCaja jornada, List<TotalPorMedio> totales) {
		return new JornadaCajaView(
				jornada.getId(),
				jornada.getConsultorioId(),
				jornada.getFechaNegocio(),
				jornada.getMoneda(),
				jornada.getEstado().name(),
				jornada.getSaldoInicial(),
				jornada.getSaldoArqueo(),
				jornada.getAbiertaEn(),
				jornada.getAbiertaPorCuentaId(),
				jornada.getCerradaEn(),
				jornada.getCerradaPorCuentaId(),
				jornada.getSaldoTeoricoCierre(),
				jornada.getSaldoDeclarado(),
				jornada.getDiferencia(),
				jornada.getMotivoDiferencia(),
				List.copyOf(totales));
	}
}
