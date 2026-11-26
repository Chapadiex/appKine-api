package com.akine.assistant.domain.port;

import com.akine.assistant.domain.VinculoDeTelegram;

import java.time.Instant;
import java.util.Optional;

/** {@code telegram_vinculo}. */
public interface VinculosDeTelegram {

	Optional<VinculoDeTelegram> deLaCuenta(long organizationId, long cuentaId);

	Optional<VinculoDeTelegram> delUsuario(long organizationId, long telegramUsuarioId);

	/**
	 * Deja un codigo nuevo para la cuenta, en la sede dada. Si ya estaba vinculada, el vinculo sigue
	 * hasta que el codigo se use.
	 *
	 * @return {@code false} si el codigo ya lo tenia otra cuenta del centro (se reintenta con otro)
	 */
	boolean nuevoCodigo(long organizationId, long cuentaId, long consultorioId, String codigo, Instant venceEn,
			Instant ahora);

	/**
	 * Usa el codigo: lo borra y ata la cuenta al usuario de Telegram. Si ese usuario estaba atado a
	 * otra cuenta del centro, la suelta.
	 *
	 * @return la cuenta vinculada, o vacio si el codigo no existe o vencio
	 */
	Optional<VinculoDeTelegram> usarCodigo(long organizationId, String codigo, long telegramUsuarioId,
			String telegramNombre, Instant ahora);

	void desvincular(long organizationId, long cuentaId, Instant ahora);
}
