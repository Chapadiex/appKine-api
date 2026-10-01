package com.akine.offering.spi;

/**
 * Como y cuando cobra una Oferta, para quien tenga que decidir si devengar (RN-M27-006,
 * RF-M18-008).
 *
 * <p><b>Es una proyeccion aparte de {@link OfertaSnapshot} y de {@link PrecioDeOferta}, y las tres
 * razones son distintas.</b> El snapshot excluye todo lo economico porque el motor de agenda no
 * cotiza. El precio dice <i>cuanto</i>. Esto dice <i>cuando</i>, que es una pregunta que sólo se
 * hace quien esta por crear una deuda: un modulo que pide la politica esta declarando que va a
 * devengar, y ese acoplamiento tiene que verse en la firma.
 *
 * <p>Los valores viajan como texto y no como los enums de {@code offering.domain} porque el
 * {@code spi} no puede exponer el dominio de su modulo (AGENT.md §4, regla de capa). Quien los
 * consume compara contra las constantes de esta clase.
 *
 * @param esquemaCobro    {@code null} si el centro no declaro como cobra esa oferta. Es un estado
 *                        real, no un valor faltante: significa que la decision no se tomo, y quien
 *                        lea esto <b>no debe inventar un default</b>
 * @param momentoDevengo  {@code null} si y solo si {@code esquemaCobro} lo es
 * @param devengaNoShow   si una ausencia devenga igual. Solo tiene sentido con
 *                        {@link #MOMENTO_ASISTENCIA}
 */
public record PoliticaDeDevengoDeOferta(
		long ofertaId, String esquemaCobro, String momentoDevengo, boolean devengaNoShow) {

	public static final String ESQUEMA_POR_SESION = "POR_SESION";
	public static final String ESQUEMA_POR_CLASE = "POR_CLASE";
	public static final String ESQUEMA_POR_PACK = "POR_PACK";
	public static final String ESQUEMA_POR_ABONO = "POR_ABONO";

	public static final String MOMENTO_ASISTENCIA = "ASISTENCIA";
	public static final String MOMENTO_INSCRIPCION = "INSCRIPCION";
	public static final String MOMENTO_VENTA = "VENTA";

	/** El centro declaro como cobra esta oferta. */
	public boolean estaDeclarada() {
		return esquemaCobro != null;
	}

	/**
	 * La deuda de esta oferta nace al <b>vender</b> un producto, no al prestar el servicio.
	 *
	 * <p>Es lo que hace que una clase cubierta por un pack o un abono <b>no devengue</b>: el cargo
	 * ya se devengo en la venta, y cobrarlo otra vez seria cobrar dos veces lo mismo.
	 */
	public boolean devengaEnLaVenta() {
		return MOMENTO_VENTA.equals(momentoDevengo);
	}

	/** La deuda nace cuando la persona estuvo. Es el unico momento que lee el no-show. */
	public boolean devengaEnLaAsistencia() {
		return MOMENTO_ASISTENCIA.equals(momentoDevengo);
	}

	/** La deuda nace cuando la persona reservo el lugar, haya venido o no. */
	public boolean devengaEnLaInscripcion() {
		return MOMENTO_INSCRIPCION.equals(momentoDevengo);
	}
}
