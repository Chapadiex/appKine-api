package com.akine.contracting.spi;

/**
 * Lo que otro modulo necesita saber de un Financiador sin depender de su entidad.
 *
 * <p><b>Es una lectura VIVA, y por eso no sirve para guardar.</b> Refleja el estado del
 * financiador en el momento en que se pidio: si manana lo renombran, este record devuelve el
 * nombre nuevo. Sirve para DECIDIR —¿puedo elegir este financiador hoy?— y nunca para persistir.
 * Lo que se guarda es {@link ReferenciaDeCobertura}, que es una copia congelada.
 *
 * @param operable ciclo de vida administrativo: {@code false} tras la baja logica. Un financiador
 *                 no operable no admite planes nuevos y sus planes no se pueden elegir
 */
public record FinanciadorSnapshot(
		long id,
		long organizationId,
		String codigo,
		String nombre,
		String tipo,
		String cuit,
		boolean operable) {
}
