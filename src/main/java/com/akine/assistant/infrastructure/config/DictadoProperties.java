package com.akine.assistant.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion del dictado por voz ({@code akine.dictado.*}). Apagado por defecto: toda llamada
 * responde 409 {@code dictado-deshabilitado}.
 *
 * <p>La propuesta de evaluacion usa el modelo del asistente: necesita ademas
 * {@code akine.asistente.enabled}; sin el, responde 503.
 *
 * @param maxBytes tope del audio; tiene que quedar por debajo de
 *                 {@code spring.servlet.multipart.max-file-size}, o el rechazo es un 500 generico
 */
@ConfigurationProperties(prefix = "akine.dictado")
public record DictadoProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("10485760") long maxBytes,
		@DefaultValue Transcriptor transcriptor) {

	/**
	 * {@code tipo}: {@code whisper} o {@code prueba}. {@code timeoutSegundos}: la primera llamada
	 * con el modelo descargado suma la carga en frio; el default cubre un minuto de audio en CPU.
	 */
	public record Transcriptor(
			@DefaultValue("whisper") String tipo,
			@DefaultValue("http://localhost:8000") String url,
			@DefaultValue("Systran/faster-whisper-small") String modelo,
			@DefaultValue("es") String idioma,
			@DefaultValue("90") int timeoutSegundos,
			@DefaultValue("2") int concurrencia,
			@DefaultValue("Sesión de kinesiología. Dolor EVA, lumbalgia, cervicalgia, dorsalgia, "
					+ "isquiotibiales, cuádriceps, manguito rotador, esguince, tendinopatía, "
					+ "movilidad articular, fortalecimiento, elongación, crioterapia, "
					+ "electroestimulación, TENS, ultrasonido, magnetoterapia.") String vocabulario) {
	}
}
