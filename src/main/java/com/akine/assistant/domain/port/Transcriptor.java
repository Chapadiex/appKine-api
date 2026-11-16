package com.akine.assistant.domain.port;

import java.time.Duration;

/**
 * Pasa audio a texto. La implementacion real es un servidor Whisper local; nunca un servicio de
 * terceros, porque la voz de una atencion es dato de salud.
 */
public interface Transcriptor {

	/**
	 * @param audio        los bytes, tal como los grabo el navegador
	 * @param tipoContenido el {@code Content-Type} declarado (p. ej. {@code audio/webm})
	 * @param plazo        cuanto esperar como maximo
	 * @return el texto transcripto, sin espacios sobrantes; vacio si no se entendio nada
	 * @throws com.akine.assistant.domain.exception.DictadoNoDisponibleException si no responde
	 */
	String transcribir(byte[] audio, String tipoContenido, Duration plazo);
}
