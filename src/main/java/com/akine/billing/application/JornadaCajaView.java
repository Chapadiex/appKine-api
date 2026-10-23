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
 * @param resumen            {@code null} en los listados; poblado en el detalle (DP-45)
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
		List<TotalPorMedio> totalesPorMedio,
		Resumen resumen) {

	/**
	 * Cuanto movio cada medio en esta jornada, con signo.
	 *
	 * <p>Existe para que la pantalla pueda decir "120.000 en total, de los cuales 60.000 por
	 * tarjeta y no estan en el cajon". Sin este desglose, un arqueo de 60.000 sobre un dia de
	 * 120.000 parece un faltante gigante.
	 *
	 * @param ingresos {@code INGRESO - REVERSION_DE_INGRESO} del medio (DP-45)
	 * @param egresos  {@code EGRESO - REVERSION_DE_EGRESO} del medio (DP-45)
	 */
	public record TotalPorMedio(
			String medio, BigDecimal total, boolean afectaArqueo,
			BigDecimal ingresos, BigDecimal egresos, long cantidad) {
	}

	/**
	 * Los totales de la tira de la caja (DP-45): lo que entro y salio en la jornada, separado en
	 * efectivo —lo que se arquea— y los demas medios.
	 *
	 * <p><b>Es caja, no cobro ni deuda</b> (regla maestra 7): suma {@code movimiento_caja} de esta
	 * jornada y nada mas. Las reversiones restan de lo que revierten —un ingreso anulado no es un
	 * egreso—, asi que para el efectivo se cumple
	 * {@code saldoInicial + efectivoIngresos - efectivoEgresos = saldoTeorico}.
	 */
	public record Resumen(
			BigDecimal ingresos,
			BigDecimal egresos,
			BigDecimal efectivoIngresos,
			BigDecimal efectivoEgresos,
			BigDecimal otrosMediosIngresos,
			BigDecimal otrosMediosEgresos,
			long cantidadMovimientos) {

		static Resumen de(List<TotalPorMedio> totales) {
			BigDecimal efectivoIngresos = BigDecimal.ZERO;
			BigDecimal efectivoEgresos = BigDecimal.ZERO;
			BigDecimal otrosIngresos = BigDecimal.ZERO;
			BigDecimal otrosEgresos = BigDecimal.ZERO;
			long cantidad = 0;
			for (TotalPorMedio total : totales) {
				if (total.afectaArqueo()) {
					efectivoIngresos = efectivoIngresos.add(total.ingresos());
					efectivoEgresos = efectivoEgresos.add(total.egresos());
				} else {
					otrosIngresos = otrosIngresos.add(total.ingresos());
					otrosEgresos = otrosEgresos.add(total.egresos());
				}
				cantidad += total.cantidad();
			}
			return new Resumen(
					efectivoIngresos.add(otrosIngresos), efectivoEgresos.add(otrosEgresos),
					efectivoIngresos, efectivoEgresos, otrosIngresos, otrosEgresos, cantidad);
		}
	}

	public static JornadaCajaView de(JornadaCaja jornada) {
		return armar(jornada, List.of(), null);
	}

	public static JornadaCajaView de(JornadaCaja jornada, List<TotalPorMedio> totales) {
		return armar(jornada, totales, Resumen.de(totales));
	}

	private static JornadaCajaView armar(
			JornadaCaja jornada, List<TotalPorMedio> totales, Resumen resumen) {
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
				List.copyOf(totales),
				resumen);
	}
}
