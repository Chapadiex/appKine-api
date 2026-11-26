package com.akine.assistant.infrastructure.config;

import com.akine.assistant.domain.TelegramAjustes;
import com.akine.assistant.infrastructure.telegram.CifradorDeTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cableado de la carga por Telegram. El sondeo ({@code SondeoDeTelegram}) existe solo con el
 * interruptor prendido; la API existe siempre y responde 409 apagada.
 */
@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramConfig {

	@Bean
	public TelegramAjustes telegramAjustes(TelegramProperties properties) {
		return new TelegramAjustes(properties.enabled(), properties.urlApp(), properties.maxBytesAudio());
	}

	/**
	 * Sin clave propia se usa el secreto del JWT, que todo despliegue ya tiene. Prendido y sin
	 * ninguna de las dos, no arranca: guardar tokens sin cifrar no es una opcion.
	 */
	@Bean
	public CifradorDeTokens cifradorDeTokens(TelegramProperties properties,
			@Value("${akine.security.jwt.secret:}") String secretoJwt) {
		String clave = properties.claveCifrado() == null || properties.claveCifrado().isBlank()
				? secretoJwt : properties.claveCifrado();
		if (clave == null || clave.isBlank()) {
			if (properties.enabled()) {
				throw new IllegalStateException(
						"akine.telegram.enabled=true necesita AKINE_TELEGRAM_CLAVE o AKINE_JWT_SECRET para cifrar los tokens");
			}
			return CifradorDeTokens.inutilizable();
		}
		return new CifradorDeTokens(clave);
	}
}
