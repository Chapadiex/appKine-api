package com.akine.resource.application;

import com.akine.resource.domain.CatalogoAlcance;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Alta de un concepto del catalogo clinico (RF-M06-001, RF-M06-002, RF-M06-003).
 *
 * <p>Un solo comando para los tres conceptos porque el alta es la misma operacion: duenio,
 * clave estable, nombre y vigencia. Lo unico que varia es {@code especialidadId}, obligatorio
 * para una practica e ignorado para los otros dos — y esa asimetria se valida en el servicio,
 * que es quien sabe que tipo esta creando.
 *
 * <p><b>{@code alcance} es un dato del comando y no de la ruta</b>, y es deliberado: la ruta
 * dice QUE se crea, el cuerpo dice PARA QUIEN. Publicar dos arboles de endpoints —uno de
 * plataforma y otro de tenant— habria duplicado el contrato para expresar una sola diferencia,
 * que ademas es la que el servidor tiene que verificar de todos modos contra el rol del actor.
 */
public record CatalogoAltaCommand(

		/** GLOBAL exige rol de plataforma; ORGANIZACION exige contexto y permiso en el tenant. */
		CatalogoAlcance alcance,

		String codigo,

		String name,

		String descripcion,

		/** Obligatorio al crear una practica, ignorado en los otros dos tipos. */
		Long especialidadId,

		/** Solo para una vigencia de nomenclador. */
		BigDecimal valorReferencia,

		/** Si viene {@code null}, el momento del alta: el caso mas frecuente. */
		Instant validFrom,

		/** {@code null} = sin fin previsto, que es lo normal. */
		Instant validUntil) {
}
