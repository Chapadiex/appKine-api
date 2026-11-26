package com.akine.assistant.domain.port;

import java.util.List;

/**
 * La Bot API de Telegram, lo justo para el sondeo. Cada metodo recibe el token del bot del centro.
 *
 * <p>Un fallo de red o un rechazo de Telegram es {@link FalloDeTelegram}, con un mensaje que se
 * puede mostrar y guardar: nunca lleva el token.
 */
public interface ClienteDeTelegram {

	/** {@code getMe}: valida el token y dice de que bot es. */
	IdentidadDelBot conocer(String token);

	/** {@code getUpdates} desde {@code offset}, sin esperar. */
	List<Novedad> novedades(String token, long offset);

	/** {@code sendMessage}, en respuesta a un mensaje si {@code responderA} no es {@code null}. */
	void enviar(String token, long chatId, Long responderA, String texto, List<Boton> botones);

	/** {@code answerCallbackQuery}: saca el reloj del boton tocado. */
	void contestarBoton(String token, String callbackId);

	/** {@code getFile} y la descarga del archivo. */
	byte[] descargar(String token, String fileId, long maxBytes);

	record IdentidadDelBot(long id, String usuario, String nombre) {
	}

	/** Un boton debajo del mensaje: al tocarlo vuelve {@code datos} (hasta 64 bytes). */
	record Boton(String texto, String datos) {
	}

	/**
	 * Un update, ya reducido a lo que el asistente usa: un mensaje (texto o voz) o un boton tocado.
	 *
	 * @param vozFileId el archivo de la nota de voz o del audio, o {@code null}
	 * @param callbackId el boton tocado, o {@code null} si es un mensaje
	 */
	record Novedad(
			long updateId,
			long chatId,
			Long mensajeId,
			long usuarioId,
			String nombreUsuario,
			String texto,
			String vozFileId,
			String vozMime,
			Long vozBytes,
			String callbackId,
			String callbackDatos) {

		public boolean esBoton() {
			return callbackId != null;
		}
	}

	class FalloDeTelegram extends RuntimeException {

		private final boolean tokenRechazado;

		public FalloDeTelegram(String mensaje, boolean tokenRechazado) {
			super(mensaje);
			this.tokenRechazado = tokenRechazado;
		}

		/** Telegram dijo 401 o 404: el token no existe o lo revocaron en @BotFather. */
		public boolean tokenRechazado() {
			return tokenRechazado;
		}
	}
}
