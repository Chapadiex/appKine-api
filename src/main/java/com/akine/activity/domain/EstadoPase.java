package com.akine.activity.domain;

/**
 * {@code ck_pase_estado} (V64). {@code ACTIVO} al vender; {@code AGOTADO} lo escribe el UPDATE
 * condicional que consume el ultimo credito, y una devolucion lo vuelve a {@code ACTIVO}.
 * {@code VENCIDO} y {@code ANULADO} no tienen escritor todavia (job de vencimiento y anulacion,
 * 08.07): el vencimiento se evalua por fecha al consumir y al leer.
 */
public enum EstadoPase {
	ACTIVO,
	AGOTADO,
	VENCIDO,
	ANULADO
}
