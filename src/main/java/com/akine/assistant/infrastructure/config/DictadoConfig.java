package com.akine.assistant.infrastructure.config;

import com.akine.assistant.application.RepartidorDeEvaluacion;
import com.akine.assistant.domain.DictadoAjustes;
import com.akine.assistant.domain.port.ModeloDeLenguaje;
import com.akine.assistant.domain.port.Transcriptor;
import com.akine.assistant.infrastructure.TranscriptorConCupo;
import com.akine.assistant.infrastructure.TranscriptorDePrueba;
import com.akine.assistant.infrastructure.WhisperTranscriptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Set;

/**
 * Cableado del dictado por voz (docs/diseno/AKINE-dictado-por-voz.md).
 *
 * <p>El transcriptor existe siempre, aunque el dictado este apagado: crearlo no abre conexiones, y
 * el servicio corta con 409 antes de usarlo. Asi no hace falta pedirlo por {@code ObjectProvider}.
 */
@Configuration
@EnableConfigurationProperties(DictadoProperties.class)
public class DictadoConfig {

	static final String PROMPT_EVALUACION = "asistente/prompt-evaluacion-dictada.txt";
	static final Set<String> TIPOS_DE_TRANSCRIPTOR = Set.of("whisper", "prueba");

	@Bean
	public DictadoAjustes dictadoAjustes(DictadoProperties properties) {
		String tipo = properties.transcriptor().tipo();
		if (properties.enabled() && !TIPOS_DE_TRANSCRIPTOR.contains(tipo)) {
			throw new IllegalStateException("akine.dictado.transcriptor.tipo='" + tipo
					+ "' no es valido: tiene que ser whisper o prueba");
		}
		return new DictadoAjustes(properties.enabled(),
				Duration.ofSeconds(properties.transcriptor().timeoutSegundos()), properties.maxBytes());
	}

	@Bean
	public Transcriptor transcriptorDelDictado(DictadoProperties properties) {
		DictadoProperties.Transcriptor t = properties.transcriptor();
		if ("prueba".equals(t.tipo())) {
			return new TranscriptorDePrueba();
		}
		return new TranscriptorConCupo(new WhisperTranscriptor(t.url(), t.modelo(), t.idioma(), t.vocabulario()),
				t.concurrencia());
	}

	/** Usa el modelo del asistente si esta prendido; si no, la propuesta responde 503. */
	@Bean
	public RepartidorDeEvaluacion repartidorDeEvaluacionDictada(ObjectProvider<ModeloDeLenguaje> modelo,
			DictadoProperties properties) {
		return new RepartidorDeEvaluacion(modelo::getIfAvailable, AsistenteConfig.leerPrompt(PROMPT_EVALUACION),
				Duration.ofSeconds(properties.transcriptor().timeoutSegundos()));
	}
}
