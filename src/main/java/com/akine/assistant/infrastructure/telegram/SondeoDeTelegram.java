package com.akine.assistant.infrastructure.telegram;

import com.akine.assistant.application.MensajesDeTelegram;
import com.akine.assistant.domain.BotDeTelegram;
import com.akine.assistant.domain.port.BotsDeTelegram;
import com.akine.assistant.domain.port.ClienteDeTelegram;
import com.akine.assistant.domain.port.ClienteDeTelegram.FalloDeTelegram;
import com.akine.assistant.domain.port.ClienteDeTelegram.Novedad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Le pregunta a Telegram por los mensajes nuevos de cada bot activo (docs/diseno/AKINE-telegram.md §5).
 *
 * <p><b>Polling y no webhook</b>: no necesita una URL publica ni HTTPS, asi que funciona igual en
 * una PC y en el despliegue. Con dos instancias, cada bot lo toma una sola a la vez (alquiler en
 * {@code telegram_bot.tomado_hasta}): Telegram rechaza dos {@code getUpdates} cruzados del mismo bot.
 *
 * <p><b>El offset avanza antes de procesar cada mensaje</b>: si el proceso cae a mitad de una carga,
 * ese mensaje se pierde (la persona lo vuelve a mandar), pero nunca se carga dos veces una atencion.
 * Para un dato clinico, "a lo sumo una vez" es lo correcto.
 *
 * <p><b>Nunca propaga</b>: una excepcion que escapa de un {@code @Scheduled} no lo apaga, pero ensucia
 * el log en cada vuelta.
 */
@Component
@ConditionalOnProperty(name = "akine.telegram.enabled", havingValue = "true")
class SondeoDeTelegram {

	private static final Logger log = LoggerFactory.getLogger(SondeoDeTelegram.class);
	/** Mas que lo que tarda en procesar una tanda: una carga con el agente ronda los 10 s por mensaje. */
	static final Duration ALQUILER = Duration.ofMinutes(10);

	private final BotsDeTelegram bots;
	private final ClienteDeTelegram telegram;
	private final MensajesDeTelegram mensajes;

	SondeoDeTelegram(BotsDeTelegram bots, ClienteDeTelegram telegram, MensajesDeTelegram mensajes) {
		this.bots = bots;
		this.telegram = telegram;
		this.mensajes = mensajes;
	}

	@Scheduled(initialDelayString = "${akine.telegram.intervalo-ms:3000}",
			fixedDelayString = "${akine.telegram.intervalo-ms:3000}")
	void sondear() {
		List<Long> activos;
		try {
			activos = bots.activos();
		}
		catch (RuntimeException e) {
			log.warn("Telegram: no se pudieron leer los bots activos: {}", e.getClass().getSimpleName());
			return;
		}
		for (Long organizationId : activos) {
			try {
				sondearBot(organizationId);
			}
			catch (RuntimeException e) {
				log.warn("Telegram: fallo el sondeo del centro {}: {}", organizationId, e.getClass().getSimpleName(), e);
			}
		}
	}

	private void sondearBot(long organizationId) {
		Instant ahora = Instant.now();
		if (!bots.tomar(organizationId, ahora, ahora.plus(ALQUILER))) {
			return;
		}
		try {
			Optional<BotDeTelegram> leido = bots.buscar(organizationId);
			if (leido.isEmpty() || leido.get().token() == null) {
				return;
			}
			BotDeTelegram bot = leido.get();
			List<Novedad> novedades;
			try {
				novedades = telegram.novedades(bot.token(), bot.ultimoUpdateId() + 1);
			}
			catch (FalloDeTelegram e) {
				bots.registrarError(organizationId, e.getMessage(), Instant.now());
				return;
			}
			if (novedades.isEmpty()) {
				bots.avanzar(organizationId, bot.ultimoUpdateId(), Instant.now());
				return;
			}
			for (Novedad novedad : novedades) {
				bots.avanzar(organizationId, novedad.updateId(), Instant.now());
				try {
					mensajes.procesar(bot, novedad);
				}
				catch (FalloDeTelegram e) {
					bots.registrarError(organizationId, e.getMessage(), Instant.now());
				}
				catch (RuntimeException e) {
					log.warn("Telegram: un mensaje del centro {} no se proceso: {}", organizationId,
							e.getClass().getSimpleName(), e);
				}
			}
		}
		finally {
			bots.soltar(organizationId);
		}
	}
}
