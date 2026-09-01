package com.akine.encounter.domain;

import com.akine.encounter.domain.exception.CierreIncompletoException;

/**
 * Lo que hay que declarar para cerrar una atencion (RF-M14-006..008).
 *
 * <h2>Aca SI hay minimos, al reves que en la evaluacion</h2>
 *
 * <p>{@link EvaluacionBase} no exige nada porque una sesion de seguimiento carga dolor y evolucion
 * y nada mas. El cierre es distinto: es el acto que declara <b>que la prestacion ocurrio</b>, y a
 * partir de el se deriva la obligacion economica (07.01) y se actualiza la historia clinica. Una
 * sesion cerrada sin decir que se hizo ni como resulto es un registro que no sirve para nada y que
 * ademas va a generar un cobro.
 *
 * <p>Los minimos son <b>dos</b>, y ninguno es un formalismo:
 *
 * <ul>
 *   <li><b>Asistencia.</b> Sin ella no se sabe si hubo prestacion. Y {@link Asistencia#AUSENTE} es
 *       un cierre legitimo —la ausencia tambien es un hecho— que ademas evita que el turno quede
 *       abierto para siempre. Cuando el paciente no vino, nada mas es obligatorio: pedir resultado
 *       de una atencion que no ocurrio seria pedir que se invente.</li>
 *   <li><b>Nota de cierre, si el paciente vino.</b> La etapa valida "tratamiento o nota
 *       equivalente". El detalle estructurado de tratamientos es 06.04, que el Paquete B dejo
 *       afuera, asi que la nota es lo UNICO que registra que se hizo. No es un campo de descarte
 *       mientras 06.04 no exista.</li>
 * </ul>
 *
 * <p>Lo demas —tolerancia, indicaciones, proxima conducta— queda opcional a proposito: son
 * informacion clinica valiosa, pero exigirlas obligaria a completar campos para poder cerrar una
 * sesion que ya termino, y el profesional los completaria con cualquier cosa.
 *
 * @param respuestaTratamiento como respondio el paciente a lo que se le hizo hoy
 * @param proximaConducta      que sigue. Es lo que convierte una sesion suelta en un tratamiento
 */
public record CierreDeSesion(
		Asistencia asistencia,
		String notaDeCierre,
		String respuestaTratamiento,
		Tolerancia tolerancia,
		String indicaciones,
		ProximaConducta proximaConducta) {

	public CierreDeSesion {
		notaDeCierre = vacioEsNulo(notaDeCierre);
		respuestaTratamiento = vacioEsNulo(respuestaTratamiento);
		indicaciones = vacioEsNulo(indicaciones);
	}

	/** Ver la cabecera: dos minimos, y el segundo solo cuando el paciente vino. */
	public void exigirMinimos() {
		if (asistencia == null) {
			throw new CierreIncompletoException(
					"Hay que declarar si el paciente asistio: sin eso no se sabe si hubo prestacion");
		}
		if (asistencia == Asistencia.PRESENTE && notaDeCierre == null) {
			throw new CierreIncompletoException(
					"Una atencion que ocurrio tiene que decir que se hizo: falta la nota de cierre");
		}
	}

	/** Un texto en blanco es lo mismo que no haberlo cargado. Ver {@code EvaluacionBase}. */
	private static String vacioEsNulo(String valor) {
		return valor == null || valor.isBlank() ? null : valor.trim();
	}
}
