package com.akine.activity.infrastructure;

import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.TipoMovimientoPase;
import com.akine.offering.spi.ClasificacionDeOferta;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import com.akine.reporting.spi.ReporteContributor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Los creditos de pases y abonos por servicio, en el reporte de ingresos por servicio (RF-M23-009,
 * AKINE-09.01b).
 *
 * <p>Esta seccion existe para que CA-M23-009-06 se pueda leer en una sola pantalla: <i>"reporte de
 * Pilates muestra ventas de packs y ocupacion sin sumar cada consumo como nuevo ingreso"</i>. La
 * venta del pack es plata y la cuenta {@code billing} (seccion {@code ingresos-por-servicio}); la
 * ocupacion la da la seccion {@code clases}; y lo que se consumio de ese pack va aca, <b>como
 * conteo de creditos y nunca como importe</b> (RN-M23-006). Un credito consumido no es un ingreso
 * nuevo: es el uso de uno que ya se devengo al vender.
 *
 * <p>Mismos criterios que la seccion {@code pases} (RF-M23-010): movimientos del ledger por
 * {@code ocurrido_en}, sede de la venta. La oferta es la que el pase congelo al venderse
 * ({@code snapshot_oferta_id}).
 */
@Component
public class CreditosPorServicioEnElReporte implements ReporteContributor {

	static final String SECCION = "creditos-por-servicio";

	private static final String FUENTE = "M29 movimiento_pase + M27 servicio";
	private static final String CRITERIO = "ocurrido_en del movimiento, instante proyectado a la "
			+ "zona de la sede; sede donde se vendio el pase; creditos, no dinero";

	private final ActividadEnElReporteRepository consultas;
	private final OfertaDirectory ofertas;

	public CreditosPorServicioEnElReporte(
			ActividadEnElReporteRepository consultas, OfertaDirectory ofertas) {
		this.consultas = consultas;
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
		return "Creditos por servicio";
	}

	@Override
	public String permisoRequerido() {
		return PermissionCodes.PASE_READ;
	}

	@Override
	public AporteDeReporte aportar(ConsultaDeReporte consulta) {
		long org = consulta.organizationId();
		long sede = consulta.consultorioId();

		List<Object[]> movimientos = consultas.creditosPorOfertaYTipo(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante());
		Set<Long> ofertaIds = new HashSet<>();
		movimientos.forEach(f -> ofertaIds.add(((Number) f[0]).longValue()));
		Map<Long, ClasificacionDeOferta> clasificacion = ofertas.clasificar(org, sede, ofertaIds);

		// [vendidos, consumidos, devueltos] por (servicio, oferta, tipo de producto).
		Map<List<String>, long[]> porFila = new TreeMap<>((a, b) -> String.join("\u0000", a)
				.compareTo(String.join("\u0000", b)));
		long vendidos = 0;
		long consumidos = 0;
		long devueltos = 0;
		for (Object[] fila : movimientos) {
			long ofertaId = ((Number) fila[0]).longValue();
			ClasificacionDeOferta oferta = clasificacion.get(ofertaId);
			List<String> clave = List.of(
					oferta == null ? "(oferta " + ofertaId + ")" : oferta.servicioNombre(),
					oferta == null ? "(oferta " + ofertaId + ")" : oferta.nombreComercial(),
					String.valueOf(fila[1]));
			long creditos = fila[3] == null ? 0 : ((Number) fila[3]).longValue();
			long[] celdas = porFila.computeIfAbsent(clave, c -> new long[3]);
			String tipo = String.valueOf(fila[2]);
			if (TipoMovimientoPase.COMPRA.name().equals(tipo)) {
				celdas[0] += creditos;
				vendidos += creditos;
			} else if (TipoMovimientoPase.CONSUMO.name().equals(tipo)) {
				celdas[1] -= creditos;
				consumidos -= creditos;
			} else if (TipoMovimientoPase.DEVOLUCION.name().equals(tipo)) {
				celdas[2] += creditos;
				devueltos += creditos;
			}
		}

		List<FilaDeReporte> filas = new ArrayList<>();
		porFila.entrySet().stream().limit(consulta.limiteFilas()).forEach(e -> filas.add(
				FilaDeReporte.de(e.getKey().get(0), e.getKey().get(1), e.getKey().get(2),
						String.valueOf(e.getValue()[0]), String.valueOf(e.getValue()[1]),
						String.valueOf(e.getValue()[2]))));

		List<IndicadorDeReporte> indicadores = List.of(
				IndicadorDeReporte.contando("servicios-creditos-vendidos", "Creditos vendidos",
						vendidos, FUENTE, CRITERIO + "; movimientos COMPRA"),
				IndicadorDeReporte.contando("servicios-creditos-consumidos", "Creditos consumidos",
						consumidos, FUENTE, CRITERIO + "; movimientos CONSUMO; no es ingreso"),
				IndicadorDeReporte.contando("servicios-creditos-devueltos", "Creditos devueltos",
						devueltos, FUENTE, CRITERIO + "; movimientos DEVOLUCION"));

		return new AporteDeReporte(SECCION, titulo(), indicadores,
				List.of("Servicio", "Oferta", "Producto", "Creditos vendidos", "Creditos consumidos",
						"Creditos devueltos"),
				filas, List.of());
	}
}
