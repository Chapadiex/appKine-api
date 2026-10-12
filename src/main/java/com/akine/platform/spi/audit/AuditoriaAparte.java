package com.akine.platform.spi.audit;

/**
 * Auditoria que tiene que quedar escrita <b>aunque la operacion que la origina falle</b>.
 *
 * <p>Es la excepcion acotada a la regla de {@link AuditTrail} —la auditoria va dentro de la
 * transaccion de negocio—, y vale solo para hechos que no confirman ninguna mutacion: un rechazo
 * por permiso o por limite de plan, o el uso de un acceso de soporte para leer. Si se escribieran
 * en la transaccion de negocio, el rollback del 403 o del 404 se los llevaria puestos.
 *
 * <p>La entrada se escribe en una transaccion propia que corre <b>cuando la del pedido ya
 * termino</b> y devolvio su conexion (ver
 * {@link com.akine.platform.spi.transaccion.AlTerminarLaTransaccion}), o en el acto si no hay
 * ninguna abierta. Hasta el 11/10/2026 cada modulo lo resolvia con un {@code REQUIRES_NEW}
 * llamado desde adentro, que retenia dos conexiones por pedido y trababa el pool
 * (docs/carga.md §8).
 *
 * <p><b>No es para mutaciones.</b> Una mutacion confirmada cuya auditoria se escribe aparte puede
 * quedar sin rastro; esas van por {@link AuditTrail}, dentro de la transaccion.
 */
@FunctionalInterface
public interface AuditoriaAparte {

	/**
	 * Registra el hecho en su propia transaccion, termine como termine la del pedido.
	 *
	 * @param entry hecho a auditar, ya armado: el instante y el {@code correlationId} son los del
	 *              momento en que ocurrio, no los de la escritura
	 */
	void registrar(AuditEntry entry);
}
