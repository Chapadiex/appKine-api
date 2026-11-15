package com.akine.assistant.infrastructure.config;

import com.akine.assistant.domain.AsistenteAjustes;
import com.akine.assistant.domain.port.ModeloDeLenguaje;
import com.akine.assistant.infrastructure.ModeloDelAsistenteDePrueba;
import com.akine.assistant.infrastructure.OllamaModeloDelAsistente;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Set;

/**
 * Cableado del modulo {@code assistant}.
 *
 * <p>El modelo existe solo con el asistente prendido: apagado no hay bean de
 * {@link ModeloDeLenguaje} y quien lo use lo pide por {@code ObjectProvider}. Los nombres de los
 * beans no repiten los de {@code knowledge} ({@code ollamaModeloDeLenguaje},
 * {@code modeloDeLenguajeDePrueba}): con la biblioteca y el asistente prendidos a la vez, dos beans
 * con el mismo nombre no dejan arrancar a Spring.
 */
@Configuration
@EnableConfigurationProperties(AsistenteProperties.class)
public class AsistenteConfig {

	static final Set<String> TIPOS_DE_MODELO = Set.of("ollama", "prueba");

	@Bean
	public AsistenteAjustes asistenteAjustes(AsistenteProperties properties) {
		exigirTipoDeModelo(properties);
		return new AsistenteAjustes(properties.enabled(),
				Duration.ofSeconds(properties.modelo().timeoutSegundos()));
	}

	/** Prendido con un tipo desconocido no habria modelo y nadie se enteraria: no arranca. */
	static void exigirTipoDeModelo(AsistenteProperties properties) {
		String tipo = properties.modelo().tipo();
		if (properties.enabled() && !TIPOS_DE_MODELO.contains(tipo)) {
			throw new IllegalStateException("akine.asistente.modelo.tipo='" + tipo
					+ "' no es valido: tiene que ser ollama o prueba");
		}
	}

	@Bean
	@ConditionalOnExpression("${akine.asistente.enabled:false}"
			+ " and '${akine.asistente.modelo.tipo:ollama}' == 'ollama'")
	public ModeloDeLenguaje modeloDelAsistente(AsistenteProperties properties) {
		AsistenteProperties.Modelo modelo = properties.modelo();
		return new OllamaModeloDelAsistente(modelo.url(), modelo.nombre(), modelo.keepAlive());
	}

	/** Determinista, para tests y desarrollo sin Ollama. */
	@Bean
	@ConditionalOnExpression("${akine.asistente.enabled:false}"
			+ " and '${akine.asistente.modelo.tipo:ollama}' == 'prueba'")
	public ModeloDeLenguaje modeloDelAsistenteDePrueba() {
		return new ModeloDelAsistenteDePrueba();
	}
}
