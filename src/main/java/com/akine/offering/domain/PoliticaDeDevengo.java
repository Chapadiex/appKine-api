package com.akine.offering.domain;

import com.akine.offering.domain.exception.PoliticaDeDevengoIncoherenteException;

/**
 * Como y cuando cobra una Oferta (RN-M27-006, RF-M18-008).
 *
 * <h2>Por que existe esta clase y no tres columnas sueltas</h2>
 *
 * <p>Los tres valores <b>no son independientes</b>: un {@code POR_PACK} con momento
 * {@code ASISTENCIA} seria una oferta que cobra el pack y ademas cada clase, o sea el doble cobro
 * que RN-M29-007 prohibe para el abono y que nadie querria para el pack. Y un momento sin esquema
 * no significa nada. Si los tres campos viajaran sueltos, esa validacion viviria en cada servicio
 * que los escriba —y el dia que haya dos caminos de escritura, en uno solo—.
 *
 * <p>{@code ck_oferta_politica_devengo} de {@code V64} hace cumplir lo mismo en la base. Las dos
 * cosas hacen falta: el motor garantiza que ninguna fila imposible exista, esta clase da el error
 * explicable antes de llegar al motor.
 *
 * <h2>La decision que esta clase deja abierta a proposito</h2>
 *
 * <p>Para {@link EsquemaCobro#POR_CLASE} hay <b>dos</b> momentos validos, y esta clase no elige.
 * Es la pregunta que AKINE-08.03 elevo al usuario, y convertirla en un default seria tomarla por
 * el. {@link #paraEsquema(EsquemaCobro, boolean)} solo puede resolverla para los esquemas con un
 * momento unico.
 */
public record PoliticaDeDevengo(
		EsquemaCobro esquemaCobro, MomentoDevengo momentoDevengo, boolean devengaNoShow) {

	/** La oferta no declaro como cobra. Es un estado real, no un valor faltante. */
	public static final PoliticaDeDevengo SIN_DECLARAR = new PoliticaDeDevengo(null, null, false);

	public PoliticaDeDevengo {
		if (esquemaCobro == null) {
			if (momentoDevengo != null) {
				throw new PoliticaDeDevengoIncoherenteException(
						"un momento de devengo sin esquema de cobro no significa nada");
			}
		} else if (!esquemaCobro.admite(momentoDevengo)) {
			throw new PoliticaDeDevengoIncoherenteException(
					"el esquema " + esquemaCobro + " no admite el momento de devengo "
							+ momentoDevengo + "; admite " + esquemaCobro.momentosAdmitidos());
		}
	}

	/**
	 * La politica de un esquema que tiene un solo momento posible.
	 *
	 * @throws PoliticaDeDevengoIncoherenteException si el esquema admite mas de uno — hoy solo
	 *                                               {@link EsquemaCobro#POR_CLASE}—, porque elegir
	 *                                               por el usuario es justamente lo que no se hace
	 */
	public static PoliticaDeDevengo paraEsquema(EsquemaCobro esquema, boolean devengaNoShow) {
		if (esquema == null) {
			return SIN_DECLARAR;
		}
		MomentoDevengo unico = esquema.momentoUnico();
		if (unico == null) {
			throw new PoliticaDeDevengoIncoherenteException(
					"el esquema " + esquema + " admite " + esquema.momentosAdmitidos()
							+ " y hay que declarar cual: no se elige por el centro");
		}
		return new PoliticaDeDevengo(esquema, unico, devengaNoShow);
	}

	/** La oferta declaro como cobra. */
	public boolean estaDeclarada() {
		return esquemaCobro != null;
	}

	/**
	 * La deuda de esta oferta nace al <b>vender</b> un producto, no al prestar el servicio.
	 *
	 * <p>Es lo que hace que una clase cubierta por un pack <b>no devengue</b>: el cargo ya se
	 * devengo en la venta.
	 */
	public boolean devengaEnLaVenta() {
		return momentoDevengo == MomentoDevengo.VENTA;
	}
}
