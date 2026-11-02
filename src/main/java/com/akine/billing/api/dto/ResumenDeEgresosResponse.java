package com.akine.billing.api.dto;

import com.akine.billing.application.ResumenDeEgresosView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Los totales de egresos de un periodo (DP-45). Es egreso, no caja: ver {@link ResumenDeEgresosView}.
 */
@Schema(name = "ResumenDeEgresos",
		description = "Totales de los egresos registrados en el periodo, por moneda y por categoria. "
				+ "`registrado` son los CONFIRMADO y PAGADO; `pagado` es lo saldado hoy y `pendiente` lo "
				+ "que falta. No es caja: un pago por transferencia no toco el cajon")
public record ResumenDeEgresosResponse(
		long consultorioId,
		@Schema(description = "Primer dia del periodo, en la zona de la sede", example = "2026-10-01")
		LocalDate desde,
		@Schema(description = "Ultimo dia del periodo, inclusive", example = "2026-10-31")
		LocalDate hasta,
		@Schema(description = "Una fila por moneda. Vacio si no hubo egresos en el periodo")
		List<TotalesResponse> porMoneda,
		@Schema(description = "Solo categorias con egresos confirmados o pagados")
		List<CategoriaResponse> porCategoria) {

	@Schema(name = "TotalesDeEgresos")
	public record TotalesResponse(
			@Schema(example = "ARS") String moneda,
			@Schema(description = "Importe de los egresos CONFIRMADO y PAGADO", example = "250000.00")
			BigDecimal registrado,
			@Schema(description = "Lo ya saldado, hoy", example = "180000.00") BigDecimal pagado,
			@Schema(description = "Lo que falta pagar de esos egresos", example = "70000.00") BigDecimal pendiente,
			@Schema(description = "Cantidad de egresos CONFIRMADO y PAGADO", example = "9") long cantidad,
			@Schema(description = "Borradores del periodo: todavia no son compromiso", example = "2")
			long cantidadBorrador,
			@Schema(example = "15000.00") BigDecimal importeBorrador,
			@Schema(example = "1") long cantidadAnulados) {
	}

	@Schema(name = "TotalesDeEgresosPorCategoria")
	public record CategoriaResponse(
			@Schema(example = "ARS") String moneda,
			@Schema(allowableValues = {"HONORARIOS_PROFESIONALES", "SUELDOS", "ALQUILER", "SERVICIOS",
					"INSUMOS", "IMPUESTOS", "MANTENIMIENTO", "OTRO"})
			String categoria,
			BigDecimal registrado,
			BigDecimal pagado,
			BigDecimal pendiente,
			long cantidad) {
	}

	public static ResumenDeEgresosResponse de(ResumenDeEgresosView v) {
		return new ResumenDeEgresosResponse(
				v.consultorioId(), v.desde(), v.hasta(),
				v.porMoneda().stream()
						.map(t -> new TotalesResponse(t.moneda(), t.registrado(), t.pagado(), t.pendiente(),
								t.cantidad(), t.cantidadBorrador(), t.importeBorrador(), t.cantidadAnulados()))
						.toList(),
				v.porCategoria().stream()
						.map(c -> new CategoriaResponse(c.moneda(), c.categoria(), c.registrado(), c.pagado(),
								c.pendiente(), c.cantidad()))
						.toList());
	}
}
