package com.akine.clinical.spi;

/**
 * Lo que ocurrio dentro de un Caso para una oferta, contado y sin contenido clinico.
 *
 * <p>Los dos numeros salen de sesiones <b>cerradas</b>: el reparto lo hace la asistencia, que
 * {@code CierreDeSesion} exige al cerrar. Una atencion a la que el paciente vino es una
 * realizacion; una a la que no vino no lo es, y tampoco es nada: es una sesion que se consumio del
 * plan sin tratamiento, y el profesional necesita verla para entender por que el avance no avanza.
 *
 * @param ofertaId    la oferta contra la que se atendio. Es el eje del conteo porque es lo que
 *                    {@code plan_item} planifica; la practica individual dentro de la sesion es
 *                    06.04 y todavia no existe
 * @param realizadas  sesiones cerradas con el paciente presente
 * @param canceladas  sesiones cerradas con el paciente ausente. <b>No</b> son turnos cancelados:
 *                    cancelar un turno es de {@code scheduling} y no llega hasta aca, justamente
 *                    porque un turno cancelado no prueba ni desmiente que hubo atencion (DP-05)
 */
public record RealizadoPorOferta(long ofertaId, int realizadas, int canceladas) {

	public RealizadoPorOferta {
		if (realizadas < 0 || canceladas < 0) {
			throw new IllegalArgumentException("Un conteo de sesiones no puede ser negativo");
		}
	}
}
