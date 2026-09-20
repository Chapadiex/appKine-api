package com.akine.billing.domain;

/**
 * En que se fue la plata.
 *
 * <p><b>Enum y no catalogo configurable</b>, a proposito. Un catalogo por organizacion es otra
 * tabla, otro CRUD y otra pantalla, y lo que M22 necesita hoy es poder filtrar y totalizar
 * (RF-M22-004). Si M23 pide categorias propias del centro para un reporte, se agrega entonces con
 * la migracion correspondiente; al reves —nacer configurable y descubrir que nadie lo configura—
 * se paga en mantenimiento para siempre.
 *
 * <p>{@link #OTRO} existe por lo mismo que en {@link MedioDePago}: sin el, el operador elige la
 * categoria equivocada y el total por categoria deja de significar algo.
 */
public enum CategoriaEgreso {

	/** Lo que M22 existe para pagar: honorarios de un profesional del centro. */
	HONORARIOS_PROFESIONALES,
	SUELDOS,
	ALQUILER,
	SERVICIOS,
	INSUMOS,
	IMPUESTOS,
	MANTENIMIENTO,
	OTRO
}
