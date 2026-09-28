package com.akine.encounter.application;

import java.util.List;

/**
 * Las mediciones de una sesion, con el informe de completitud (RF-M14-004).
 *
 * <h2>"Completo" se INFORMA, no se gatea</h2>
 *
 * <p>El enunciado pide "completo solo cuando corresponde", y la decision de la etapa es que la
 * completitud se <b>calcula y se muestra</b>, y <b>no bloquea el cierre de la sesion</b>. Cerrar
 * sigue siendo la decision del profesional: bloquearlo por una medicion faltante es lo mismo que
 * 04.04 rechazo al negarse a cerrar un Caso por contador, y obligaria a inventar un dato clinico
 * para poder guardar — que es lo que 06.02 ya declaro inaceptable.
 *
 * <p><b>La definicion concreta de "completo" la fija esta etapa, porque el diseño no la fijaba:</b>
 * {@code completo} es {@code true} cuando toda definicion <b>activa y visible</b> para el tenant
 * tiene al menos una medicion en esta sesion. Es la unica lectura que no exige inventar un
 * concepto nuevo —no hay "obligatoriedad" por definicion ni por especialidad en el modelo— y
 * degrada bien: un centro que carga cincuenta tests en su catalogo vera "incompleto" casi siempre,
 * y eso es exactamente una senal para el, no un error del sistema.
 *
 * <p>Las dos cuentas viajan al lado para que la pantalla pueda decir "12 de 18" en vez de un
 * booleano que no explica nada.
 */
public record MedicionesDeSesionView(

		List<MedicionView> mediciones,

		/** Cuantas definiciones DISTINTAS tienen al menos una medicion en esta sesion. */
		int definicionesCubiertas,

		/** Cuantas definiciones activas ve este tenant hoy: el denominador del informe. */
		int definicionesDisponibles,

		/** Ver el javadoc de la clase. <b>No gatea nada.</b> */
		boolean completo) {

	public static MedicionesDeSesionView de(
			List<MedicionView> mediciones, int definicionesDisponibles) {

		int cubiertas = (int) mediciones.stream()
				.map(MedicionView::definicionId)
				.distinct()
				.count();

		// Un catalogo vacio no se informa como "completo": no hay nada que haya sido cubierto, y
		// decir que si convertiria la ausencia de catalogo en un visto bueno clinico.
		boolean completo = definicionesDisponibles > 0 && cubiertas >= definicionesDisponibles;

		return new MedicionesDeSesionView(
				mediciones, cubiertas, definicionesDisponibles, completo);
	}
}
