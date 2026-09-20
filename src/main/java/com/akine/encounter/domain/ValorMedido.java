package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.MedicionTipoIncompatibleException;
import com.akine.resource.spi.MedicionTipo;

import java.math.BigDecimal;

/**
 * El valor de una medicion, con <b>exactamente uno</b> de los tres campos seteado.
 *
 * <h2>Por que un record de tres nulables y no tres parametros sueltos</h2>
 *
 * <p>La invariante "exactamente uno" es la que el CHECK de V52 hace cumplir, y tenerla en un tipo
 * propio es lo que permite verificarla <b>una sola vez</b>, antes de tocar la entidad. Repartida
 * entre el alta y la reescritura habria dos copias, y la primera en divergir produciria una fila
 * que el motor rechaza al flush: un 500 en lugar del 400 que corresponde.
 *
 * <p>No es un {@code sealed interface} con tres implementaciones porque el cuerpo HTTP tiene esa
 * misma forma —tres campos opcionales— y convertirlo antes de poder validarlo obligaria a decidir
 * el tipo en la capa {@code api}, que es donde no se toman decisiones de negocio.
 */
public record ValorMedido(BigDecimal numerico, String texto, Boolean booleano) {

	public ValorMedido {
		texto = texto == null || texto.isBlank() ? null : texto.strip();
	}

	/** Cuantos de los tres vinieron. Sirve para distinguir "ninguno" de "mas de uno". */
	public int cuantosPresentes() {
		int presentes = 0;
		if (numerico != null) {
			presentes++;
		}
		if (texto != null) {
			presentes++;
		}
		if (booleano != null) {
			presentes++;
		}
		return presentes;
	}

	/**
	 * Exige que el valor sea el que ese tipo admite, y ninguno mas.
	 *
	 * <p><b>Las dos mitades importan.</b> Que falte el que corresponde deja una medicion que no
	 * mide nada; que sobre otro es el principio de una medicion que despues nadie puede comparar
	 * —un numero guardado como texto no se promedia, no se grafica y no se resta contra el de la
	 * sesion anterior—.
	 *
	 * @throws MedicionTipoIncompatibleException con el codigo del test y el tipo esperado. Es 400
	 *                                           y no 409: no depende de nada que pueda cambiar
	 *                                           entre dos peticiones, asi que reintentar el mismo
	 *                                           cuerpo falla igual
	 */
	public void exigirCompatibleCon(MedicionTipo tipo, String codigoDelTest) {
		if (cuantosPresentes() != 1) {
			throw new MedicionTipoIncompatibleException(codigoDelTest, tipo.name(),
					"se espera exactamente un valor y llegaron " + cuantosPresentes());
		}
		boolean coincide = switch (tipo) {
			case NUMERICO, ESCALA -> numerico != null;
			case TEXTO -> texto != null;
			case BOOLEANO -> booleano != null;
		};
		if (!coincide) {
			throw new MedicionTipoIncompatibleException(codigoDelTest, tipo.name(),
					"el valor enviado no es el que admite ese tipo de medida");
		}
	}
}
