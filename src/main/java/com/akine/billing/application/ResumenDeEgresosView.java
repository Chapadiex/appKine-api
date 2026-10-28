package com.akine.billing.application;

import com.akine.billing.domain.EstadoEgreso;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Los totales de egresos de un periodo, para la tira de la pantalla de egresos (DP-45).
 *
 * <p><b>Es egreso (M22), no caja.</b> {@code registrado} es lo que el centro se comprometio a pagar
 * —egresos {@code CONFIRMADO} y {@code PAGADO}—; {@code pagado} es la parte ya saldada <b>hoy</b>
 * ({@code importe_total - saldo_pendiente}: un pago anulado devuelve su importe), y {@code pendiente}
 * lo que falta. Ninguno es un movimiento de caja: un pago por transferencia salio sin tocar el
 * cajon. Los borradores no son compromiso y los anulados no cuentan: van aparte.
 *
 * <p>Una fila por moneda: un egreso tiene una sola, y sumar pesos con dolares no es un total.
 */
public record ResumenDeEgresosView(
		long consultorioId,
		LocalDate desde,
		LocalDate hasta,
		List<Totales> porMoneda,
		List<PorCategoria> porCategoria) {

	public record Totales(
			String moneda,
			BigDecimal registrado,
			BigDecimal pagado,
			BigDecimal pendiente,
			long cantidad,
			long cantidadBorrador,
			BigDecimal importeBorrador,
			long cantidadAnulados) {
	}

	public record PorCategoria(
			String moneda,
			String categoria,
			BigDecimal registrado,
			BigDecimal pagado,
			BigDecimal pendiente,
			long cantidad) {
	}

	/** Arma los totales desde las filas {@code [moneda, categoria, estado, cantidad, importe, saldo]}. */
	static ResumenDeEgresosView de(
			long consultorioId, LocalDate desde, LocalDate hasta, List<Object[]> filas) {

		Map<String, Acumulado> porMoneda = new LinkedHashMap<>();
		Map<String, Acumulado> porCategoria = new LinkedHashMap<>();

		for (Object[] fila : filas) {
			String moneda = (String) fila[0];
			String categoria = (String) fila[1];
			EstadoEgreso estado = EstadoEgreso.valueOf((String) fila[2]);
			long cantidad = ((Number) fila[3]).longValue();
			BigDecimal importe = (BigDecimal) fila[4];
			BigDecimal saldo = (BigDecimal) fila[5];

			Acumulado deMoneda = porMoneda.computeIfAbsent(moneda, m -> new Acumulado(m, null));
			deMoneda.sumar(estado, cantidad, importe, saldo);
			porCategoria.computeIfAbsent(moneda + "|" + categoria, c -> new Acumulado(moneda, categoria))
					.sumar(estado, cantidad, importe, saldo);
		}

		List<Totales> totales = new ArrayList<>();
		porMoneda.values().forEach(a -> totales.add(new Totales(
				a.moneda, a.registrado, a.registrado.subtract(a.saldoComprometido), a.saldoComprometido,
				a.cantidad, a.cantidadBorrador, a.importeBorrador, a.cantidadAnulados)));

		List<PorCategoria> categorias = new ArrayList<>();
		porCategoria.values().stream()
				.filter(a -> a.cantidad > 0)
				.forEach(a -> categorias.add(new PorCategoria(
						a.moneda, a.categoria, a.registrado,
						a.registrado.subtract(a.saldoComprometido), a.saldoComprometido, a.cantidad)));

		return new ResumenDeEgresosView(consultorioId, desde, hasta, totales, categorias);
	}

	private static final class Acumulado {

		private final String moneda;
		private final String categoria;
		private BigDecimal registrado = BigDecimal.ZERO;
		private BigDecimal saldoComprometido = BigDecimal.ZERO;
		private long cantidad;
		private long cantidadBorrador;
		private BigDecimal importeBorrador = BigDecimal.ZERO;
		private long cantidadAnulados;

		private Acumulado(String moneda, String categoria) {
			this.moneda = moneda;
			this.categoria = categoria;
		}

		private void sumar(EstadoEgreso estado, long n, BigDecimal importe, BigDecimal saldo) {
			switch (estado) {
				case CONFIRMADO, PAGADO -> {
					registrado = registrado.add(importe);
					saldoComprometido = saldoComprometido.add(saldo);
					cantidad += n;
				}
				case BORRADOR -> {
					cantidadBorrador += n;
					importeBorrador = importeBorrador.add(importe);
				}
				case ANULADO -> cantidadAnulados += n;
			}
		}
	}
}
