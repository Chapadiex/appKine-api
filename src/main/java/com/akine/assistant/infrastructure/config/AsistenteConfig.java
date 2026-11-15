package com.akine.assistant.infrastructure.config;

import com.akine.assistant.application.Interpretador;
import com.akine.assistant.domain.AsistenteAjustes;
import com.akine.assistant.domain.port.ModeloDeLenguaje;
import com.akine.assistant.infrastructure.ModeloDelAsistenteDePrueba;
import com.akine.assistant.infrastructure.OllamaModeloDelAsistente;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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

	static final String PROMPT = "asistente/prompt-interpretador.txt";
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

	/**
	 * El interpretador existe siempre; el modelo lo pide en cada llamada, asi que con el asistente
	 * apagado no hay bean de modelo y nadie lo llama (el servicio corta antes con 409).
	 */
	@Bean
	public Interpretador interpretadorDelAsistente(ObjectProvider<ModeloDeLenguaje> modelo, AsistenteAjustes ajustes) {
		return new Interpretador(modelo::getObject, leerPrompt(PROMPT), ajustes.plazo());
	}

	/**
	 * El mismo archivo que mide {@code docs/asistente/evaluacion/evaluar.mjs}: lo que corre es lo que
	 * se midio. Si falta, la aplicacion no arranca.
	 */
	static String leerPrompt(String ruta) {
		try (InputStream entrada = new ClassPathResource(ruta).getInputStream()) {
			return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException("No se pudo leer el prompt del interpretador: " + ruta, e);
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
