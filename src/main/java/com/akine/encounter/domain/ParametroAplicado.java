package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.ParametroInvalidoException;

import java.math.BigDecimal;

/**
 * Un parametro tal como lo declara quien registra la intervencion, antes de persistirse.
 *
 * <p>Existe separado de {@link TratamientoParametro} por la regla de capas: los DTO de {@code api}
 * no pueden llegar a {@code application}, y {@code application} no arma entities a mano con siete
 * argumentos sueltos. Es el mismo reparto que {@code EvaluacionBase} tiene con las columnas de
 * evaluacion de la sesion.
 *
 * <h2>{@link #exigirCoherente()} es donde vive el caso borde de la etapa</h2>
 *
 * <p>El plan nombra el "parametro legado sin tipo/unidad que debe <b>rechazarse</b> o normalizarse
 * explicitamente". Aca se rechaza, con 400 y nombrando la clave: un parametro sin tipo, o cuyo
 * valor no cae en la columna que su tipo declara, no llega nunca a la base.
 *
 * <p><b>Se valida lo que seria FALSO, no lo que falta.</b> Es la regla que 06.02 dejo fijada para
 * todo lo clinico: la unidad puede faltar —hay numericos genuinamente adimensionales, "3 series"—
 * y eso no es un error. Lo que si es falso es una unidad colgada de un texto: "mA" de que.
 */
public record ParametroAplicado(
		String clave,
		TipoDatoParametro tipoDato,
		BigDecimal valorNumerico,
		String valorTexto,
		Boolean valorBooleano,
		String unidad) {

	/**
	 * Rechaza el parametro mal tipado.
	 *
	 * @throws ParametroInvalidoException 400, nombrando la clave y el motivo
	 */
	public void exigirCoherente() {
		if (clave == null || clave.isBlank()) {
			throw new ParametroInvalidoException("(sin clave)", "Todo parametro necesita una clave");
		}
		String nombre = clave.strip();

		// EL CASO BORDE DE LA ETAPA. Un parametro sin tipo no se normaliza adivinando: se
		// rechaza, porque adivinar es como se cuela un valor de dosificacion con el tipo
		// equivocado.
		if (tipoDato == null) {
			throw new ParametroInvalidoException(nombre,
					"Falta el tipo de dato. Un parametro sin tipo no se puede interpretar: "
							+ "declaralo como NUMERICO, TEXTO o BOOLEANO");
		}

		int declarados = (valorNumerico == null ? 0 : 1)
				+ (valorTexto == null || valorTexto.isBlank() ? 0 : 1)
				+ (valorBooleano == null ? 0 : 1);

		if (declarados != 1) {
			throw new ParametroInvalidoException(nombre,
					"Un parametro lleva exactamente un valor, y se recibieron " + declarados);
		}

		boolean enSuColumna = switch (tipoDato) {
			case NUMERICO -> valorNumerico != null;
			case TEXTO -> valorTexto != null && !valorTexto.isBlank();
			case BOOLEANO -> valorBooleano != null;
		};
		if (!enSuColumna) {
			throw new ParametroInvalidoException(nombre,
					"El valor no corresponde al tipo declarado (" + tipoDato + ")");
		}

		// "mA" de que. Una unidad sin valor numerico no significa nada.
		if (unidad != null && !unidad.isBlank() && tipoDato != TipoDatoParametro.NUMERICO) {
			throw new ParametroInvalidoException(nombre,
					"Solo un parametro NUMERICO puede llevar unidad");
		}
	}
}
