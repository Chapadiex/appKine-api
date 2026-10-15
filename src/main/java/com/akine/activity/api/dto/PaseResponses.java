package com.akine.activity.api.dto;

import com.akine.activity.application.MovimientoPaseView;
import com.akine.activity.application.PaseDetalleView;
import com.akine.activity.application.PaseView;
import com.akine.activity.application.ProductoView;
import com.akine.activity.application.ResultadoDeVenta;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Respuestas de productos de pack y pases (AKINE-08.06/08.07). */
public final class PaseResponses {

	private PaseResponses() {
	}

	@Schema(name = "Producto", description = "Producto de pack del catalogo de una sede (RF-M29-001).")
	public record Producto(
			@Schema(example = "12") long id,
			@Schema(example = "7") long consultorioId,
			@Schema(example = "42") long ofertaId,
			@Schema(example = "Pack Pilates 8 clases") String nombre,
			String descripcion,
			@Schema(description = "Hoy solo `PACK`; `ABONO` es de 08.08", allowableValues = {"PACK", "ABONO"},
					example = "PACK") String tipo,
			@Schema(example = "8") int creditos,
			@Schema(example = "40000.00") BigDecimal precio,
			@Schema(example = "ARS") String moneda,
			@Schema(description = "Dias corridos que vale un pase desde la compra", example = "60")
			int vigenciaDias,
			@Schema(description = "Desde cuando se vende") LocalDate vigenciaDesde,
			@Schema(description = "Hasta cuando se vende, inclusive. Ausente: sin fin") LocalDate vigenciaHasta,
			@Schema(description = "false tras la baja: no se vende mas", example = "true") boolean active,
			Instant deletedAt,
			String deactivationReason,
			@Schema(description = "Version para el control optimista", example = "0") long version) {

		public static Producto de(ProductoView v) {
			return new Producto(v.id(), v.consultorioId(), v.ofertaId(), v.nombre(), v.descripcion(),
					v.tipo(), v.creditos(), v.precio(), v.moneda(), v.vigenciaDias(),
					v.vigenciaDesde(), v.vigenciaHasta(), v.active(), v.deletedAt(),
					v.deactivationReason(), v.version());
		}
	}

	@Schema(
			name = "Pase",
			description = "Pase comprado por una Persona (RF-M29-002). Nombre, precio, moneda, "
					+ "creditos y oferta son el **snapshot de la venta**: editar el producto no los cambia.")
	public record Pase(
			@Schema(example = "77") long id,
			@Schema(example = "7") long consultorioId,
			@Schema(example = "512") long personaId,
			@Schema(example = "12") long productoId,
			@Schema(example = "Pack Pilates 8 clases") String nombre,
			@Schema(example = "40000.00") BigDecimal precio,
			@Schema(example = "ARS") String moneda,
			@Schema(description = "La oferta cuyas clases consumen estos creditos", example = "42") long ofertaId,
			@Schema(example = "8") int creditosIniciales,
			@Schema(description = "Saldo vigente. Nunca negativo (RN-M29-008)", example = "7") int creditosDisponibles,
			LocalDate vigenciaDesde,
			@Schema(description = "Ultimo dia en que vale, inclusive") LocalDate vigenciaHasta,
			@Schema(allowableValues = {"ACTIVO", "AGOTADO", "VENCIDO", "ANULADO"}, example = "ACTIVO")
			String estado,
			@Schema(description = "true si hoy, en la zona de la sede, puede cubrir una asistencia: "
					+ "activo, con saldo y dentro de su vigencia", example = "true") boolean vigente,
			Instant vendidoEn,
			@Schema(example = "3") long vendidoPorCuentaId,
			@Schema(example = "1") long version) {

		public static Pase de(PaseView v) {
			return new Pase(v.id(), v.consultorioId(), v.personaId(), v.productoId(), v.nombre(),
					v.precio(), v.moneda(), v.ofertaId(), v.creditosIniciales(),
					v.creditosDisponibles(), v.vigenciaDesde(), v.vigenciaHasta(), v.estado(),
					v.vigente(), v.vendidoEn(), v.vendidoPorCuentaId(), v.version());
		}
	}

	@Schema(
			name = "MovimientoPase",
			description = "Movimiento inmutable del ledger de creditos (RN-M29-003). `cantidad` lleva "
					+ "signo; la suma del ledger es el saldo.")
	public record Movimiento(
			@Schema(example = "301") long id,
			@Schema(allowableValues = {"COMPRA", "CONSUMO", "AJUSTE", "DEVOLUCION", "VENCIMIENTO"},
					example = "CONSUMO") String tipo,
			@Schema(example = "-1") int cantidad,
			@Schema(example = "7") int saldoResultante,
			@Schema(allowableValues = {"VENTA", "ASISTENCIA", "CANCELACION", "AJUSTE_MANUAL", "VENCIMIENTO"},
					example = "ASISTENCIA") String origenTipo,
			@Schema(description = "Para VENTA, el pase; para ASISTENCIA, el evento de asistencia",
					example = "901") long origenId,
			@Schema(description = "La asistencia que lo produjo, si la hay", example = "455") Long asistenciaId,
			Long actorCuentaId,
			String motivo,
			Instant ocurridoEn) {

		public static Movimiento de(MovimientoPaseView v) {
			return new Movimiento(v.id(), v.tipo(), v.cantidad(), v.saldoResultante(), v.origenTipo(),
					v.origenId(), v.asistenciaId(), v.actorCuentaId(), v.motivo(), v.ocurridoEn());
		}
	}

	@Schema(name = "PaseDetalle", description = "Un pase con su ledger, del movimiento mas viejo al mas nuevo.")
	public record Detalle(Pase pase, List<Movimiento> movimientos) {

		public static Detalle de(PaseDetalleView v) {
			return new Detalle(Pase.de(v.pase()), v.movimientos().stream().map(Movimiento::de).toList());
		}
	}

	@Schema(
			name = "DeudaDeVenta",
			description = "La obligacion que devengo la venta (RF-M18-009). **No es un cobro**: se paga "
					+ "con `POST /cobros`.")
	public record Deuda(
			@Schema(example = "9001") long obligacionId,
			@Schema(example = "40000.00") BigDecimal importe,
			@Schema(example = "40000.00") BigDecimal saldo,
			@Schema(example = "ARS") String moneda,
			@Schema(allowableValues = {"PENDIENTE", "PARCIAL", "PAGADA", "ANULADA"}, example = "PENDIENTE")
			String estado) {
	}

	@Schema(name = "ResultadoDeVenta", description = "El pase vendido y su deuda, sin cobro.")
	public record ResultadoVenta(
			Pase pase,
			Deuda deuda,
			@Schema(description = "false si fue un reintento con la misma clave", example = "true")
			boolean creada) {

		public static ResultadoVenta de(ResultadoDeVenta r) {
			ResultadoDeVenta.Deuda d = r.deuda();
			return new ResultadoVenta(Pase.de(r.pase()),
					new Deuda(d.obligacionId(), d.importe(), d.saldo(), d.moneda(), d.estado()),
					r.creada());
		}
	}
}
