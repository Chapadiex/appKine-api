package com.akine.billing.infrastructure;

import com.akine.billing.domain.OrigenObligacion;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ingresos por servicio (RF-M23-009, AKINE-09.01b): lo devengado y lo cobrado de la sede, abiertos
 * por oferta y por concepto —prestacion individual, clase, pack, abono—.
 *
 * <h2>Lo que entra y lo que no</h2>
 *
 * <p>El RF pide <i>"consolidar obligaciones, cobros y caja por Oferta/Servicio"</i> y se titula
 * <i>"rentabilidad e ingresos"</i>. De eso, esta seccion entrega lo que tiene fuente:
 *
 * <ul>
 *   <li><b>Devengado</b> (M18): la obligacion lleva su oferta y su origen. Exacto.</li>
 *   <li><b>Cobrado</b> (M19): la imputacion dice a que deuda se aplico cada peso, y la deuda dice
 *       de que oferta es. Exacto, y por eso es lo <b>imputado</b> y no lo cobrado: un cobro con
 *       saldo a favor tiene una parte que todavia no es de ningun servicio, y esa va aparte en
 *       {@code servicios-anticipos-sin-imputar}.</li>
 * </ul>
 *
 * <p>Y declara lo que no:
 *
 * <ul>
 *   <li><b>La caja por servicio.</b> Un movimiento de caja es un medio de un cobro, y un cobro
 *       puede pagar deudas de varias ofertas con dos medios: repartir el efectivo entre ofertas
 *       seria elegir una regla de prorrateo que ningun RF da. El cobrado imputado es la misma plata
 *       vista por la deuda.</li>
 *   <li><b>La rentabilidad.</b> Necesita costos por servicio, y ningun egreso (M22) se imputa a una
 *       oferta: su beneficiario es un colaborador o un externo y su categoria es contable. Los
 *       honorarios por clase no existen (U-41). Mostrar un margen seria inventarlo. La advertencia
 *       {@code rentabilidad-sin-costos} lo dice siempre, y la decision es U-50.</li>
 * </ul>
 *
 * <h2>Consumir un credito no es un ingreso (CA-M23-009-06)</h2>
 *
 * <p>Vender un pack de Pilates devenga <b>una</b> obligacion de origen {@code VENTA_PASE}; asistir a
 * las ocho clases consume ocho creditos y no devenga nada (M29). Esta seccion cuenta la venta como
 * ingreso anticipado del servicio; los creditos consumidos los muestra {@code activity} en su
 * seccion del mismo reporte, como conteo y nunca como importe.
 *
 * <h2>Clase o prestacion individual</h2>
 *
 * <p>Una obligacion de origen {@code SESION} sobre una oferta grupal es una clase (la atencion
 * clinica grupal de 08.05 devenga por participante); sobre una oferta individual, una prestacion.
 * Lo decide la modalidad tipada de la oferta, no su nombre.
 */
@Component
public class IngresosPorServicioEnElReporte implements ReporteContributor {

	static final String SECCION = "ingresos-por-servicio";
	static final String ADVERTENCIA_SIN_COSTOS = "rentabilidad-sin-costos";

	private static final String MONEDA_POR_DEFECTO = "ARS";
	private static final String CRITERIO_INSTANTE_SEDE = "instante proyectado a la zona de la sede";

	/** Los cuatro conceptos que pide distinguir RF-M23-009. */
	enum Concepto {
		PRESTACION("prestaciones", "Prestaciones individuales"),
		CLASE("clases", "Clases"),
		PACK("packs", "Packs"),
		ABONO("abonos", "Abonos");

		final String clave;
		final String etiqueta;

		Concepto(String clave, String etiqueta) {
			this.clave = clave;
			this.etiqueta = etiqueta;
		}
	}

	private final ObligacionRepository obligaciones;
	private final CobroRepository cobros;
	private final OfertaDirectory ofertas;

	public IngresosPorServicioEnElReporte(
			ObligacionRepository obligaciones, CobroRepository cobros, OfertaDirectory ofertas) {
		this.obligaciones = obligaciones;
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
		return "Ingresos por servicio";
	}

	/** {@code cobro:register}, el mismo de la seccion de economia: es la misma plata, abierta. */
	@Override
	public String permisoRequerido() {
		return PermissionCodes.COBRO_REGISTER;
	}

	@Override
	public AporteDeReporte aportar(ConsultaDeReporte consulta) {
		long org = consulta.organizationId();
		long sede = consulta.consultorioId();

		List<Object[]> devengadas = obligaciones.sumarDevengadoPorOfertaEnElReporte(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante());
		List<Object[]> cobradas = obligaciones.sumarCobradoImputadoPorOfertaEnElReporte(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante());

		Set<Long> ofertaIds = new HashSet<>();
		devengadas.forEach(f -> ofertaIds.add(((Number) f[0]).longValue()));
		cobradas.forEach(f -> ofertaIds.add(((Number) f[0]).longValue()));
		Map<Long, ClasificacionDeOferta> clasificacion = ofertas.clasificar(org, sede, ofertaIds);

		Map<Clave, BigDecimal[]> porFila = new HashMap<>();
		Map<Concepto, BigDecimal> devengadoPorConcepto = ceros();
		Map<Concepto, BigDecimal> cobradoPorConcepto = ceros();
		acumular(devengadas, 0, clasificacion, porFila, devengadoPorConcepto);
		acumular(cobradas, 1, clasificacion, porFila, cobradoPorConcepto);

		BigDecimal anticipos = cero(cobros.sumarSaldoAFavorEnElReporte(
				org, sede, consulta.desdeInstante(), consulta.hastaInstante()));

		String moneda = MONEDA_POR_DEFECTO;
		List<IndicadorDeReporte> indicadores = new ArrayList<>();
		for (Concepto concepto : Concepto.values()) {
			indicadores.add(IndicadorDeReporte.dinero(
					"servicios-devengado-" + concepto.clave, "Devengado: " + concepto.etiqueta,
					devengadoPorConcepto.get(concepto), moneda, "M18 obligacion + M27 oferta",
					"devengada_en, " + CRITERIO_INSTANTE_SEDE + "; excluye ANULADA y la baja logica; "
							+ origenDe(concepto)));
		}
		for (Concepto concepto : Concepto.values()) {
			indicadores.add(IndicadorDeReporte.dinero(
					"servicios-cobrado-" + concepto.clave, "Cobrado imputado: " + concepto.etiqueta,
					cobradoPorConcepto.get(concepto), moneda,
					"M19 cobro_imputacion + M18 obligacion + M27 oferta",
					"imputada_en, " + CRITERIO_INSTANTE_SEDE + "; excluye cobros anulados; sede de "
							+ "la deuda; " + origenDe(concepto)));
		}
		indicadores.add(IndicadorDeReporte.dinero("servicios-anticipos-sin-imputar",
				"Anticipos sin imputar (de ningun servicio)", anticipos, moneda, "M19 cobro",
				"cobrado_en, " + CRITERIO_INSTANTE_SEDE + "; saldo a favor ACTUAL, no reconstruido "
						+ "a la fecha de corte; excluye cobros anulados"));

		List<FilaDeReporte> filas = new ArrayList<>();
		porFila.entrySet().stream()
				.sorted(Map.Entry.comparingByKey(Clave.ORDEN))
				.limit(consulta.limiteFilas())
				.forEach(e -> filas.add(FilaDeReporte.de(
						e.getKey().servicio(), e.getKey().naturaleza(), e.getKey().oferta(),
						e.getKey().concepto().etiqueta,
						e.getValue()[0].toPlainString(), e.getValue()[1].toPlainString())));

		AdvertenciaDeReporte sinCostos = new AdvertenciaDeReporte(SECCION, ADVERTENCIA_SIN_COSTOS,
				"La rentabilidad por servicio no se calcula: ningun egreso se imputa a un servicio "
						+ "y no existe un esquema de honorarios por clase (U-41). Estos son ingresos, "
						+ "no margen. Decision pendiente U-50.");

		return new AporteDeReporte(SECCION, titulo(), indicadores,
				List.of("Servicio", "Naturaleza", "Oferta", "Concepto", "Devengado", "Cobrado imputado"),
				filas, List.of(sinCostos));
	}

	/** Una fila del detalle: servicio, oferta y concepto. */
	private record Clave(String servicio, String naturaleza, String oferta, Concepto concepto) {

		static final Comparator<Clave> ORDEN = Comparator.comparing(Clave::servicio)
				.thenComparing(Clave::oferta)
				.thenComparing(Clave::concepto);
	}

	private static void acumular(
			List<Object[]> filas, int columna, Map<Long, ClasificacionDeOferta> clasificacion,
			Map<Clave, BigDecimal[]> porFila, Map<Concepto, BigDecimal> porConcepto) {

		for (Object[] fila : filas) {
			long ofertaId = ((Number) fila[0]).longValue();
			ClasificacionDeOferta oferta = clasificacion.get(ofertaId);
			Concepto concepto = concepto(String.valueOf(fila[1]), oferta != null && oferta.grupal());
			BigDecimal importe = cero((BigDecimal) fila[2]);

			porConcepto.merge(concepto, importe, BigDecimal::add);
			Clave clave = oferta == null
					? new Clave("(oferta " + ofertaId + ")", "", "(oferta " + ofertaId + ")", concepto)
					: new Clave(oferta.servicioNombre(), oferta.naturaleza(), oferta.nombreComercial(), concepto);
			BigDecimal[] par = porFila.computeIfAbsent(
					clave, c -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
			par[columna] = par[columna].add(importe);
		}
	}

	static Concepto concepto(String origen, boolean grupal) {
		if (OrigenObligacion.VENTA_PASE.name().equals(origen)) {
			return Concepto.PACK;
		}
		if (OrigenObligacion.VENTA_ABONO.name().equals(origen)) {
			return Concepto.ABONO;
		}
		return grupal ? Concepto.CLASE : Concepto.PRESTACION;
	}

	private static String origenDe(Concepto concepto) {
		return switch (concepto) {
			case PRESTACION -> "origen SESION, oferta individual";
			case CLASE -> "origen SESION, oferta grupal";
			case PACK -> "origen VENTA_PASE: ingreso anticipado, el consumo de creditos no suma";
			case ABONO -> "origen VENTA_ABONO: ingreso anticipado, el consumo de creditos no suma";
		};
	}

	private static Map<Concepto, BigDecimal> ceros() {
		Map<Concepto, BigDecimal> mapa = new EnumMap<>(Concepto.class);
		for (Concepto concepto : Concepto.values()) {
			mapa.put(concepto, BigDecimal.ZERO);
		}
		return mapa;
	}

	private static BigDecimal cero(BigDecimal valor) {
		return valor == null ? BigDecimal.ZERO : valor;
	}
}
