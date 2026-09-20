package com.akine.resource.application;

import java.math.BigDecimal;

/**
 * Edicion parcial de una definicion de medida: cada {@code null} deja el campo como estaba.
 *
 * <p>Semantica de PATCH y no de PUT, igual que {@code CatalogoEdicionCommand}.
 *
 * <p><b>Ni el codigo, ni el alcance, ni el tipo estan aca.</b> Los tres son inmutables por lo que
 * dice el javadoc de la entidad: el codigo porque es lo que los historicos guardan, el alcance
 * porque promover un concepto a global cambiaria el significado de todo lo que lo referencia, y
 * el tipo porque dejaria mediciones cuyo valor vive en una columna que el tipo nuevo no admite.
 *
 * <p>{@code expectedVersion} no es opcional: dos pantallas de administracion sobre la misma
 * definicion son el caso normal, y sin ella la segunda pisa a la primera en silencio.
 */
public record MedicionDefinicionEdicionCommand(

		String name,

		String descripcion,

		String unidad,

		BigDecimal minimo,

		BigDecimal maximo,

		/**
		 * "Saca el rango" contra "no toques el rango": dos intenciones distintas que un par de
		 * nulables no puede expresar. Misma forma que {@code clearValidUntil} en M06.
		 */
		boolean clearRango,

		long expectedVersion) {
}
