package com.akine.assistant.infrastructure.config;

import com.akine.assistant.application.InterpretadorDeAcciones;
import com.akine.assistant.domain.AsistenteAjustes;
import com.akine.platform.spi.modelo.ConfiguracionDeModelo;
import com.akine.platform.spi.modelo.FabricaDeModelos;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * {@link ModeloDeLenguaje} y quien lo use lo pide por {@code ObjectProvider}. El puerto es el de
 * {@code platform.spi.modelo}, compartido con {@code knowledge}: con la biblioteca y el asistente
 * prendidos hay dos beans del mismo tipo, y cada uno se pide por su nombre ({@link #MODELO}).
 */
@Configuration
@EnableConfigurationProperties(AsistenteProperties.class)
public class AsistenteConfig {

	static final String PROMPT_ACCIONES = "asistente/prompt-acciones.txt";
	static final Set<String> TIPOS_DE_MODELO = Set.of("ollama", "prueba");

	/** Nombre del bean del modelo del asistente: hay otros del mismo tipo. */
	public static final String MODELO = "modeloAsistente";

	/** Lo que contesta el modelo de prueba por defecto. */
	static final String FUERA_DE_ALCANCE = "{\"intencion\":\"FUERA_DE_ALCANCE\",\"persona\":null,"
			+ "\"clase\":null,\"periodo\":null,\"reporte\":null}";

	@Bean
	public AsistenteAjustes asistenteAjustes(AsistenteProperties properties) {
		exigirTipoDeModelo(properties);
		return new AsistenteAjustes(properties.enabled(),
				Duration.ofSeconds(properties.modelo().timeoutSegundos()), properties.sql().enabled(),
				properties.acciones().enabled(), Duration.ofMinutes(properties.acciones().vigenciaMinutos()));
	}

	/** Prendido con un tipo desconocido no habria modelo y nadie se enteraria: no arranca. */
	static void exigirTipoDeModelo(AsistenteProperties properties) {
		String tipo = properties.modelo().tipo();
		if (properties.enabled() && !TIPOS_DE_MODELO.contains(tipo)) {
			throw new IllegalStateException("akine.asistente.modelo.tipo='" + tipo
					+ "' no es valido: tiene que ser ollama o prueba");
		}
	}

	/** El de las acciones, con su propio prompt: el de las consultas no se toca. Existe siempre. */
	@Bean
	public InterpretadorDeAcciones interpretadorDeAccionesDelAsistente(
			@Qualifier(MODELO) ObjectProvider<ModeloDeLenguaje> modelo, AsistenteAjustes ajustes) {
		return new InterpretadorDeAcciones(modelo::getObject, leerPrompt(PROMPT_ACCIONES), ajustes.plazo());
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
			throw new UncheckedIOException("No se pudo leer el prompt del asistente: " + ruta, e);
		}
	}

	/**
	 * El modelo del asistente, solo con el asistente prendido. Con {@code ollama} va con cupo
	 * ({@code akine.asistente.modelo.concurrencia}, al menos 1); el de prueba, sin cupo y contestando
	 * {@code FUERA_DE_ALCANCE} hasta que un test le pida otra cosa.
	 */
	@Bean(MODELO)
	@ConditionalOnExpression("${akine.asistente.enabled:false}"
			+ " and ('${akine.asistente.modelo.tipo:ollama}' == 'ollama'"
			+ " or '${akine.asistente.modelo.tipo:ollama}' == 'prueba')")
	public ModeloDeLenguaje modeloAsistente(FabricaDeModelos fabrica, AsistenteProperties properties) {
		return fabrica.crear(configuracionDelModelo(properties.modelo()));
	}

	static ConfiguracionDeModelo configuracionDelModelo(AsistenteProperties.Modelo modelo) {
		boolean ollama = ConfiguracionDeModelo.OLLAMA.equals(modelo.tipo());
		if (ollama && modelo.concurrencia() < 1) {
			throw new IllegalArgumentException("La concurrencia del modelo tiene que ser al menos 1");
		}
		return new ConfiguracionDeModelo(modelo.tipo(), modelo.url(), modelo.nombre(), modelo.keepAlive(),
				ollama ? modelo.concurrencia() : 0, FUERA_DE_ALCANCE);
	}
}
