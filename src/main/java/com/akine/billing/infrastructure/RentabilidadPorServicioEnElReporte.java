package com.akine.billing.infrastructure;

import com.akine.billing.domain.PermissionCodes;
import com.akine.offering.spi.ClasificacionDeOferta;
import com.akine.offering.spi.OfertaDirectory;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rentabilidad por servicio (DP-39, RF-M23-009): lo que devengo cada oferta menos los egresos
 * imputados a ella en el periodo.
 *
 * <h2>Los dos lados, con el criterio que ya publica el reporte economico</h2>
 *
 * <ul>
 *   <li><b>Ingresos</b> = devengado (M18): {@code devengada_en}, sin {@code ANULADA} ni baja logica.
 *       Es la misma consulta que la seccion de ingresos por servicio, sumada sobre los cuatro
 *       conceptos. Devengado y no cobrado porque el otro lado tambien es devengado: un egreso
 *       confirmado es un costo aunque no se haya pagado.</li>
 *   <li><b>Egresos</b> = egresos {@code CONFIRMADO} o {@code PAGADO} (M22) por {@code registrado_en}:
 *       el recorte del indicador {@code egresos-confirmados}. Un {@code BORRADOR} todavia no es un
 *       compromiso y un {@code ANULADO} ya no lo es. Con el mismo recorte, la suma de la columna de
 *       egresos (imputados y sin imputar) es exactamente ese indicador.</li>
 * </ul>
 *
 * <h2>Lo sin imputar no se reparte</h2>
 *
 * <p>Un egreso sin oferta (alquiler, impuestos) va a su propia fila "sin imputar", con ingresos cero:
 * repartirlo entre servicios es una regla de costeo indirecto que DP-39 no da. Por eso hay dos
 * resultados: el <b>margen de los servicios</b> (ingresos menos lo imputado) y el <b>resultado</b>
 * (el margen menos lo sin imputar).
 *
 * <h2>La advertencia {@code rentabilidad-sin-costos}</h2>
 *
 * <p>Solo cuando ningun egreso del periodo esta imputado a una oferta: ahi el resultado de cada
 * servicio es igual a su ingreso, y mostrarlo sin avisar haria leer como margen lo que es facturacion.
 * Hasta DP-39 la emitia siempre la seccion de ingresos.
 */
@Component
public class RentabilidadPorServicioEnElReporte implements ReporteContributor {

	static final String SECCION = "rentabilidad-por-servicio";
	static final String ADVERTENCIA_SIN_COSTOS = "rentabilidad-sin-costos";

	private static final String MONEDA_POR_DEFECTO = "ARS";
	private static final String CRITERIO_INSTANTE_SEDE = "instante proyectado a la zona de la sede";
	private static final String FUENTE_EGRESOS = "M22 egreso + M27 oferta";
	private static final String CRITERIO_EGRESOS = "registrado_en, " + CRITERIO_INSTANTE_SEDE
			+ "; CONFIRMADO o PAGADO (excluye BORRADOR y ANULADO), como egresos-confirmados";

	private final ObligacionRepository obligaciones;
	private final EgresoRepository egresos;
	private final OfertaDirectory ofertas;

	public RentabilidadPorServicioEnElReporte(
			ObligacionRepository obligaciones, EgresoRepository egresos, OfertaDirectory ofertas) {
		this.obligaciones = obligaciones;
		this.egresos = egresos;
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
		return "Rentabilidad por servicio";
	}

	/** {@code cobro:register}, el de economia: los egresos confirmados ya se leen con ese permiso. */
	@Override
	public String permisoRequerido() {
		return PermissionCodes.COBRO_REGISTER;
	}

	@Override
	public AporteDeReporte aportar(ConsultaDeReporte consulta) {
		long org = consulta.organizationId();
		long sede = consulta.consultorioId();

		Map<Long, BigDecimal> ingresos = new HashMap<>();
		for (Object[] fila : obligaciones.sumarDevengadoPorOfertaEnElReporte(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante())) {
			ingresos.merge(((Number) fila[0]).longValue(), importe(fila[2]), BigDecimal::add);
		}
		Map<Long, BigDecimal> costos = new HashMap<>();
		BigDecimal sinImputar = BigDecimal.ZERO;
		for (Object[] fila : egresos.sumarConfirmadosPorOfertaEnElReporte(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante())) {
			if (fila[0] == null) {
				sinImputar = sinImputar.add(importe(fila[1]));
			} else {
				costos.merge(((Number) fila[0]).longValue(), importe(fila[1]), BigDecimal::add);
			}
		}

		BigDecimal totalIngresos = suma(ingresos);
		BigDecimal imputados = suma(costos);
		BigDecimal margen = totalIngresos.subtract(imputados);

		String moneda = MONEDA_POR_DEFECTO;
		List<IndicadorDeReporte> indicadores = List.of(
				IndicadorDeReporte.dinero("rentabilidad-ingresos", "Ingresos (devengado)", totalIngresos,
						moneda, "M18 obligacion", "devengada_en, " + CRITERIO_INSTANTE_SEDE
								+ "; excluye ANULADA y la baja logica; es el devengado del reporte economico"),
				IndicadorDeReporte.dinero("rentabilidad-egresos-imputados", "Egresos imputados a servicios",
						imputados, moneda, FUENTE_EGRESOS, CRITERIO_EGRESOS + "; con oferta"),
				IndicadorDeReporte.dinero("rentabilidad-egresos-sin-imputar", "Egresos sin imputar",
						sinImputar, moneda, FUENTE_EGRESOS, CRITERIO_EGRESOS + "; sin oferta, no se reparten"),
				IndicadorDeReporte.dinero("rentabilidad-margen-de-servicios",
						"Margen de los servicios (ingresos - egresos imputados)", margen, moneda,
						"M18 obligacion - M22 egreso", "rentabilidad-ingresos - rentabilidad-egresos-imputados"),
				IndicadorDeReporte.dinero("rentabilidad-resultado",
						"Resultado (margen - egresos sin imputar)", margen.subtract(sinImputar), moneda,
						"M18 obligacion - M22 egreso",
						"rentabilidad-margen-de-servicios - rentabilidad-egresos-sin-imputar"));

		Set<Long> ofertaIds = new HashSet<>(ingresos.keySet());
		ofertaIds.addAll(costos.keySet());
		Map<Long, ClasificacionDeOferta> clasificacion = ofertas.clasificar(org, sede, ofertaIds);

		List<FilaDeReporte> filas = new ArrayList<>();
		ofertaIds.stream()
				.sorted(Comparator.comparing((Long id) -> EtiquetaDeOferta.de(id, clasificacion).orden()))
				.limit(consulta.limiteFilas())
				.forEach(id -> filas.add(fila(EtiquetaDeOferta.de(id, clasificacion),
						ingresos.getOrDefault(id, BigDecimal.ZERO), costos.getOrDefault(id, BigDecimal.ZERO))));
		if (sinImputar.signum() > 0) {
			filas.add(fila(EtiquetaDeOferta.sinImputar("(egresos sin oferta)"), BigDecimal.ZERO, sinImputar));
		}

		List<AdvertenciaDeReporte> advertencias = costos.isEmpty()
				? List.of(new AdvertenciaDeReporte(SECCION, ADVERTENCIA_SIN_COSTOS,
						"Ningun egreso del periodo esta imputado a un servicio: el resultado de cada "
								+ "servicio es igual a su ingreso. Imputar el egreso a una oferta al "
								+ "cargarlo es lo que hace aparecer el costo (DP-39)."))
				: List.of();

		return new AporteDeReporte(SECCION, titulo(), indicadores,
				List.of("Servicio", "Naturaleza", "Oferta", "Ingresos (devengado)", "Egresos imputados",
						"Resultado"),
				filas, advertencias);
	}

	private static FilaDeReporte fila(EtiquetaDeOferta etiqueta, BigDecimal ingresos, BigDecimal egresos) {
		List<String> celdas = new ArrayList<>(etiqueta.celdas());
		celdas.add(ingresos.toPlainString());
		celdas.add(egresos.toPlainString());
		celdas.add(ingresos.subtract(egresos).toPlainString());
		return new FilaDeReporte(celdas);
	}

	private static BigDecimal importe(Object valor) {
		return valor == null ? BigDecimal.ZERO : (BigDecimal) valor;
	}

	private static BigDecimal suma(Map<Long, BigDecimal> importes) {
		return importes.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
	}
}
