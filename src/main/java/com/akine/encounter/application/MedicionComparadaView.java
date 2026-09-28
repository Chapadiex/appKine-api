package com.akine.encounter.application;

import com.akine.encounter.domain.LateralidadMedicion;

import java.math.BigDecimal;

/**
 * Una medida con su valor de esta sesion y el de la sesion cerrada anterior (RF-M14-004).
 *
 * <h2>Se calcula al leer, y no se guarda</h2>
 *
 * <p>No existe ninguna columna de delta ni ninguna FK a "la medicion anterior". Guardarlas seria
 * una segunda copia de la verdad que miente el dia que alguien enmiende la sesion anterior. Es lo
 * mismo que el timeline de 04.02, el avance del plan de 04.04 y la disponibilidad efectiva de
 * 02.04.
 *
 * <h2>Los tres desenlaces, y ninguno es un error</h2>
 *
 * <pre>
 *   actual != null, anterior == null   la medida se tomo hoy por primera vez, o no hay baseline
 *   actual == null, anterior != null   se tomo la vez pasada y todavia no hoy
 *   los dos != null                    hay con que comparar
 * </pre>
 *
 * <p><b>El segundo caso es el que hace innecesario un endpoint de "copiar".</b> La pantalla ve el
 * valor anterior al lado del campo vacio y el profesional lo ajusta; escribirlo es un registro
 * normal, con alguien detras. "Copiar nunca guarda sin revision" es una regla de la pantalla y el
 * backend la sostiene <b>no teniendo como romperla</b>.
 *
 * <h2>{@code delta} es {@code null} mas seguido de lo que parece, y a proposito</h2>
 *
 * <p>Solo se calcula cuando los dos valores existen, los dos son numericos y <b>las dos unidades
 * coinciden</b>. Si el centro cambio la unidad del test entre las dos sesiones, los 90 de marzo y
 * los 90 de septiembre <b>no son el mismo numero</b>: restarlos daria cero y diria que no hubo
 * cambio. En ese caso {@code unidadesDifieren} viene en {@code true}, las dos unidades viajan en
 * sus respectivas vistas y la pantalla tiene que mostrar las dos sin restar nada.
 *
 * <p>Esa es la condicion que el snapshot de {@code sesion_medicion} existe para poder detectar: si
 * la unidad se resolviera por join al catalogo, las dos filas dirian la unidad de hoy y la
 * comparacion mentiria en silencio.
 */
public record MedicionComparadaView(

		long definicionId,

		String codigo,

		String nombre,

		LateralidadMedicion lateralidad,

		/** Lo medido en esta sesion, o {@code null} si todavia no se cargo. */
		MedicionView actual,

		/** Lo medido en la sesion cerrada anterior, o {@code null} si no hay baseline. */
		MedicionView anterior,

		/**
		 * {@code actual - anterior}, o {@code null} cuando no corresponde calcularlo.
		 *
		 * <p>Se publica calculado y no se deja para el cliente porque decidir <b>cuando</b> se
		 * puede restar es una regla de negocio; repetirla en TypeScript daria dos versiones y la
		 * que se equivoque va a ser la que el profesional mira.
		 */
		BigDecimal delta,

		/** {@code true} cuando los dos valores existen y sus unidades NO coinciden. */
		boolean unidadesDifieren) {

	/**
	 * Arma la fila de comparacion y decide si el delta corresponde.
	 *
	 * <p>Al menos uno de los dos tiene que venir: una fila con los dos en {@code null} no
	 * describe nada y solo podria producirla un error de armado.
	 */
	public static MedicionComparadaView de(MedicionView actual, MedicionView anterior) {
		MedicionView presente = actual != null ? actual : anterior;
		if (presente == null) {
			throw new IllegalArgumentException(
					"Una comparacion necesita al menos un lado: sin ninguno no hay medida que "
							+ "nombrar");
		}

		boolean comparables = actual != null && anterior != null;
		boolean mismaUnidad = comparables
				&& java.util.Objects.equals(actual.unidad(), anterior.unidad());

		BigDecimal delta = null;
		if (comparables && mismaUnidad
				&& actual.valorNumerico() != null && anterior.valorNumerico() != null) {
			delta = actual.valorNumerico().subtract(anterior.valorNumerico());
		}

		return new MedicionComparadaView(
				presente.definicionId(),
				presente.codigo(),
				presente.nombre(),
				presente.lateralidad(),
				actual,
				anterior,
				delta,
				comparables && !mismaUnidad);
	}
}
