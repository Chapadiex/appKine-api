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
 * <h2>Hasta AKINE F-4 esta seccion devolvia cero, y lo decia</h2>
 *
 * <p>Ninguna obligacion se devengaba con {@code responsable = FINANCIADOR}: el devengado de 07.01
 * producia una sola, a nombre del paciente, y eso apagaba la cadena entera —sin obligacion de
 * financiador no hay prestacion elegible, ni lote, ni prestado—. La estructura se construyo igual,
 * y por eso F-4 la encendio <b>sin tocar su logica</b>: {@code prestado} empieza a
 * sumar la parte del financiador de cada sesion cubierta por un convenio (el coseguro es del
 * paciente y no entra).
 *
 * <p>La advertencia {@code sin-devengado-de-financiador} sigue saliendo cuando prestado y
 * presentado son cero, pero ahora dice lo que ese cero significa: que en el periodo no hubo
 * prestaciones devengadas a un financiador, y que condiciones tiene que cumplir una sesion para
 * que las haya.
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
			"En el periodo no se devengo ninguna prestacion a nombre de un financiador ni se "
					+ "presento ningun lote. La parte del financiador se devenga al cerrar una "
					+ "sesion cuya oferta admite obra social, con una cobertura vigente del "
					+ "paciente y un convenio de la sede con arancel para la practica; sin alguna "
					+ "de las tres, la sesion se cobra como particular.";

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
