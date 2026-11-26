package com.akine.assistant.domain;

import java.time.Instant;

/**
 * Una cuenta de AKINE con su usuario de Telegram.
 *
 * @param consultorioId     la sede en la que carga lo que manda por Telegram
 * @param codigo            el codigo pendiente para vincular, o {@code null}
 * @param telegramUsuarioId el usuario vinculado, o {@code null} si todavia no se vinculo
 */
public record VinculoDeTelegram(
		long organizationId,
		long cuentaId,
		long consultorioId,
		String codigo,
		Instant codigoVenceEn,
		Long telegramUsuarioId,
		String telegramNombre,
		Instant vinculadoEn) {

	public boolean vinculado() {
		return telegramUsuarioId != null;
	}

	public boolean codigoVigente(Instant ahora) {
		return codigo != null && codigoVenceEn != null && ahora.isBefore(codigoVenceEn);
	}
}
