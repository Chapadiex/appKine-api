package com.akine.platform.infrastructure.modelo.agente;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion del agente del asistente ({@code akine.asistente.agente.*}).
 *
 * @param proveedor          {@code claude}, {@code compatible} o {@code prueba}
 * @param alternativo        proveedor al que se cae si el principal no responde; vacio, ninguno
 * @param effort             {@code output_config.effort} de Claude: low, medium, high, xhigh o max
 * @param maxVueltas         tope de turnos del modelo por pregunta
 * @param plazoTotalSegundos tope de tiempo de toda la pregunta
 */
@ConfigurationProperties(prefix = "akine.asistente.agente")
public record AgenteProperties(
		@DefaultValue("claude") String proveedor,
		@DefaultValue("") String alternativo,
		@DefaultValue("medium") String effort,
		@DefaultValue("8") int maxVueltas,
		@DefaultValue("30") int plazoTotalSegundos,
		@DefaultValue Claude claude,
		@DefaultValue Compatible compatible) {

	public AgenteProperties {
		alternativo = alternativo == null ? "" : alternativo;
		claude = claude == null ? new Claude(null, null, 16000) : claude;
		compatible = compatible == null ? new Compatible(null, null, null) : compatible;
	}

	/** API de Anthropic. La key sale de {@code AKINE_ANTHROPIC_API_KEY}; vacia, el proveedor no responde. */
	public record Claude(
			@DefaultValue("") String apiKey,
			@DefaultValue("claude-opus-5-5") String modelo,
			@DefaultValue("16000") long maxTokens) {

		public Claude {
			apiKey = apiKey == null ? "" : apiKey.strip();
			modelo = modelo == null || modelo.isBlank() ? "claude-opus-5-5" : modelo;
		}

		/** Sin la key: el record termina en logs y mensajes de arranque. */
		@Override
		public String toString() {
			return "Claude[modelo=" + modelo + ", maxTokens=" + maxTokens + ", apiKey="
					+ (apiKey.isEmpty() ? "(vacia)" : "***") + "]";
		}
	}

	/** Servidor con la API de chat compatible con OpenAI (lo usa el proveedor {@code compatible}). */
	public record Compatible(
			@DefaultValue("") String url,
			@DefaultValue("") String modelo,
			@DefaultValue("") String apiKey) {

		public Compatible {
			url = url == null ? "" : url;
			modelo = modelo == null ? "" : modelo;
			apiKey = apiKey == null ? "" : apiKey.strip();
		}

		@Override
		public String toString() {
			return "Compatible[url=" + url + ", modelo=" + modelo + ", apiKey="
					+ (apiKey.isEmpty() ? "(vacia)" : "***") + "]";
		}
	}
}
