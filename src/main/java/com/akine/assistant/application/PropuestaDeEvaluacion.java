package com.akine.assistant.application;

/**
 * Como repartiria el modelo un texto dictado entre los campos de la evaluacion de una sesion. Es
 * una propuesta: nada se escribe en la sesion. Todo es opcional; un campo en {@code null} es que el
 * texto no decia nada de el.
 *
 * <p>{@code dolorLateralidad} y {@code evolucion} usan los valores del contrato de la evaluacion
 * ({@code IZQUIERDA}, {@code DERECHA}, {@code BILATERAL}, {@code NO_APLICA}; {@code MEJOR},
 * {@code IGUAL}, {@code PEOR}, {@code SIN_REFERENCIA}). {@code restante} es lo dicho que no entro
 * en ningun campo.
 */
public record PropuestaDeEvaluacion(
		Integer dolorEva,
		String dolorZona,
		String dolorLateralidad,
		String evolucion,
		String objetivoSesion,
		String motivoClinico,
		String limitacionFuncional,
		String restante) {

	static final PropuestaDeEvaluacion VACIA =
			new PropuestaDeEvaluacion(null, null, null, null, null, null, null, null);
}
