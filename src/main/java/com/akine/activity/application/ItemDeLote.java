package com.akine.activity.application;

/**
 * El resultado de <b>un</b> participante dentro de un lote (RF-M13-008).
 *
 * <p>O trae la asistencia, o trae el error de ese participante. Nunca los dos, nunca ninguno:
 * "resultados parciales explicitos" significa que el cliente puede decir, fila por fila, que paso
 * — y un fallo individual no puede ocultar los resultados de los demas.
 *
 * @param problemType la URI del tipo de problema, la misma que habria viajado en un
 *                    {@code ProblemDetail} si la operacion hubiera sido individual. Asi el cliente
 *                    usa el mismo mapeo que ya tiene y no un segundo vocabulario de errores
 */
public record ItemDeLote(
		long inscripcionId,
		ResultadoDeAsistencia resultado,
		String problemType,
		String detalle) {

	public static ItemDeLote ok(long inscripcionId, ResultadoDeAsistencia resultado) {
		return new ItemDeLote(inscripcionId, resultado, null, null);
	}

	public static ItemDeLote error(long inscripcionId, String problemType, String detalle) {
		return new ItemDeLote(inscripcionId, null, problemType, detalle);
	}

	public boolean exitoso() {
		return resultado != null;
	}
}
