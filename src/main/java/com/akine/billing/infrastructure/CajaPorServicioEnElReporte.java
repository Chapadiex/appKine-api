package com.akine.billing.infrastructure;

import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.PermissionCodes;
import com.akine.billing.domain.ProrrateoAlCentavo;
import com.akine.offering.spi.ClasificacionDeOferta;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import com.akine.reporting.spi.ReporteContributor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Caja por servicio (DP-39, RF-M23-009): la plata que entro por los cobros del periodo, repartida
 * entre las ofertas que esos cobros pagaron, y abierta por medio.
 *
 * <h2>La regla de reparto</h2>
 *
 * <p>Un cobro de 1.000 pagado 300 en efectivo y 700 por transferencia que salda una sesion de
 * kinesio de 600 y una clase de 400 no dice que efectivo fue a que servicio: el medio es del cobro
 * y la imputacion es de la deuda, y ninguna tabla los cruza. La decision del usuario (DP-39) es
 * repartir <b>cada medio en proporcion a lo imputado a cada deuda</b>: 180 de efectivo y 420 de
 * transferencia a kinesio, 120 y 280 a la clase.
 *
 * <ul>
 *   <li><b>Lo no imputado es una parte mas.</b> Un cobro de 1.000 que imputa 600 y deja 400 de saldo
 *       a favor reparte sus medios entre la oferta (600) y "sin imputar" (400): el anticipo todavia
 *       no es de ningun servicio, y repartirlo entre los que si pago inventaria ingresos. Es la
 *       misma frontera que el indicador {@code servicios-anticipos-sin-imputar} de la seccion de
 *       ingresos. Si el saldo despues se reintegra, sigue aca: la plata entro, y su salida esta en
 *       la caja como reintegro.</li>
 *   <li><b>Al centavo, por mayor resto</b> ({@link ProrrateoAlCentavo}): cada medio cierra exacto
 *       con lo que entro por el, y el total de la seccion es exactamente lo cobrado del periodo
 *       —el indicador {@code cobrado} del reporte economico—. Una oferta puede diferir en un
 *       centavo de su cobrado imputado: el redondeo cierra por medio, no por fila.</li>
 *   <li><b>Se calcula al leer y no se persiste</b>: es derivable de cobro, medios e imputaciones,
 *       y una tabla de reparto seria una segunda copia de la verdad.</li>
 * </ul>
 *
 * <h2>Fecha de corte</h2>
 *
 * <p>El periodo recorta por {@code cobrado_en}: la plata entra el dia del cobro. Las imputaciones se
 * leen <b>hasta el momento del reporte</b> ({@code generadoEn}): un saldo a favor de septiembre
 * imputado en octubre pasa, en el reporte de septiembre generado en octubre, de "sin imputar" al
 * servicio que pago. El criterio de cada indicador lo dice.
 *
 * <h2>Cobro no es caja, y esta seccion no lo olvida</h2>
 *
 * <p>Es lo cobrado (M19) repartido, no los movimientos de la caja (M20): no incluye ingresos
 * manuales, egresos ni diferencias de arqueo, y un cobro anulado no suma aunque su ingreso siga en
 * el cajon con su reversion al lado. La conciliacion entre los dos lados es del reporte economico.
 */
@Component
public class CajaPorServicioEnElReporte implements ReporteContributor {

	static final String SECCION = "caja-por-servicio";

	/** La parte de un cobro que no se imputo a ninguna deuda. Ningun id de oferta es 0. */
	static final long SIN_IMPUTAR = 0L;

	private static final String MONEDA_POR_DEFECTO = "ARS";
	private static final String FUENTE = "M19 cobro + cobro_medio + cobro_imputacion + M18 obligacion "
			+ "+ M27 oferta";
	private static final String CRITERIO = "cobrado_en, instante proyectado a la zona de la sede; "
			+ "excluye cobros anulados; cada medio se reparte entre las ofertas en proporcion a lo "
			+ "imputado a cada deuda hasta la generacion del reporte (generadoEn es la fecha de corte), "
			+ "al centavo por mayor resto";

	private final CobroRepository cobros;
	private final OfertaDirectory ofertas;

	public CajaPorServicioEnElReporte(CobroRepository cobros, OfertaDirectory ofertas) {
		this.cobros = cobros;
		this.ofertas = ofertas;
	}

	@Override
	public Set<ReporteCode> reportes() {
		return Set.of(ReporteCode.SERVICIOS);
	}

	@Override
	public String seccion() {
		return SECCION;
	}

	@Override
	public String titulo() {
		return "Cobrado en caja por servicio";
	}

	/** {@code cobro:register}, el de la seccion de ingresos: es la misma plata, abierta por medio. */
	@Override
	public String permisoRequerido() {
		return PermissionCodes.COBRO_REGISTER;
	}

	@Override
	public AporteDeReporte aportar(ConsultaDeReporte consulta) {
		long org = consulta.organizationId();
		long sede = consulta.consultorioId();

		Map<Long, Map<MedioDePago, BigDecimal>> mediosPorCobro = new TreeMap<>();
		for (Object[] fila : cobros.listarMediosDeCobrosEnElReporte(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante())) {
			mediosPorCobro.computeIfAbsent(((Number) fila[0]).longValue(), c -> new EnumMap<>(MedioDePago.class))
					.merge(MedioDePago.valueOf(String.valueOf(fila[1])), (BigDecimal) fila[2], BigDecimal::add);
		}
		Map<Long, Map<Long, BigDecimal>> imputadoPorCobro = new HashMap<>();
		for (Object[] fila : cobros.listarImputadoPorOfertaDeCobrosEnElReporte(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante())) {
			imputadoPorCobro.computeIfAbsent(((Number) fila[0]).longValue(), c -> new TreeMap<>())
					.merge(((Number) fila[1]).longValue(), (BigDecimal) fila[2], BigDecimal::add);
		}

		Map<Long, Map<MedioDePago, BigDecimal>> porOferta = repartir(mediosPorCobro, imputadoPorCobro);

		BigDecimal total = BigDecimal.ZERO;
		BigDecimal sinImputar = BigDecimal.ZERO;
		for (Map.Entry<Long, Map<MedioDePago, BigDecimal>> e : porOferta.entrySet()) {
			BigDecimal suma = suma(e.getValue());
			total = total.add(suma);
			if (e.getKey() == SIN_IMPUTAR) {
				sinImputar = sinImputar.add(suma);
			}
		}

		String moneda = MONEDA_POR_DEFECTO;
		List<IndicadorDeReporte> indicadores = List.of(
				IndicadorDeReporte.dinero("servicios-caja-cobrado", "Cobrado en caja (todos los medios)",
						total, moneda, FUENTE, CRITERIO + "; es el indicador cobrado del reporte economico"),
				IndicadorDeReporte.dinero("servicios-caja-imputado", "Cobrado en caja imputado a servicios",
						total.subtract(sinImputar), moneda, FUENTE, CRITERIO),
				IndicadorDeReporte.dinero("servicios-caja-sin-imputar",
						"Cobrado en caja sin imputar (anticipos)", sinImputar, moneda, FUENTE,
						CRITERIO + "; la parte de cada cobro que no se aplico a ninguna deuda"));

		Map<Long, ClasificacionDeOferta> clasificacion = ofertas.clasificar(org, sede,
				porOferta.keySet().stream().filter(id -> id != SIN_IMPUTAR).toList());

		List<FilaDeReporte> filas = new ArrayList<>();
		porOferta.entrySet().stream()
				.filter(e -> e.getKey() != SIN_IMPUTAR)
				.map(e -> fila(EtiquetaDeOferta.de(e.getKey(), clasificacion), e.getValue()))
				.sorted(Comparator.comparing(FilaOrdenable::orden))
				.limit(consulta.limiteFilas())
				.forEach(f -> filas.add(f.fila()));
		if (porOferta.containsKey(SIN_IMPUTAR)) {
			filas.add(fila(EtiquetaDeOferta.sinImputar("(anticipos y saldo a favor)"),
					porOferta.get(SIN_IMPUTAR)).fila());
		}

		List<String> columnas = new ArrayList<>(List.of("Servicio", "Naturaleza", "Oferta", "Total"));
		for (MedioDePago medio : MedioDePago.values()) {
			columnas.add(etiqueta(medio));
		}
		return new AporteDeReporte(SECCION, titulo(), indicadores, columnas, filas, List.of());
	}

	/**
	 * El reparto entero: por cada cobro, cada medio entre sus ofertas y lo no imputado.
	 *
	 * <p>Los cobros se recorren por id y las ofertas de cada uno por id, con "sin imputar" al final:
	 * el desempate del mayor resto depende de ese orden, y fijarlo hace que el mismo reporte de
	 * siempre los mismos centavos en las mismas filas.
	 */
	static Map<Long, Map<MedioDePago, BigDecimal>> repartir(
			Map<Long, Map<MedioDePago, BigDecimal>> mediosPorCobro,
			Map<Long, Map<Long, BigDecimal>> imputadoPorCobro) {

		Map<Long, Map<MedioDePago, BigDecimal>> porOferta = new LinkedHashMap<>();
		for (Map.Entry<Long, Map<MedioDePago, BigDecimal>> cobro : mediosPorCobro.entrySet()) {
			Map<MedioDePago, BigDecimal> medios = cobro.getValue();
			BigDecimal totalDelCobro = suma(medios);

			Map<Long, BigDecimal> pesos = new LinkedHashMap<>(
					imputadoPorCobro.getOrDefault(cobro.getKey(), Map.of()));
			BigDecimal imputado = pesos.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
			BigDecimal noImputado = totalDelCobro.subtract(imputado);
			if (noImputado.signum() > 0) {
				pesos.put(SIN_IMPUTAR, noImputado);
			}
			if (pesos.values().stream().noneMatch(p -> p.signum() > 0)) {
				continue;
			}

			for (Map.Entry<MedioDePago, BigDecimal> medio : medios.entrySet()) {
				ProrrateoAlCentavo.repartir(medio.getValue(), pesos).forEach((oferta, parte) ->
						porOferta.computeIfAbsent(oferta, o -> new EnumMap<>(MedioDePago.class))
								.merge(medio.getKey(), parte, BigDecimal::add));
			}
		}
		return porOferta;
	}

	private static FilaOrdenable fila(EtiquetaDeOferta etiqueta, Map<MedioDePago, BigDecimal> porMedio) {
		List<String> celdas = new ArrayList<>(etiqueta.celdas());
		celdas.add(suma(porMedio).toPlainString());
		for (MedioDePago medio : MedioDePago.values()) {
			celdas.add(porMedio.getOrDefault(medio, BigDecimal.ZERO).toPlainString());
		}
		return new FilaOrdenable(etiqueta.orden(), new FilaDeReporte(celdas));
	}

	private static BigDecimal suma(Map<?, BigDecimal> importes) {
		return importes.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static String etiqueta(MedioDePago medio) {
		return switch (medio) {
			case EFECTIVO -> "Efectivo";
			case TRANSFERENCIA -> "Transferencia";
			case TARJETA_DEBITO -> "Tarjeta de debito";
			case TARJETA_CREDITO -> "Tarjeta de credito";
			case OTRO -> "Otro medio";
		};
	}

	private record FilaOrdenable(String orden, FilaDeReporte fila) {
	}
}
