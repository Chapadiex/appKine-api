package com.akine.activity.infrastructure;

import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.TipoMovimientoPase;
import com.akine.activity.domain.TipoProducto;
import com.akine.reporting.spi.AdvertenciaDeReporte;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import com.akine.reporting.spi.ReporteContributor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lo que {@code activity} aporta al reporte de pases y abonos (RF-M23-010): que se vendio, que se
 * anulo, que se renovo, que vencio, como se movieron los creditos y cuanto saldo queda vigente.
 *
 * <h2>Un credito no es dinero</h2>
 *
 * <p>RN-M23-006 y la regla de 09.01: "creditos ≠ dinero". Ningun indicador de esta seccion es un
 * importe. Lo que la venta de un pack devengo y lo que se cobro por el estan en la seccion de
 * economia (M18/M19), con su fuente; sumar creditos con pesos no tiene sentido, y poner los dos en
 * la misma seccion invitaria a hacerlo.
 *
 * <h2>Dos clases de numero: del periodo y de hoy</h2>
 *
 * <ul>
 *   <li><b>Del periodo</b>: ventas por {@code vendido_en}, anulaciones por {@code anulado_en},
 *       movimientos por {@code ocurrido_en} —instantes contra la ventana de la sede— y vencimientos
 *       por {@code vigencia_hasta}, que es fecha de negocio.</li>
 *   <li><b>De hoy</b>: los pases vigentes y su saldo. El estado de un pase no se puede reconstruir
 *       hacia atras, asi que es una foto, y el indicador lleva <b>la fecha de corte escrita</b>:
 *       "hoy" en la zona de la sede, nunca la del servidor (entre las 21 y las 24 de Cordoba, UTC ya
 *       es manana y un pase que vence hoy se leeria vencido).</li>
 * </ul>
 *
 * <h2>El saldo se reconcilia con el ledger, y si no cierra se dice</h2>
 *
 * <p>RF-M23-010 pide que "los saldos reconcilien con movimientos" y RNF-M29-001 lo exige del modelo:
 * {@code creditos_disponibles == SUM(movimiento_pase.cantidad)}. La seccion lo verifica sobre los
 * pases de la sede y, si alguno diverge, emite la advertencia {@code saldo-de-pase-no-reconcilia}
 * en vez de mostrar un total de disponibles que no cierra.
 *
 * <h2>La sede es la de la venta</h2>
 *
 * <p>{@code movimiento_pase} no lleva sede: un consumo hecho en otra sede de la organizacion se
 * imputa al pase, y el pase es de la sede donde se vendio. Cada criterio lo dice.
 *
 * <h2>Sin recorte por actividad propia</h2>
 *
 * <p>Un pase no tiene profesional. {@link #filtraPorActividadPropia()} queda en {@code false}: el
 * reporte limitado de un {@code PROFESIONAL} omite esta seccion y la declara, en vez de mostrarle
 * las ventas de toda la sede.
 */
@Component
public class PasesEnElReporte implements ReporteContributor {

	static final String SECCION = "pases";
	static final String ADVERTENCIA_SALDO_NO_RECONCILIA = "saldo-de-pase-no-reconcilia";

	private static final String FUENTE_PASE = "M29 pase_servicio";
	private static final String FUENTE_LEDGER = "M29 movimiento_pase";
	private static final String SEDE_DE_VENTA = "sede donde se vendio el pase";

	private final ActividadEnElReporteRepository consultas;
	private final Clock clock;

	@Autowired
	public PasesEnElReporte(ActividadEnElReporteRepository consultas) {
		this(consultas, Clock.systemUTC());
	}

	PasesEnElReporte(ActividadEnElReporteRepository consultas, Clock clock) {
		this.consultas = consultas;
		this.clock = clock;
	}

	@Override
	public Set<ReporteCode> reportes() {
		return Set.of(ReporteCode.PASES);
	}

	@Override
	public String seccion() {
		return SECCION;
	}

	@Override
	public String titulo() {
		return "Pases y abonos";
	}

	/** {@code pase:read}: el mismo con el que se consulta el estado y los movimientos (RF-M29-009). */
	@Override
	public String permisoRequerido() {
		return PermissionCodes.PASE_READ;
	}

	@Override
	public AporteDeReporte aportar(ConsultaDeReporte consulta) {
		long org = consulta.organizationId();
		long sede = consulta.consultorioId();
		// "Hoy" en la sede: la zona de la consulta es la de la sede (ReporteService la resuelve
		// con ZonaDeSede), nunca la de la JVM.
		LocalDate hoy = LocalDate.ofInstant(clock.instant(), consulta.zona());

		Map<TipoProducto, Long> vendidos = porTipo();
		long renovados = 0;
		for (Object[] fila : consultas.vendidosPorTipo(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante())) {
			sumar(vendidos, fila[0], fila[1]);
			if (TipoProducto.ABONO.name().equals(String.valueOf(fila[0]))) {
				renovados += numero(fila[2]);
			}
		}

		Map<TipoProducto, Long> anulados = porTipo();
		for (Object[] fila : consultas.anuladosPorTipo(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante())) {
			sumar(anulados, fila[0], fila[1]);
		}

		Map<TipoProducto, Long> vencidos = porTipo();
		for (Object[] fila : consultas.vencidosPorTipo(
				org, sede, consulta.desde(), consulta.hasta(), hoy)) {
			sumar(vencidos, fila[0], fila[1]);
		}

		Map<TipoProducto, Long> vigentes = porTipo();
		long creditosDisponibles = 0;
		for (Object[] fila : consultas.vigentesPorTipo(org, sede, hoy)) {
			sumar(vigentes, fila[0], fila[1]);
			creditosDisponibles += numero(fila[2]);
		}

		Map<String, Long> creditosPorMovimiento = new HashMap<>();
		List<FilaDeReporte> filas = new ArrayList<>();
		for (Object[] fila : consultas.movimientosPorProductoYTipo(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante())) {
			String tipoMovimiento = String.valueOf(fila[2]);
			long creditos = numero(fila[4]);
			creditosPorMovimiento.merge(tipoMovimiento, creditos, Long::sum);
			if (filas.size() < consulta.limiteFilas()) {
				filas.add(FilaDeReporte.de(
						String.valueOf(fila[0]), String.valueOf(fila[1]), tipoMovimiento,
						String.valueOf(numero(fila[3])), String.valueOf(creditos)));
			}
		}

		String delPeriodoVenta = "vendido_en, instante proyectado a la zona de la sede; " + SEDE_DE_VENTA;
		String delPeriodoAnulacion = "anulado_en, instante proyectado a la zona de la sede; " + SEDE_DE_VENTA;
		String delPeriodoLedger = "ocurrido_en del movimiento, instante proyectado a la zona de la "
				+ "sede; " + SEDE_DE_VENTA;
		String corteDeHoy = "foto al " + hoy + " (hoy en la zona de la sede), no depende del "
				+ "periodo; ACTIVO y vigente ese dia";
		String delPeriodoVencimiento = "vigencia_hasta dentro del periodo y anterior al " + hoy
				+ " (hoy en la zona de la sede)";

		List<IndicadorDeReporte> indicadores = List.of(
				IndicadorDeReporte.contando("pases-packs-vendidos", "Packs vendidos",
						vendidos.get(TipoProducto.PACK), FUENTE_PASE, delPeriodoVenta),
				IndicadorDeReporte.contando("pases-abonos-vendidos", "Abonos vendidos",
						vendidos.get(TipoProducto.ABONO), FUENTE_PASE,
						delPeriodoVenta + "; incluye las renovaciones"),
				IndicadorDeReporte.contando("pases-abonos-renovados", "Abonos renovados",
						renovados, FUENTE_PASE,
						delPeriodoVenta + "; periodos vendidos por la renovacion automatica"),
				IndicadorDeReporte.contando("pases-packs-anulados", "Packs anulados",
						anulados.get(TipoProducto.PACK), FUENTE_PASE, delPeriodoAnulacion),
				IndicadorDeReporte.contando("pases-abonos-anulados", "Abonos anulados",
						anulados.get(TipoProducto.ABONO), FUENTE_PASE, delPeriodoAnulacion),
				IndicadorDeReporte.contando("pases-packs-vencidos", "Packs vencidos",
						vencidos.get(TipoProducto.PACK), FUENTE_PASE,
						delPeriodoVencimiento + "; estado VENCIDO (vencio con saldo sin usar)"),
				IndicadorDeReporte.contando("pases-abonos-vencidos", "Abonos vencidos",
						vencidos.get(TipoProducto.ABONO), FUENTE_PASE,
						delPeriodoVencimiento + "; periodo terminado, sin contar los anulados"),
				IndicadorDeReporte.contando("pases-creditos-vendidos", "Creditos vendidos",
						creditos(creditosPorMovimiento, TipoMovimientoPase.COMPRA), FUENTE_LEDGER,
						delPeriodoLedger + "; movimientos COMPRA"),
				IndicadorDeReporte.contando("pases-creditos-consumidos", "Creditos consumidos",
						-creditos(creditosPorMovimiento, TipoMovimientoPase.CONSUMO), FUENTE_LEDGER,
						delPeriodoLedger + "; movimientos CONSUMO por asistencia"),
				IndicadorDeReporte.contando("pases-creditos-devueltos", "Creditos devueltos",
						creditos(creditosPorMovimiento, TipoMovimientoPase.DEVOLUCION), FUENTE_LEDGER,
						delPeriodoLedger + "; movimientos DEVOLUCION"),
				IndicadorDeReporte.contando("pases-creditos-ajustados", "Ajuste neto de creditos",
						creditos(creditosPorMovimiento, TipoMovimientoPase.AJUSTE), FUENTE_LEDGER,
						delPeriodoLedger + "; movimientos AJUSTE con signo, incluye lo descartado "
								+ "al anular"),
				IndicadorDeReporte.contando("pases-creditos-vencidos", "Creditos vencidos",
						-creditos(creditosPorMovimiento, TipoMovimientoPase.VENCIMIENTO), FUENTE_LEDGER,
						delPeriodoLedger + "; movimientos VENCIMIENTO"),
				IndicadorDeReporte.contando("pases-packs-vigentes", "Packs vigentes",
						vigentes.get(TipoProducto.PACK), FUENTE_PASE, corteDeHoy),
				IndicadorDeReporte.contando("pases-abonos-vigentes", "Abonos vigentes",
						vigentes.get(TipoProducto.ABONO), FUENTE_PASE, corteDeHoy),
				IndicadorDeReporte.contando("pases-creditos-disponibles", "Creditos disponibles",
						creditosDisponibles, FUENTE_PASE,
						corteDeHoy + "; suma de creditos_disponibles (CA-M23-010-06)"));

		List<AdvertenciaDeReporte> advertencias = new ArrayList<>();
		long divergentes = consultas.pasesConSaldoDivergente(org, sede);
		if (divergentes > 0) {
			advertencias.add(new AdvertenciaDeReporte(
					SECCION,
					ADVERTENCIA_SALDO_NO_RECONCILIA,
					divergentes + (divergentes == 1 ? " pase" : " pases") + " de la sede tienen un "
							+ "saldo que no coincide con la suma de sus movimientos. El total de "
							+ "creditos disponibles no es confiable hasta revisarlos."));
		}

		return new AporteDeReporte(
				SECCION, titulo(), indicadores,
				List.of("Producto", "Tipo", "Movimiento", "Movimientos", "Creditos"),
				filas, advertencias);
	}

	private static Map<TipoProducto, Long> porTipo() {
		Map<TipoProducto, Long> mapa = new EnumMap<>(TipoProducto.class);
		for (TipoProducto tipo : TipoProducto.values()) {
			mapa.put(tipo, 0L);
		}
		return mapa;
	}

	/** Suma al tipo que corresponda; un tipo que esta seccion no conoce no rompe el reporte. */
	private static void sumar(Map<TipoProducto, Long> mapa, Object tipo, Object cantidad) {
		for (TipoProducto conocido : TipoProducto.values()) {
			if (conocido.name().equals(String.valueOf(tipo))) {
				mapa.merge(conocido, numero(cantidad), Long::sum);
				return;
			}
		}
	}

	private static long creditos(Map<String, Long> porMovimiento, TipoMovimientoPase tipo) {
		return porMovimiento.getOrDefault(tipo.name(), 0L);
	}

	private static long numero(Object valor) {
		return valor == null ? 0L : ((Number) valor).longValue();
	}
}
