package com.akine.encounter.application;

import java.util.List;

/**
 * La comparacion de esta sesion contra la cerrada anterior (RF-M14-004).
 *
 * <h2>Contra que se compara, y por que el Caso manda</h2>
 *
 * <p>El baseline es la ultima sesion <b>cerrada</b> del paciente que tenga mediciones, acotada al
 * <b>mismo Caso</b> cuando la sesion tiene caso. La acotacion no es un detalle: 04.03 admite
 * varios casos activos a la vez —una rodilla y un hombro— y comparar el ROM de rodilla contra la
 * sesion del hombro es comparar contra nada. Cuando la sesion no tiene caso se cae a "la anterior
 * del paciente", que es lo unico que existe para todas las sesiones anteriores a 04.03.
 *
 * <p><b>Queda declarado fuera de alcance:</b> comparar la misma medida entre casos distintos del
 * mismo paciente. Es legitimo quererlo y necesita otro camino, no este.
 *
 * <h2>Sin baseline NO es un error</h2>
 *
 * <p>{@code sesionAnteriorId} viene {@code null} y cada fila trae {@code anterior = null}. Una
 * primera evaluacion, o una re-evaluacion cuya sesion previa quedo sin cerrar, son situaciones
 * normales: responder un error obligaria a la pantalla a distinguir "todavia no hay con que
 * comparar" de "algo salio mal", que para el profesional son cosas muy distintas.
 */
public record ComparacionDeMedicionesView(

		/** La sesion que hace de baseline, o {@code null} si no hay ninguna. */
		Long sesionAnteriorId,

		/**
		 * {@code true} si la comparacion se acoto al Caso de la sesion.
		 *
		 * <p>La pantalla lo necesita para poder decir "comparado dentro de este caso" en vez de
		 * dejar creer que se comparo contra toda la historia del paciente — que con dos casos
		 * abiertos serian dos afirmaciones muy distintas.
		 */
		boolean acotadaAlCaso,

		/**
		 * Una fila por (medida, lado), con la union de lo de hoy y lo de la sesion anterior.
		 *
		 * <p>Es la union y no la interseccion: las medidas que se tomaron la vez pasada y todavia
		 * no hoy <b>tienen que aparecer</b>, porque son precisamente las que el profesional va a
		 * volver a tomar. Ese es el "copiar-previo-y-ajustar" del requisito, sin ningun endpoint
		 * de copiar.
		 */
		List<MedicionComparadaView> medidas) {
}
