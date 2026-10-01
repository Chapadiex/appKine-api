package com.akine.offering.domain;

import java.util.Set;

/**
 * Esquema economico declarado por el centro para una {@link OfertaServicioConsultorio}
 * (RN-M27-006).
 *
 * <h2>Por que ahora SI es un enum, cuando 02.06 decidio que no</h2>
 *
 * <p>02.06 lo modelo como un {@code record} de texto libre y dejo escrito el motivo: la columna
 * {@code esquema_cobro} de {@code V24} es {@code VARCHAR(32)} <b>sin lista cerrada</b> porque
 * <i>"fijar el enum ahora seria adivinar el vocabulario de un modulo que nadie escribio... los
 * modulos que definirian ese vocabulario (M15 convenios, M16 aranceles, M18 facturacion) no
 * existen todavia"</i>.
 *
 * <p><b>Ya existen.</b> M18 entro en AKINE-07.01 y M29 es AKINE-08.06, que es la etapa que fija
 * este vocabulario y la {@link PoliticaDeDevengo} que lo acompana. {@code V64} cierra la columna
 * con un {@code CHECK} y esta clase lo replica, que es la forma del repositorio para todo
 * clasificador con lista cerrada ({@link Naturaleza}, {@link Modalidad}).
 *
 * <p>Sigue siendo <b>opcional</b>: una oferta puede no declarar esquema, y eso no es un valor
 * faltante sino un estado real —un centro que todavia no decidio como cobra esa prestacion—.
 *
 * <h2>Y ahora SI se interpreta</h2>
 *
 * <p>02.06 escribio que "ningun codigo lee este valor para decidir nada". Eso dejo de ser cierto:
 * el esquema determina {@link #momentosAdmitidos()}, o sea <b>cuando nace la deuda</b>, que es la
 * pregunta que AKINE-08.03 delego explicitamente a esta etapa.
 */
public enum EsquemaCobro {

	/**
	 * Se cobra cada atencion individual. Es lo que {@code billing} ya hace desde 07.01: la sesion
	 * cerrada con asistencia devenga su obligacion.
	 */
	POR_SESION(MomentoDevengo.ASISTENCIA),

	/**
	 * Se cobra cada clase grupal.
	 *
	 * <p><b>Es el unico esquema con dos momentos posibles, y esa es la decision del usuario.</b>
	 * {@code ASISTENCIA} cobra a quien fue; {@code INSCRIPCION} cobra a quien reservo el lugar.
	 * Las consecuencias de cada una estan en {@code docs/diseno/AKINE-08.06-challenge.md} §7.
	 */
	POR_CLASE(MomentoDevengo.ASISTENCIA, MomentoDevengo.INSCRIPCION),

	/**
	 * Se cobra por adelantado un pack de creditos. La deuda nace <b>al comprar</b> y la clase
	 * consume credito sin devengar nada: cobrar las dos cosas seria cobrar dos veces.
	 */
	POR_PACK(MomentoDevengo.VENTA),

	/**
	 * Se cobra un abono por periodo. RN-M29-007: el abono cubre un periodo y <b>no genera deuda por
	 * cada asistencia incluida</b>. AKINE-08.08.
	 */
	POR_ABONO(MomentoDevengo.VENTA);

	private final Set<MomentoDevengo> momentosAdmitidos;

	EsquemaCobro(MomentoDevengo... momentos) {
		this.momentosAdmitidos = Set.of(momentos);
	}

	/** Cuando puede nacer la deuda bajo este esquema. */
	public Set<MomentoDevengo> momentosAdmitidos() {
		return momentosAdmitidos;
	}

	/**
	 * El unico momento posible, cuando hay uno solo.
	 *
	 * <p>Devuelve {@code null} para {@link #POR_CLASE}, que admite dos: pedirle a este metodo que
	 * elija seria decidir por el usuario la pregunta que la etapa eleva.
	 */
	public MomentoDevengo momentoUnico() {
		return momentosAdmitidos.size() == 1 ? momentosAdmitidos.iterator().next() : null;
	}

	/** El esquema admite ese momento. {@code null} nunca es admitido: la politica seria incompleta. */
	public boolean admite(MomentoDevengo momento) {
		return momento != null && momentosAdmitidos.contains(momento);
	}
}
