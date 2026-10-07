package com.akine.offering.application;

import java.time.Instant;
import java.util.List;

/**
 * Que practicas puede prestar una Oferta y cual es su principal (A-9, DP-11).
 *
 * @param ofertaVersion      version de la OFERTA para el proximo reemplazo, con el mismo contrato que
 *                           {@link HabilitacionesView#ofertaVersion()}: la vigente en una lectura, y
 *                           {@code leida + 1} en la respuesta de un reemplazo
 * @param practicaPrincipalId la principal vigente, o {@code null} si la oferta no declara practicas
 * @param practicas          filas activas e inactivas, en orden de alta
 */
public record PracticasDeOfertaView(
		long ofertaId,
		long ofertaVersion,
		Long practicaPrincipalId,
		List<PracticaDeOfertaView> practicas) {

	/**
	 * Una practica de la oferta.
	 *
	 * @param codigo            codigo de la practica en el catalogo, resuelto al leer. {@code null}
	 *                          si el catalogo ya no la resuelve para este tenant
	 * @param vigenteEnCatalogo si la practica se puede elegir HOY en M06. Puede ser {@code false} con
	 *                          la fila activa: la practica se dio de baja en el catalogo y la oferta
	 *                          la conserva (RN-M06-002)
	 */
	public record PracticaDeOfertaView(
			long id,
			long practicaId,
			String codigo,
			String nombre,
			boolean principal,
			String estado,
			boolean vigenteEnCatalogo,
			Instant deletedAt,
			String deactivationReason,
			long version) {
	}
}
