package com.akine.notification.spi;

/**
 * Unico punto de entrada para encolar una notificacion. Lo consumen {@code identity} (01.02) y
 * las etapas siguientes; nadie escribe la tabla {@code notification_outbox} directamente.
 *
 * <p><b>Se invoca DENTRO de la transaccion del llamador, con propagacion
 * {@code MANDATORY}.</b> Esa es la esencia del patron: la fila del outbox se confirma junto
 * con la operacion de negocio, o no se confirma ninguna de las dos. De ahi salen las dos
 * garantias que pide la spec:
 *
 * <ul>
 *   <li><b>No se pierde</b> — si el negocio comiteo, la notificacion existe. No hay ventana
 *       entre "la cuenta quedo creada" y "alguien se acordo de mandar el mail".</li>
 *   <li><b>No revierte el negocio</b> (RN-M26-001) — el envio ocurre despues, en el worker,
 *       fuera de esta transaccion. Que el SMTP este caido no puede deshacer una cuenta ya
 *       creada.</li>
 * </ul>
 *
 * <p><b>Por que {@code MANDATORY} y no {@code REQUIRED}.</b> {@code REQUIRED} abriria una
 * transaccion propia cuando el llamador no tiene ninguna, y ahi el patron se rompe en
 * silencio: la fila del outbox comitea por su cuenta y el negocio puede fallar despues,
 * dejando enviado un mail sobre algo que nunca paso. {@code MANDATORY} convierte ese error de
 * uso en una excepcion en el primer test que lo ejercite, en vez de en un mail fantasma en
 * produccion. El costo —que el llamador tenga que estar en una transaccion— es exactamente lo
 * que el patron exige.
 *
 * <p><b>Idempotente por construccion.</b> Reintentar la transaccion del productor con la misma
 * {@link NotificationEnqueueCommand#claveIdempotente()} no crea una segunda notificacion:
 * devuelve el id de la existente (RF-M26-005, RN-M26-003, seccion 34).
 */
public interface NotificationOutbox {

	/**
	 * Encola la notificacion en la transaccion en curso.
	 *
	 * @return id de la fila del outbox; el de la fila ya existente si la clave se repite
	 * @throws IllegalArgumentException si el comando esta incompleto, si el tipo exige enlace
	 *                                  seguro y no trae {@code referenciaTokenId}, o si los
	 *                                  datos de render contienen algo con pinta de token,
	 *                                  enlace o secreto (T-11)
	 * @throws org.springframework.transaction.IllegalTransactionStateException si no hay una
	 *                                  transaccion activa del llamador
	 */
	long enqueue(NotificationEnqueueCommand command);
}
