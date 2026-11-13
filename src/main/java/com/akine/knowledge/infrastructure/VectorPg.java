package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.Vectores;

/**
 * El texto con el que pgvector recibe un vector: {@code [0.1,0.2,...]}. Se manda como parametro y
 * se convierte con {@code CAST(? AS vector)}.
 *
 * <p>{@link Float#toString} da la representacion mas corta que vuelve exactamente al mismo
 * {@code float}, y pgvector guarda float32: el vector que se lee es el mismo que se escribio.
 */
final class VectorPg {

	private VectorPg() {
	}

	static String literal(float[] vector) {
		StringBuilder texto = new StringBuilder(vector.length * 12).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				texto.append(',');
			}
			texto.append(Float.toString(vector[i]));
		}
		return texto.append(']').toString();
	}

	/** Bytes float32 little-endian del dominio, o {@code null}. */
	static String literal(byte[] bytes) {
		return bytes == null ? null : literal(Vectores.deBytes(bytes));
	}
}
