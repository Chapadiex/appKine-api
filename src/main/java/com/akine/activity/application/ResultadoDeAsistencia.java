package com.akine.activity.application;

/**
 * Como termino un registro de asistencia.
 *
 * <p>Los tres desenlaces son distintos y la pantalla los muestra distinto, asi que viajan
 * explicitos en vez de deducirse de comparar campos:
 *
 * <ul>
 *   <li>{@code registrada} — no habia hecho y ahora lo hay. <b>201</b></li>
 *   <li>{@code corregida} — habia hecho con otro resultado y se cambio, con motivo. <b>200</b></li>
 *   <li>ninguno de los dos — el pedido repetia lo ya registrado y <b>no se escribio nada</b>.
 *       <b>200</b>. Es la idempotencia de la etapa, y sale del hecho, no de una clave</li>
 * </ul>
 */
public record ResultadoDeAsistencia(
		AsistenciaView asistencia,
		InscripcionView inscripcion,
		boolean registrada,
		boolean corregida) {

	/** {@code true} si esta llamada no cambio nada porque repetia lo ya registrado. */
	public boolean sinCambios() {
		return !registrada && !corregida;
	}
}
