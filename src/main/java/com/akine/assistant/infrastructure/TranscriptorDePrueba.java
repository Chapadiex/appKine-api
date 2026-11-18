package com.akine.assistant.infrastructure;

import com.akine.assistant.domain.port.Transcriptor;

import java.time.Duration;

/** Determinista, para tests y desarrollo sin Whisper: siempre devuelve el mismo texto. */
public class TranscriptorDePrueba implements Transcriptor {

	public static final String TEXTO = "Texto dictado de prueba.";

	@Override
	public String transcribir(byte[] audio, String tipoContenido, Duration plazo) {
		return TEXTO;
	}
}
