package com.akine.knowledge.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Operaciones sobre vectores de embeddings. Solo metodos estaticos: el dominio no guarda
 * {@code float} como campo; el vector vive como {@code float[]} en transito y como bytes al
 * guardarse (float32 little-endian, 4 bytes por componente).
 */
public final class Vectores {

	private Vectores() {
	}

	public static byte[] aBytes(float[] vector) {
		ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		buffer.asFloatBuffer().put(vector);
		return buffer.array();
	}

	public static float[] deBytes(byte[] bytes) {
		if (bytes.length % Float.BYTES != 0) {
			throw new IllegalArgumentException("Un vector float32 ocupa un multiplo de 4 bytes");
		}
		float[] vector = new float[bytes.length / Float.BYTES];
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
		return vector;
	}

	/** Copia con norma 1. Un vector nulo (todo ceros) vuelve igual. */
	public static float[] normalizar(float[] vector) {
		double suma = 0;
		for (float v : vector) {
			suma += (double) v * v;
		}
		float[] copia = vector.clone();
		if (suma == 0) {
			return copia;
		}
		double norma = Math.sqrt(suma);
		for (int i = 0; i < copia.length; i++) {
			copia[i] = (float) (copia[i] / norma);
		}
		return copia;
	}

	/** Similitud coseno de dos vectores ya normalizados: el producto punto. */
	public static float coseno(float[] a, float[] b) {
		if (a.length != b.length) {
			throw new IllegalArgumentException("Los vectores tienen dimensiones distintas");
		}
		double punto = 0;
		for (int i = 0; i < a.length; i++) {
			punto += (double) a[i] * b[i];
		}
		return (float) punto;
	}
}
