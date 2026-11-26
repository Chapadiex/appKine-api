package com.akine.assistant.domain.port;

import com.akine.assistant.domain.BotDeTelegram;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** {@code telegram_bot}. El token se cifra y se descifra adentro: aca viaja en claro. */
public interface BotsDeTelegram {

	Optional<BotDeTelegram> buscar(long organizationId);

	/** Conecta o reemplaza el bot del centro y empieza de cero el offset si cambio de bot. */
	void guardar(long organizationId, String token, long botId, String usuario, long cuentaId, Instant ahora);

	/** Borra el token y deja el bot inactivo. La fila queda. */
	void desconectar(long organizationId, Instant ahora);

	/** Los centros con bot activo. */
	List<Long> activos();

	/**
	 * Toma el bot para sondearlo hasta {@code hasta}, si nadie lo tiene. Con dos instancias, una sola
	 * pregunta a Telegram por bot a la vez.
	 */
	boolean tomar(long organizationId, Instant ahora, Instant hasta);

	void soltar(long organizationId);

	/** Recuerda el ultimo update procesado y que Telegram respondio. */
	void avanzar(long organizationId, long updateId, Instant ahora);

	void registrarError(long organizationId, String error, Instant ahora);
}
