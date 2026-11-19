package com.akine.assistant.spi;

/**
 * Una accion que el asistente propone y, confirmada, graba. La implementa el modulo dueño del dato en
 * su {@code infrastructure}, igual que {@link HerramientaDelAsistente}: {@code assistant} no importa a
 * nadie.
 *
 * <p><b>Ninguna implementacion abre una transaccion que envuelva a los servicios</b>: varios de
 * ellos (el cobro, el lote de asistencia, el cierre de sesion) exigen que no haya una activa. Cada
 * llamada al servicio de aplicacion vive en la suya.
 *
 * <p>Un {@code AccessDeniedException} del servicio se deja propagar en los dos metodos: el asistente
 * lo convierte en "sin permiso" en un solo lugar.
 */
public interface AccionDelAsistente {

	/** El tipo que resuelve. Una implementacion por tipo. */
	TipoDeAccion tipo();

	/** Solo lee: resuelve de que se habla, valida las precondiciones y arma la tarjeta. */
	PropuestaDeAccion preparar(ActorDelAsistente actor, PedidoDeAccion pedido);

	/**
	 * Graba lo que la persona confirmo. <b>No confia en la propuesta</b>: revalida todo por el camino
	 * normal del servicio. Un rechazo de negocio vuelve como {@link ResultadoDeAccion#rechazada}, no
	 * como excepcion.
	 *
	 * @param accionId el id de la propuesta: la base de la clave de idempotencia de lo que se cree
	 */
	ResultadoDeAccion ejecutar(ActorDelAsistente actor, long accionId, Tarjeta confirmada);
}
