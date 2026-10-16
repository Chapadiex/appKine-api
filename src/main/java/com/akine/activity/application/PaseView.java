package com.akine.activity.application;

import com.akine.activity.domain.PaseServicio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un pase vendido, con su snapshot congelado.
 *
 * @param estado         el guardado: {@code ACTIVO}, {@code AGOTADO}, {@code VENCIDO} o {@code ANULADO}
 * @param tipo           {@code PACK} o {@code ABONO}
 * @param vigente        si hoy (en la zona de la sede) el pase puede cubrir una asistencia: activo, con
 *                       saldo y dentro de su vigencia. Se calcula al leer, asi que es exacto aunque el
 *                       job que escribe {@code VENCIDO} (08.07) todavia no haya pasado
 * @param creditosUsados los creditos tomados por asistencias: {@code -SUM(cantidad)} de los
 *                       {@code CONSUMO} y {@code DEVOLUCION} del ledger. Los ajustes manuales no
 *                       cuentan. Se calcula al leer
 * @param renovacionAutomatica solo abonos (AKINE-08.08): si el job va a vender el periodo siguiente
 * @param renuevaPaseId  el periodo de abono que este renueva, o {@code null} si lo vendio una persona
 * @param renovacionOmitidaMotivo por que el job no pudo renovar este periodo, o {@code null}
 * @param renovacionAutorizadaPor nombre de quien activo por ultima vez la renovacion automatica
 *                       (0.117.0). Apagarla lo conserva y el periodo renovado lo hereda. Se resuelve
 *                       al leer; {@code null} si nunca se activo o la cuenta no resuelve
 * @param renovadoPorPaseId el periodo siguiente que renovo a este, o {@code null} (0.117.0). Se
 *                       calcula al leer, por {@code uk_pase_servicio_renovacion}
 * @param anulable       si la anulacion lo aceptaria hoy: no anulado, sin creditos usados y con la
 *                       deuda de la venta sin cobros. Es la misma regla con la que
 *                       {@link AjusteDePaseService#anular} rechaza, calculada al leer
 */
public record PaseView(
		long id,
		long consultorioId,
		long personaId,
		long productoId,
		String tipo,
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
		int creditosUsados,
		boolean anulable,
		Instant vendidoEn,
		long vendidoPorCuentaId,
		Instant anuladoEn,
		Long anuladoPorCuentaId,
		String motivoAnulacion,
		boolean renovacionAutomatica,
		Long renuevaPaseId,
		String renovacionOmitidaMotivo,
		String renovacionAutorizadaPor,
		Long renovadoPorPaseId,
		long version) {

	/**
	 * Lo que se lee al armar la vista y no se persiste, porque es derivable: del ledger y de
	 * {@code billing} ({@code creditosUsados}, {@code anulable}), del propio pase
	 * ({@code renovadoPorPaseId}) y de {@code platform.spi.identity} ({@code renovacionAutorizadaPor}).
	 * Lo arma {@link LecturaDePases}, en lote.
	 */
	public record Uso(int creditosUsados, boolean conCobros, Long renovadoPorPaseId,
			String renovacionAutorizadaPor) {
	}

	public static PaseView de(PaseServicio p, LocalDate hoy, Uso uso) {
		return de(p, p.getCreditosDisponibles(), p.getEstado().name(), p.getVersion(), hoy, uso);
	}

	/** Con saldo y estado leidos del motor despues de un UPDATE nativo. */
	public static PaseView de(PaseServicio p, int saldo, String estado, long version, LocalDate hoy, Uso uso) {
		boolean vigente = "ACTIVO".equals(estado) && saldo > 0 && p.vigenteEl(hoy);
		boolean anulable = !"ANULADO".equals(estado) && uso.creditosUsados() <= 0 && !uso.conCobros();
		return new PaseView(
				p.getId(), p.getConsultorioId(), p.getPersonaId(), p.getProductoId(),
				p.getTipo().name(), p.getSnapshotNombre(), p.getSnapshotPrecio(), p.getSnapshotMoneda(),
				p.getSnapshotOfertaId(), p.getCreditosIniciales(), saldo, p.getVigenciaDesde(),
				p.getVigenciaHasta(), estado, vigente, uso.creditosUsados(), anulable, p.getVendidoEn(),
				p.getVendidoPorCuentaId(), p.getAnuladoEn(), p.getAnuladoPorCuentaId(),
				p.getMotivoAnulacion(), p.isRenovacionAutomatica(), p.getRenuevaPaseId(),
				p.getRenovacionOmitidaMotivo(), uso.renovacionAutorizadaPor(), uso.renovadoPorPaseId(),
				version);
	}

	/** La misma vista con la anulacion que acaba de escribir un UPDATE nativo. */
	public PaseView anulado(Instant en, long porCuentaId, String motivo) {
		return new PaseView(id, consultorioId, personaId, productoId, tipo, nombre, precio, moneda,
				ofertaId, creditosIniciales, creditosDisponibles, vigenciaDesde, vigenciaHasta, estado,
				vigente, creditosUsados, false, vendidoEn, vendidoPorCuentaId, en, porCuentaId, motivo,
				renovacionAutomatica, renuevaPaseId, renovacionOmitidaMotivo, renovacionAutorizadaPor,
				renovadoPorPaseId, version);
	}

	/**
	 * La misma vista con la renovacion que acaba de escribir un UPDATE nativo (AKINE-08.08).
	 *
	 * @param autorizadaPor el nombre de quien la autorizo despues del UPDATE: quien la prende, o el
	 *                      ultimo que la habia prendido si se apaga
	 */
	public PaseView conRenovacion(boolean activa, long nuevaVersion, String autorizadaPor) {
		return new PaseView(id, consultorioId, personaId, productoId, tipo, nombre, precio, moneda,
				ofertaId, creditosIniciales, creditosDisponibles, vigenciaDesde, vigenciaHasta, estado,
				vigente, creditosUsados, anulable, vendidoEn, vendidoPorCuentaId, anuladoEn,
				anuladoPorCuentaId, motivoAnulacion, activa, renuevaPaseId, null, autorizadaPor,
				renovadoPorPaseId, nuevaVersion);
	}
}
