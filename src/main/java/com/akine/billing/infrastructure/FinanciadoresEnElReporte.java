package com.akine.billing.infrastructure;

import com.akine.billing.domain.PermissionCodes;
import com.akine.reporting.spi.AdvertenciaDeReporte;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import com.akine.reporting.spi.ReporteContributor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lo que {@code billing} aporta al reporte de financiadores: prestado, presentado, facturado,
 * cobrado y pendiente (RF-M23-005).
 *
 * <h2>Esta seccion devuelve cero hoy, y lo dice</h2>
 *
 * <p><b>No existe ninguna obligacion con {@code responsable = FINANCIADOR} y nada la produce.</b>
 * {@code ObligacionDevengador} devenga una sola obligacion a nombre del paciente; el enchufe que
 * V36 reservo ({@code snapshot_convenio_id}, {@code snapshot_arancel_id}) y el que V56 completo
 * ({@code financiador_id}) nunca se conectaron. Es la decision pendiente del usuario que el design
 * challenge de AKINE-07.04 ya habia declarado, y desde aca se ve que apaga la cadena entera:
 *
 * <pre>
 *   sin obligacion de financiador
 *      -&gt; sin prestacion elegible        (07.04 lo declaro asi)
 *      -&gt; sin presentacion con items
 *      -&gt; prestado = presentado = facturado = pendiente = 0
 * </pre>
 *
 * <p>La estructura se construye igual: el dia que el devengado se recablee, esta seccion empieza a
 * dar numeros <b>sin tocar una linea</b>. Y mientras tanto emite
 * {@code sin-devengado-de-financiador} cuando sus totales son cero, porque un tablero que en
 * produccion muestra ceros sin explicar por que es peor que uno ausente: el operador leeria "el mes
 * no tuvo actividad con financiadores" donde en realidad dice "esto todavia no esta cableado".
 *
 * <h2>Tres fuentes, tres conceptos, ningun total</h2>
 *
 * <p>{@code prestado} sale de M18 (la deuda a nombre del financiador), {@code presentado},
 * {@code facturado}, {@code debitado} y {@code pendiente} de M21 (el lote), y
 * {@code cobrado-de-financiadores} de M21 tambien pero de otra tabla (el pago). <b>Prestado y
 * presentado no se suman ni se restan</b>: lo prestado que todavia no se presento es una brecha
 * legitima —el lote se arma despues— y no un error a compensar.
 */
@Component
public class FinanciadoresEnElReporte implements ReporteContributor {

	private static final String SECCION = "financiadores";
	private static final String MONEDA_POR_DEFECTO = "ARS";

	private static final String CODIGO_SIN_DEVENGADO = "sin-devengado-de-financiador";
	private static final String DETALLE_SIN_DEVENGADO =
			"Ninguna obligacion se devenga todavia a nombre de un financiador: el devengado nunca "
					+ "se recableo contra convenios y aranceles. Por eso 'prestado' vale cero por "
					+ "construccion y no por falta de actividad, y sin prestaciones elegibles "
					+ "tampoco hay lotes para presentar. Es una decision pendiente, no un dato.";

	private final ObligacionRepository obligaciones;
	private final PresentacionRepository presentaciones;
	private final FinanciadorPagoRepository pagos;

	public FinanciadoresEnElReporte(
			ObligacionRepository obligaciones,
			PresentacionRepository presentaciones,
			FinanciadorPagoRepository pagos) {

		this.obligaciones = obligaciones;
		this.presentaciones = presentaciones;
		this.pagos = pagos;
	}

	@Override
	public Set<ReporteCode> reportes() {
		return Set.of(ReporteCode.FINANCIADORES);
	}

	@Override
	public String seccion() {
		return SECCION;
	}

	@Override
	public String titulo() {
		return "Financiadores";
	}

	/** {@code cobro:register}, el mismo con el que se opera la cuenta corriente de financiadores. */
	@Override
	public String permisoRequerido() {
		return PermissionCodes.COBRO_REGISTER;
	}

	@Override
	public AporteDeReporte aportar(ConsultaDeReporte consulta) {
		long org = consulta.organizationId();
		long sede = consulta.consultorioId();

		// Linked: conserva el orden del SQL para las filas de financiadores sin lote.
		Map<Long, BigDecimal> prestadoPorFinanciador = new LinkedHashMap<>();
		BigDecimal prestado = BigDecimal.ZERO;
		for (Object[] fila : obligaciones.sumarPrestadoPorFinanciadorEnElReporte(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante(),
				consulta.limiteFilas())) {

			Long financiador = fila[0] == null ? null : ((Number) fila[0]).longValue();
			BigDecimal total = (BigDecimal) fila[1];
			if (financiador != null && total != null) {
				prestadoPorFinanciador.put(financiador, total);
				prestado = prestado.add(total);
			}
		}

		BigDecimal presentado = BigDecimal.ZERO;
		BigDecimal facturado = BigDecimal.ZERO;
		BigDecimal debitado = BigDecimal.ZERO;
		BigDecimal cobradoDeLotes = BigDecimal.ZERO;
		BigDecimal pendiente = BigDecimal.ZERO;
		List<FilaDeReporte> filas = new ArrayList<>();

		for (Object[] fila : presentaciones.resumirPorFinanciadorEnElReporte(
				org, sede, consulta.desde(), consulta.hasta(), consulta.limiteFilas())) {

			long financiador = ((Number) fila[0]).longValue();
			BigDecimal filaPresentado = cero((BigDecimal) fila[1]);
			BigDecimal filaFacturado = cero((BigDecimal) fila[2]);
			BigDecimal filaDebitado = cero((BigDecimal) fila[3]);
			BigDecimal filaCobrado = cero((BigDecimal) fila[4]);
			BigDecimal filaPendiente = cero((BigDecimal) fila[5]);

			presentado = presentado.add(filaPresentado);
			facturado = facturado.add(filaFacturado);
			debitado = debitado.add(filaDebitado);
			cobradoDeLotes = cobradoDeLotes.add(filaCobrado);
			pendiente = pendiente.add(filaPendiente);

			filas.add(FilaDeReporte.de(
					String.valueOf(financiador),
					cero(prestadoPorFinanciador.get(financiador)).toPlainString(),
					filaPresentado.toPlainString(),
					filaFacturado.toPlainString(),
					filaDebitado.toPlainString(),
					filaCobrado.toPlainString(),
					filaPendiente.toPlainString()));
			prestadoPorFinanciador.remove(financiador);
		}

		// Los financiadores con prestado y sin lote que toque el periodo tambien tienen fila: si no,
		// la columna "Prestado" del detalle (y del CSV) no suma al indicador, y la brecha entre
		// prestado y presentado queda sin dueno visible.
		for (Map.Entry<Long, BigDecimal> sinLote : prestadoPorFinanciador.entrySet()) {
			if (filas.size() >= consulta.limiteFilas()) {
				break;
			}
			filas.add(FilaDeReporte.de(
					String.valueOf(sinLote.getKey()),
					sinLote.getValue().toPlainString(),
					"0", "0", "0", "0", "0"));
		}

		BigDecimal cobradoEnElPeriodo = cero(
				pagos.sumarPagadoEnElReporte(org, sede, consulta.desde(), consulta.hasta()));

		String moneda = MONEDA_POR_DEFECTO;

		List<IndicadorDeReporte> indicadores = List.of(
				IndicadorDeReporte.dinero("prestado", "Prestado", prestado, moneda,
						"M18 obligacion con responsable FINANCIADOR",
						"devengada_en, instante proyectado a la zona de la sede"),
				IndicadorDeReporte.dinero("presentado", "Presentado", presentado, moneda,
						"M21 presentacion",
						"periodo del lote que SOLAPA con el pedido; estados PRESENTADA, "
								+ "FACTURADA y CONCILIADA"),
				IndicadorDeReporte.dinero("facturado", "Facturado", facturado, moneda,
						"M21 presentacion",
						"periodo del lote que solapa; solo FACTURADA y CONCILIADA"),
				IndicadorDeReporte.dinero("debitado", "Debitado por el financiador", debitado,
						moneda, "M21 presentacion", "periodo del lote que solapa"),
				// Dos numeros de cobro con dos cortes distintos, y por eso dos indicadores: uno
				// dice cuanto se cobro de los lotes que tocan el periodo, el otro cuanto entro
				// DENTRO del periodo. Fundirlos en uno haria que el reporte cambiara de
				// significado segun que lote estuviera abierto.
				IndicadorDeReporte.dinero("cobrado-de-lotes-del-periodo",
						"Cobrado de los lotes del periodo", cobradoDeLotes, moneda,
						"M21 presentacion", "periodo del lote que solapa"),
				IndicadorDeReporte.dinero("cobrado-de-financiadores",
						"Cobrado de financiadores en el periodo", cobradoEnElPeriodo, moneda,
						"M21 financiador_pago",
						"fecha_pago, que es cuando pago el financiador y no cuando se cargo"),
				IndicadorDeReporte.dinero("pendiente", "Pendiente de cobro", pendiente, moneda,
						"M21 presentacion",
						"saldo actual del lote, NO reconstruido a la fecha de corte"));

		List<AdvertenciaDeReporte> advertencias = prestado.signum() == 0 && presentado.signum() == 0
				? List.of(new AdvertenciaDeReporte(SECCION, CODIGO_SIN_DEVENGADO, DETALLE_SIN_DEVENGADO))
				: List.of();

		return new AporteDeReporte(
				SECCION, titulo(), indicadores,
				List.of("Financiador", "Prestado", "Presentado", "Facturado", "Debitado",
						"Cobrado", "Pendiente"),
				filas, advertencias);
	}

	/** Un {@code SUM} sin filas devuelve {@code null}, y un {@code null} se lee como "sin dato". */
	private static BigDecimal cero(BigDecimal valor) {
		return valor == null ? BigDecimal.ZERO : valor;
	}
}
