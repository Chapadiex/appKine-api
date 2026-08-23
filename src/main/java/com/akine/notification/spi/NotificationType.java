package com.akine.notification.spi;

/**
 * Tipos de notificacion que el outbox sabe entregar en 01.02.
 *
 * <p>El tipo determina el template (asunto y cuerpo) y si la entrega necesita reconstruir un
 * enlace seguro. Vive en {@code spi} —y no en {@code domain}— porque forma parte del contrato
 * que otros modulos usan al encolar: si viviera en {@code domain}, quien encola tendria que
 * importar el modelo interno de {@code notification} y eso rompe la regla de ownership.
 *
 * <p>En 01.02 solo hay eventos de identidad. Cada etapa posterior agrega los suyos; agregar un
 * valor es aditivo y no rompe a nadie.
 */
public enum NotificationType {

	/** Activacion de una cuenta recien registrada. Necesita enlace seguro. */
	ACTIVACION_CUENTA(true),

	/** Invitacion a colaborar en una organizacion. Necesita enlace seguro. */
	INVITACION_COLABORADOR(true),

	/** Recuperacion de contrasena. Necesita enlace seguro. */
	RECUPERACION_PASSWORD(true),

	/**
	 * Aviso "ya tenes cuenta" que se envia cuando alguien intenta registrarse con un email
	 * existente (challenge D-4: el registro responde 202 uniforme y no revela existencia).
	 * No lleva enlace: solo invita a iniciar sesion o a recuperar la contrasena.
	 */
	CUENTA_YA_REGISTRADA(false);

	private final boolean requiereEnlaceSeguro;

	NotificationType(boolean requiereEnlaceSeguro) {
		this.requiereEnlaceSeguro = requiereEnlaceSeguro;
	}

	/**
	 * Indica si la entrega necesita que se reconstruya un enlace de un solo uso.
	 *
	 * <p>Cuando es {@code true}, encolar exige una referencia opaca al token
	 * ({@code referenciaTokenId}): el enlace NO viaja en la fila del outbox (T-11).
	 */
	public boolean requiereEnlaceSeguro() {
		return requiereEnlaceSeguro;
	}
}
