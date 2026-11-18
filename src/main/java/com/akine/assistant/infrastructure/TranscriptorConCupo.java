package com.akine.assistant.infrastructure;

import com.akine.assistant.domain.exception.DictadoNoDisponibleException;
import com.akine.assistant.domain.port.Transcriptor;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Limita cuantas transcripciones esperan a Whisper a la vez en esta instancia, como
 * {@link ModeloConCupo} con el modelo del asistente: en CPU, transcribir dos audios en paralelo
 * tarda lo mismo que uno detras del otro, y una fila sin tope agota los hilos del servidor.
 */
public class TranscriptorConCupo implements Transcriptor {

	private final Transcriptor transcriptor;
	private final Semaphore cupos;

	public TranscriptorConCupo(Transcriptor transcriptor, int concurrencia) {
		if (concurrencia < 1) {
			throw new IllegalArgumentException("La concurrencia del transcriptor tiene que ser al menos 1");
		}
		this.transcriptor = Objects.requireNonNull(transcriptor, "transcriptor");
		this.cupos = new Semaphore(concurrencia, true);
	}

	@Override
	public String transcribir(byte[] audio, String tipoContenido, Duration plazo) {
		long inicio = System.nanoTime();
		boolean conCupo;
		try {
			conCupo = cupos.tryAcquire(plazo.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DictadoNoDisponibleException(e);
		}
		if (!conCupo) {
			throw new DictadoNoDisponibleException();
		}
		try {
			Duration restante = plazo.minusNanos(System.nanoTime() - inicio);
			if (restante.isNegative() || restante.isZero()) {
				throw new DictadoNoDisponibleException();
			}
			return transcriptor.transcribir(audio, tipoContenido, restante);
		}
		finally {
			cupos.release();
		}
	}
}
