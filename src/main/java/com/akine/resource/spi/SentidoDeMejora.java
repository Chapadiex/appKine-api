package com.akine.resource.spi;

/**
 * Hacia donde se mueve el valor de una medida cuando el paciente mejora (RF-M31-003).
 *
 * <p>Es opcional: una definicion sin sentido declarado se muestra y se compara, pero nadie la
 * califica como mejora o empeoramiento (RN-M31-009). El rango de movimiento mejora cuando
 * {@link #SUBE}; una escala de dolor, cuando {@link #BAJA}.
 *
 * <p>No se copia a {@code sesion_medicion}: cambiarlo reinterpreta la serie entera, que es lo que
 * se quiere de un dato que dice como leer los valores y no que valor se tomo.
 */
public enum SentidoDeMejora {
	SUBE,
	BAJA
}
