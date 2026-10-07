package com.akine.reporting.spi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Lo que un contribuyente recibe para calcular su seccion. Es lo unico que recibe.
 *
 * <h2>Por que viajan las fechas DOS veces</h2>
 *
 * <p>El reporte recorta sobre dos clases de columna que no se comparan igual, y elegir mal es el
 * error que ya costo caro en la caja:
 *
 * <ul>
 *   <li><b>Instantes</b> — {@code cobro.cobrado_en}, {@code obligacion.devengada_en},
 *       {@code sesion.cerrada_en}, {@code turno.inicio}. Se comparan contra la ventana
 *       {@code [desdeInstante, hastaInstante)}, ya proyectada con la zona de la <b>sede</b>.</li>
 *   <li><b>Fechas de negocio</b> — {@code movimiento_caja.fecha_negocio},
 *       {@code jornada_caja.fecha_negocio}. Ya son locales: se comparan directo contra
 *       {@code desde} y {@code hasta}.</li>
 * </ul>
 *
 * <p>Las cuatro viajan juntas <b>para que ningun contribuyente tenga que hacer la conversion por
 * su cuenta</b>. Un cobro de las 21:30 en Ushuaia convertido con la zona del servidor cae en el
 * dia siguiente, y el reporte no falla: da otro numero. Es exactamente el descuido que
 * {@code CajaAcceso.fechaDeNegocio} evita desde 07.03.
 *
 * <h2>El tenant no es negociable</h2>
 *
 * <p>{@code organizationId} es obligatorio y lo resuelve {@code reporting} desde el contexto del
 * actor, <b>nunca desde un parametro de la request</b>. No existe una sobrecarga sin tenant que
 * alguien pueda llamar por error: una agregacion que se olvida del filtro no falla, devuelve un
 * numero mas grande, y nadie lo nota.
 *
 * <h2>La actividad propia (AKINE-G-1, DP-15)</h2>
 *
 * <p>Cuando quien pide el reporte tiene {@code reporte:read} "Limitado" —el {@code PROFESIONAL},
 * matriz §4: <i>solo reportes de su propia actividad</i>—, {@code actividadPropiaDe} lleva las
 * memberships de su cuenta en la organizacion, y la seccion cuenta <b>solo</b> las filas cuyo
 * profesional es una de ellas. {@code null} significa sin recorte: el reporte de la sede.
 *
 * <p>Viaja la <b>membership</b> y no la cuenta porque es lo que guardan {@code turno},
 * {@code sesion} y {@code caso_profesional}: la misma persona puede ser profesional en un centro
 * y administrativa en otro (V23, V28). Viajan <b>todas</b> las de la cuenta, vigentes o no, porque
 * lo que atendio con una membership que despues se cerro sigue siendo su actividad.
 *
 * <p>Un contribuyente que no sepa recortar no recibe nunca una consulta recortada:
 * {@code reporting} lo omite antes (ver {@link ReporteContributor#filtraPorActividadPropia()}).
 *
 * @param desde             primer dia del periodo, inclusive, en la zona de la sede
 * @param hasta             ultimo dia del periodo, inclusive, en la zona de la sede
 * @param zona              zona IANA de la sede ({@code consultorio.timezone}), nunca la del servidor
 * @param desdeInstante     {@code desde} a las 00:00 en {@code zona}, inclusive
 * @param hastaInstante     {@code hasta} mas un dia a las 00:00 en {@code zona}, <b>exclusivo</b>
 * @param limiteFilas       tope de filas de detalle por seccion
 * @param actividadPropiaDe memberships a las que se recorta la actividad, o {@code null} para el
 *                          reporte de la sede entera. Si no es {@code null}, nunca vacio
 */
public record ConsultaDeReporte(
		long organizationId,
		long consultorioId,
		LocalDate desde,
		LocalDate hasta,
		ZoneId zona,
		Instant desdeInstante,
		Instant hastaInstante,
		int limiteFilas,
		Set<Long> actividadPropiaDe) {

	/**
	 * Lo que {@link #membershipsDelRecorte()} devuelve cuando no hay recorte.
	 *
	 * <p>Las consultas usan {@code (:recortar = false OR x IN :memberships)}, y un {@code IN ()}
	 * vacio no es SQL valido en MySQL. Ningun id autoincremental vale cero, asi que el centinela no
	 * coincide con nada, y de todos modos con {@code recortar = false} la condicion no se evalua.
	 */
	static final long SIN_RECORTE = 0L;

	public ConsultaDeReporte {
		if (desde == null || hasta == null || zona == null
				|| desdeInstante == null || hastaInstante == null) {
			throw new IllegalArgumentException("Una consulta de reporte necesita periodo y zona");
		}
		if (limiteFilas <= 0) {
			throw new IllegalArgumentException("El limite de filas debe ser positivo");
		}
		if (actividadPropiaDe != null) {
			if (actividadPropiaDe.isEmpty()) {
				// Un recorte a "nadie" no es un reporte vacio: es un actor sin membership que
				// llego hasta aca, y eso es un bug de quien armo la consulta. Fail-closed.
				throw new IllegalArgumentException(
						"Un recorte a la actividad propia necesita al menos una membership");
			}
			actividadPropiaDe = Set.copyOf(actividadPropiaDe);
		}
	}

	/** La consulta de la sede entera, sin recorte por actividad. */
	public ConsultaDeReporte(
			long organizationId, long consultorioId, LocalDate desde, LocalDate hasta,
			ZoneId zona, Instant desdeInstante, Instant hastaInstante, int limiteFilas) {

		this(organizationId, consultorioId, desde, hasta, zona,
				desdeInstante, hastaInstante, limiteFilas, null);
	}

	/** Si la seccion tiene que contar solo la actividad propia del actor. */
	public boolean recortadaAActividadPropia() {
		return actividadPropiaDe != null;
	}

	/**
	 * Las memberships para el {@code IN} de la consulta. <b>Nunca vacia.</b>
	 *
	 * <p>Sin recorte devuelve un centinela que no coincide con ninguna fila; la consulta lo ignora
	 * porque va acompanado de {@link #recortadaAActividadPropia()} en {@code false}.
	 */
	public Collection<Long> membershipsDelRecorte() {
		return recortadaAActividadPropia() ? actividadPropiaDe : List.of(SIN_RECORTE);
	}
}
