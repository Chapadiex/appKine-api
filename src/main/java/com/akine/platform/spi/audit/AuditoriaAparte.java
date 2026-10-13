package com.akine.platform.spi.audit;

/**
 * Auditoria que tiene que quedar escrita <b>aunque la operacion que la origina falle</b>.
 *
 * <p>Es la excepcion acotada a la regla de {@link AuditTrail} —la auditoria va dentro de la
 * transaccion de negocio—. Hasta el 11/10/2026 cada modulo la resolvia con un
 * {@code REQUIRES_NEW} llamado desde adentro, que retenia dos conexiones por pedido y trababa el
 * pool (docs/carga.md §8). Ahora lo que se escribe aparte corre cuando la transaccion del pedido ya
 * termino y devolvio su conexion (ver
 * {@link com.akine.platform.spi.transaccion.AlTerminarLaTransaccion}), o en el acto si no hay
 * ninguna abierta.
 *
 * <p>Hay dos garantias distintas, y cada llamador elige la suya:
 *
 * <ul>
 *   <li>{@link #registrar}: <b>al mejor esfuerzo</b>, para los rechazos (403 por permiso, limite de
 *       plan). El pedido ya fallo; si ademas falla la escritura, se loguea en ERROR.</li>
 *   <li>{@link #registrarAccesoDeSoporte}: <b>obligatoria</b>, para el uso de un acceso de soporte,
 *       que tiene que ser trazable siempre (ver su javadoc).</li>
 * </ul>
 */
public interface AuditoriaAparte {

	/**
	 * Registra un rechazo en su propia transaccion, termine como termine la del pedido. Al mejor
	 * esfuerzo: si la escritura falla despues de la transaccion, se loguea y el pedido sigue con
	 * su propio desenlace.
	 *
	 * @param entry hecho a auditar, ya armado: el instante y el {@code correlationId} son los del
	 *              momento en que ocurrio, no los de la escritura
	 */
	void registrar(AuditEntry entry);

	/**
	 * Registra el uso de un acceso de soporte con la garantia de la regla 01.01: <b>si no queda
	 * escrito, la operacion no sale</b>.
	 *
	 * <ul>
	 *   <li><b>Dentro de una transaccion de escritura</b> —toda mutacion, y las lecturas que no son
	 *       {@code readOnly}—: se escribe ahi mismo, por {@link AuditTrail}. Si falla, la
	 *       transaccion revierte. Si la transaccion revierte por otra causa, se vuelve a escribir
	 *       aparte cuando termino: el intento existio y tiene que quedar.</li>
	 *   <li><b>En una lectura {@code readOnly}</b> —donde un {@code save} no llega a la base— o sin
	 *       transaccion: se escribe aparte cuando la transaccion termino. Si commiteo y la escritura
	 *       falla, la falla sale del {@code commit} y la lectura no se entrega. Si revirtio, el pedido
	 *       ya falla con su propia excepcion, que no se tapa; la falla de aca se loguea.</li>
	 * </ul>
	 */
	void registrarAccesoDeSoporte(AuditEntry entry);
}
