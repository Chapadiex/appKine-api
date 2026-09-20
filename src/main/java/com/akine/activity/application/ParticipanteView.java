package com.akine.activity.application;

/**
 * Un participante con su identidad, para la lista que ve el mostrador.
 *
 * <p><b>Es la unica proyeccion de esta etapa que lleva datos de persona</b>, y por eso es la unica
 * detras de {@code inscripcion:read}. 08.01 decidio que ninguna respuesta de clase expone
 * participantes y esta etapa no lo deshace: {@code ClaseView} sigue sin nombres, y quien quiera la
 * lista la pide aca.
 *
 * <p>Nombre y documento, nada mas. Ni correo, ni telefono, ni un solo dato clinico: para pasar
 * lista alcanza con saber a quien se esta llamando.
 */
public record ParticipanteView(
		InscripcionView inscripcion,
		String apellido,
		String nombre,
		String tipoDocumento,
		String numeroDocumento) {
}
