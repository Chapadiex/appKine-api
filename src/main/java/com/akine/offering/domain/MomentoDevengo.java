package com.akine.offering.domain;

/**
 * Cuando nace la deuda de una Oferta (RF-M18-008, RN-M27-006).
 *
 * <p>Es la mitad de {@link PoliticaDeDevengo}, y responde la pregunta que AKINE-08.03 se nego a
 * contestar sola y dejo escrita: <i>"el cargo de una clase, se devenga con la asistencia, con la
 * inscripcion, o con la venta de un pack?"</i>. La respuesta es <b>un valor de esta lista guardado
 * en una columna</b>, no un diseno distinto: los tres caminos terminan en una obligacion, que ya
 * sabe nacer de una sesion (07.01) y desde AKINE-08.06 tambien de una venta.
 */
public enum MomentoDevengo {

	/**
	 * La deuda nace cuando la persona <b>estuvo</b>.
	 *
	 * <p>Es lo que {@code billing.infrastructure.ObligacionDevengador} ya hace con la sesion
	 * cerrada. Para una clase, el disparador seria {@code AsistenciaService}, que desde 08.03 ya
	 * sabe si la persona vino y ya es idempotente por su unique.
	 *
	 * <p>Es el unico momento que lee {@link PoliticaDeDevengo#devengaNoShow()}: sin ausencia
	 * registrada no hay no-show que cobrar.
	 */
	ASISTENCIA,

	/**
	 * La deuda nace cuando la persona <b>reservo el lugar</b>.
	 *
	 * <p>El disparador seria {@code InscripcionService}, que ya es idempotente por
	 * {@code Idempotency-Key}. Cobra el cupo, no la prestacion: el centro que elige esto esta
	 * diciendo que el lugar reservado tiene valor aunque nadie lo use.
	 *
	 * <p><b>No contradice DP-05.</b> La regla maestra dice que ninguna transicion administrativa
	 * prueba que una prestacion ocurrio, y sigue siendo cierta: bajo este momento la deuda no
	 * afirma que hubo prestacion, afirma que hubo reserva. Son dos hechos distintos y el importe se
	 * devenga por el segundo.
	 */
	INSCRIPCION,

	/**
	 * La deuda nace cuando se <b>vende</b> el producto que cubre la prestacion.
	 *
	 * <p>Es lo unico que AKINE-08.06 implementa, porque es lo unico que no requiere elegir entre
	 * dos alternativas del usuario. La clase despues <b>consume credito y no devenga</b>: cobrar el
	 * pack y ademas cada clase es cobrar dos veces lo mismo.
	 */
	VENTA
}
