package com.akine.resource.application;

import com.akine.resource.domain.CatalogoAlcance;
import com.akine.resource.spi.MedicionTipo;

import java.math.BigDecimal;

/**
 * Alta de una definicion de medida (RF-M14-004).
 *
 * <p>{@code alcance} viaja en el comando y no en la ruta, igual que en el resto de M06: la ruta
 * dice QUE se crea, el comando dice PARA QUIEN. Dos arboles de endpoints —uno de plataforma y
 * otro de tenant— habrian duplicado el contrato entero para expresar una diferencia que el
 * servidor tiene que verificar contra el rol del actor de todos modos.
 *
 * <p>{@code null} en {@code alcance} significa {@link CatalogoAlcance#ORGANIZACION}: el caso
 * frecuente es un centro cargando su propio test, y el global exige rol de plataforma.
 */
public record MedicionDefinicionAltaCommand(

		CatalogoAlcance alcance,

		String codigo,

		String name,

		String descripcion,

		MedicionTipo tipo,

		/** Obligatoria en NUMERICO y ESCALA, prohibida en TEXTO y BOOLEANO. */
		String unidad,

		/** {@code null} = sin piso. Solo en los tipos numericos. */
		BigDecimal minimo,

		/** {@code null} = sin techo. INCLUSIVE cuando esta. Solo en los tipos numericos. */
		BigDecimal maximo) {
}
