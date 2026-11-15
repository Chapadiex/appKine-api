package com.akine.clinical.spi;

/**
 * La puerta de la Historia Clinica para modulos que muestran contenido clinico fuera de
 * {@code clinical} (M31, RN-M31-003).
 *
 * <p>Aplica exactamente la regla de {@code clinical}: el permiso pedido, y relacion asistencial
 * con la persona o justificacion declarada. Sin ninguna de las dos, audita el rechazo y lanza la
 * misma excepcion que la Historia Clinica, que su advice convierte en 403
 * {@code justificacion-de-acceso-requerida}. Quien llama no reimplementa nada de esto.
 *
 * <p><b>No audita la lectura</b>: sabe quien entro, no que se leyo. La lectura la audita quien la
 * hace, en su transaccion, con lo que devuelve este metodo. Si el permiso vino de un acceso de
 * soporte, tambien registra {@code SUPPORT_ACCESS_USED}.
 */
public interface AccesoClinicoDirectory {

	/** Codigo del permiso de lectura clinica. */
	String HC_READ = "hc:read";

	/**
	 * @param permissionCode el permiso que exige la lectura; hoy siempre {@link #HC_READ}
	 * @param operacion      nombre legible de la operacion, para la auditoria del rechazo
	 */
	AccesoClinicoConcedido exigir(
			ActorClinico actor,
			String permissionCode,
			long personaId,
			String justificacion,
			String operacion);
}
