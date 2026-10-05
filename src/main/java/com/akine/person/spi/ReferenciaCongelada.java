package com.akine.person.spi;

/** Financiador y plan tal como quedaron congelados en la cobertura el dia que se firmo. */
public record ReferenciaCongelada(
		long financiadorId, String financiadorNombre, long planId, String planNombre) {
}
