package com.akine.scheduling.application;

/**
 * Lo que la agenda necesita de un turno ademas de la reserva: para que es y en que anda.
 *
 * <p><b>{@code estadoOperativo} se deriva y no se guarda.</b> Turno, Recepcion y Sesion son tres
 * maquinas independientes (DP-05) y ninguna se fusiona con otra; lo que la agenda muestra es la foto
 * combinada de las tres, y por eso los valores no existen en ninguna tabla:
 *
 * <pre>
 *   RESERVADO, CONFIRMADO   el turno, sin nada mas
 *   PRESENTE                recepcion abierta: el paciente esta en el centro
 *   RETIRADO                llego y se fue sin ser atendido
 *   EN_ATENCION             sesion abierta
 *   ATENDIDO                sesion cerrada: es lo unico que prueba que se atendio
 *   AUSENTE, CANCELADO      el turno cerro su ciclo
 * </pre>
 *
 * <p>Sigue sin haber nada clinico: del caso viaja el numero, y del plan, que sesion es.
 *
 * @param sesionNumero         que sesion de su prestacion es este turno dentro del plan, o {@code null}
 * @param sesionesPlanificadas cuantas planifica el plan para esa prestacion, o {@code null}
 * @param sesionId             la atencion del turno, si ya empezo
 */
public record OperativoDeTurnoView(
		String estadoOperativo,
		Long casoId,
		Integer numeroCaso,
		Long planId,
		Integer sesionNumero,
		Integer sesionesPlanificadas,
		boolean sobreturno,
		String sobreturnoMotivo,
		String profesionalNombre,
		String espacioNombre,
		Long sesionId,
		String origenCancelacion,
		Boolean ausenciaConsumeSesion) {
}
