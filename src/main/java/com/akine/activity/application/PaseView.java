package com.akine.activity.application;

import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.billing.spi.DevengoDeVentas;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
		long version) {

	/**
	 * Lo que se lee del ledger y de {@code billing} para derivar {@code creditosUsados} y
	 * {@code anulable}. No se persiste: es derivable.
	 */
	public record Uso(int creditosUsados, boolean conCobros) {

		/** Un pase recien vendido: nada consumido y la deuda recien devengada. */
		public static final Uso SIN_USO = new Uso(0, false);

		/** El uso de varios pases, con una consulta al ledger y una a {@code billing}. */
		static Map<Long, Uso> de(MovimientoPaseRepositoryPort movimientos, DevengoDeVentas ventas,
				long organizationId, Collection<Long> paseIds) {

			Map<Long, Integer> neto = movimientos.consumoNetoPorPase(organizationId, paseIds);
			Set<Long> conCobros = ventas.ventasConCobros(organizationId, paseIds);
			Map<Long, Uso> usos = new HashMap<>();
			for (Long paseId : paseIds) {
				usos.put(paseId, new Uso(-neto.getOrDefault(paseId, 0), conCobros.contains(paseId)));
			}
			return usos;
		}

		/** El uso de un pase. */
		static Uso de(MovimientoPaseRepositoryPort movimientos, DevengoDeVentas ventas,
				long organizationId, long paseId) {

			return de(movimientos, ventas, organizationId, Set.of(paseId)).get(paseId);
		}
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
				p.getMotivoAnulacion(), version);
	}

	/** La misma vista con la anulacion que acaba de escribir un UPDATE nativo. */
	public PaseView anulado(Instant en, long porCuentaId, String motivo) {
		return new PaseView(id, consultorioId, personaId, productoId, tipo, nombre, precio, moneda,
				ofertaId, creditosIniciales, creditosDisponibles, vigenciaDesde, vigenciaHasta, estado,
				vigente, creditosUsados, false, vendidoEn, vendidoPorCuentaId, en, porCuentaId, motivo,
				version);
	}
}
