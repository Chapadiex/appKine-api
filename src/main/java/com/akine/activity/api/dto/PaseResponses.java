package com.akine.activity.api.dto;

import com.akine.activity.application.MovimientoPaseView;
import com.akine.activity.application.PaseDetalleView;
import com.akine.activity.application.PaseView;
import com.akine.activity.application.ProductoView;
import com.akine.activity.application.ResultadoDeAnulacion;
import com.akine.activity.application.ResultadoDeVenta;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

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
			@Schema(description = "`PACK`: creditos que valen `vigenciaDias` desde la compra. `ABONO` "
					+ "(desde 0.109.0): `creditos` son las asistencias que permite cada periodo y "
					+ "`vigenciaDias` la duracion maxima de un periodo, que se elige al vender",
					allowableValues = {"PACK", "ABONO"}, example = "PACK") String tipo,
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
			@Schema(description = "`PACK` o `ABONO` (desde 0.109.0). Un abono cubre el periodo "
					+ "`vigenciaDesde`..`vigenciaHasta` elegido al venderlo",
					allowableValues = {"PACK", "ABONO"}, example = "PACK") String tipo,
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
			@Schema(description = "Creditos tomados por asistencias: el neto de los `CONSUMO` y `DEVOLUCION` "
					+ "del ledger; los ajustes manuales no cuentan. Calculado al leer. Aditivo desde 0.111.0",
					example = "1") int creditosUsados,
			@Schema(description = "true si `POST .../anulacion` lo aceptaria hoy: no esta `ANULADO`, no tiene "
					+ "creditos usados y la deuda de su venta no tiene cobros imputados. Es la misma regla con "
					+ "la que la anulacion responde `pase-no-ajustable` (`causa = CREDITOS_USADOS`) o "
					+ "`pase-con-cobros`, calculada al leer. Aditivo desde 0.111.0", example = "false")
			boolean anulable,
			Instant vendidoEn,
			@Schema(example = "3") long vendidoPorCuentaId,
			@Schema(description = "Cuando se anulo. Presente solo con `estado = ANULADO`") Instant anuladoEn,
			@Schema(description = "Quien lo anulo") Long anuladoPorCuentaId,
			@Schema(description = "Por que se anulo") String motivoAnulacion,
			@Schema(description = "Solo abonos (desde 0.116.0): true si el sistema va a vender el periodo "
					+ "siguiente al acercarse el fin de este. Se apaga cuando se renueva (el periodo nuevo "
					+ "la hereda) o cuando no se pudo renovar (`renovacionOmitidaMotivo`). Aditivo",
					example = "false") boolean renovacionAutomatica,
			@Schema(description = "El periodo de abono que este renueva; ausente si lo vendio una "
					+ "persona. Aditivo desde 0.116.0", example = "76") Long renuevaPaseId,
			@Schema(description = "Por que el sistema no pudo renovar este periodo (producto de baja, "
					+ "persona de baja, periodo ocupado). Ausente si no se intento o se renovo. Aditivo "
					+ "desde 0.116.0") String renovacionOmitidaMotivo,
			@Schema(description = "Version para el control optimista del ajuste, la anulacion y la "
					+ "renovacion", example = "1") long version) {

		public static Pase de(PaseView v) {
			return new Pase(v.id(), v.consultorioId(), v.personaId(), v.productoId(), v.tipo(),
					v.nombre(), v.precio(), v.moneda(), v.ofertaId(), v.creditosIniciales(),
					v.creditosDisponibles(), v.vigenciaDesde(), v.vigenciaHasta(), v.estado(),
					v.vigente(), v.creditosUsados(), v.anulable(), v.vendidoEn(), v.vendidoPorCuentaId(),
					v.anuladoEn(),
					v.anuladoPorCuentaId(), v.motivoAnulacion(), v.renovacionAutomatica(), v.renuevaPaseId(),
					v.renovacionOmitidaMotivo(), v.version());
		}
	}

	@Schema(
			name = "PaginaDeMovimientosDePase",
			description = "Una pagina del ledger de un pase, del movimiento mas viejo al mas nuevo "
					+ "(desde 0.116.0). Misma forma que el resto de los listados paginados de la API.")
	public record PaginaDeMovimientos(
			@Schema(description = "Movimientos de esta pagina") List<Movimiento> content,
			@Schema(description = "Numero de pagina devuelta, base cero", example = "0") int page,
			@Schema(description = "Cantidad de elementos por pagina", example = "20") int size,
			@Schema(description = "Cantidad total de movimientos del pase", example = "3") long totalElements,
			@Schema(description = "Cantidad total de paginas disponibles", example = "1") int totalPages) {

		public static PaginaDeMovimientos de(Page<MovimientoPaseView> pagina) {
			return new PaginaDeMovimientos(
					pagina.getContent().stream().map(Movimiento::de).toList(),
					pagina.getNumber(), pagina.getSize(), pagina.getTotalElements(), pagina.getTotalPages());
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
			@Schema(description = "Para VENTA, el pase; para ASISTENCIA, el evento de asistencia; para "
					+ "CANCELACION, la clase cancelada; para AJUSTE_MANUAL y VENCIMIENTO, la version del "
					+ "pase sobre la que se aplico", example = "901") long origenId,
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

	@Schema(
			name = "ResultadoDeAnulacionDePase",
			description = "El pase ANULADO y la deuda de su venta, tambien anulada (no borrada).")
	public record ResultadoAnulacion(
			Pase pase,
			@Schema(description = "La obligacion de la venta despues de anularla. Ausente si la venta "
					+ "no tenia deuda") Deuda deuda,
			@Schema(description = "false si el pase ya estaba anulado y no se escribio nada", example = "true")
			boolean anulado) {

		public static ResultadoAnulacion de(ResultadoDeAnulacion r) {
			ResultadoDeVenta.Deuda d = r.deuda();
			return new ResultadoAnulacion(Pase.de(r.pase()),
					d == null ? null : new Deuda(d.obligacionId(), d.importe(), d.saldo(), d.moneda(), d.estado()),
					r.anulado());
		}
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
