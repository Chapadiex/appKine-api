package com.akine.contracting.application;

import com.akine.contracting.domain.TipoFinanciador;

/**
 * Alta de un financiador en el catalogo de la organizacion (RF-M15-001).
 *
 * <p><b>No lleva {@code Idempotency-Key}</b>, y es deliberado: un financiador no consume cupo de
 * ningun plan, asi que lo unico que un reintento podria producir es una fila duplicada, y contra
 * eso {@code uk_financiador_codigo_vigente} es una garantia mas fuerte que una clave —no depende
 * de que el cliente la mande ni de que la reuse bien—. El reintento responde 409 y no crea nada.
 * La contrapartida: despues de un timeout de red hay que releer el listado para saber si el alta
 * original entro. Mismo criterio, y mismo texto, que el alta del catalogo clinico en 02.05 y la
 * de servicios en 02.06.
 *
 * @param codigo clave estable dentro de la organizacion. <b>Inmutable despues del alta</b>: es la
 *               referencia por la que las coberturas y convenios lo nombran. Renombrar es cambiar
 *               {@link #nombre}
 * @param cuit   se normaliza a 11 digitos antes de guardar. Un valor que no llega a 11 digitos se
 *               rechaza con 400, no se guarda como venga
 */
public record FinanciadorAltaCommand(
		String codigo,
		String nombre,
		TipoFinanciador tipo,
		String cuit,
		String emailContacto,
		String telefonoContacto,
		String observaciones) {
}
