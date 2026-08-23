package com.akine.notification.domain;

/**
 * Estados de entrega de una fila del outbox.
 *
 * <p>Mapeo a los unicos estados que la spec nombra (RF-M26-004): <b>enviado</b> = ENVIADA,
 * <b>fallido</b> = FALLIDA o AGOTADA, <b>reintentado</b> = REINTENTABLE. PENDIENTE y
 * PROCESANDO son mecanica del worker, no vocabulario de negocio.
 */
public enum OutboxStatus {

	/** Recien encolada. Es el unico estado en el que puede nacer una fila. */
	PENDIENTE,

	/** Tomada por un worker. Lleva {@code procesandoDesde} como lease. */
	PROCESANDO,

	/** Entregada al canal. Terminal y feliz. */
	ENVIADA,

	/**
	 * Fallo NO reintentable: destinatario invalido, template inexistente, token ya consumido.
	 * Reintentar solo gastaria intentos. Sale de aca unicamente por reintento administrativo.
	 */
	FALLIDA,

	/** Fallo transitorio. Vuelve a la cola con {@code proximaEjecucionEn} en el futuro. */
	REINTENTABLE,

	/**
	 * Se acabaron los intentos. Terminal salvo reintento administrativo explicito, que
	 * reinicia el contador.
	 */
	AGOTADA;

	/** Estado en el que nace toda notificacion. */
	public static final OutboxStatus ESTADO_INICIAL = PENDIENTE;

	/** Indica si el worker puede tomar una fila en este estado. */
	public boolean esReclamable() {
		return this == PENDIENTE || this == REINTENTABLE;
	}

	/** Indica si la fila puede volver a la cola por accion de un administrador. */
	public boolean admiteReintentoManual() {
		return this == FALLIDA || this == AGOTADA;
	}
}
