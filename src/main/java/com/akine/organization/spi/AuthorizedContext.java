package com.akine.organization.spi;

/**
 * Un contexto de trabajo al que una cuenta puede entrar: Organizacion + Consultorio
 * (ADR-0009).
 *
 * <p>Lleva los nombres ademas de los ids porque su consumidor es la pantalla de seleccion de
 * contexto: sin ellos, el frontend tendria que pedir una organizacion y un consultorio por
 * fila para poder mostrar una lista.
 *
 * <p>Es una FOTO del momento en que se consulto, no una autorizacion permanente. Que un
 * contexto aparezca aca no habilita nada por si solo: cada request revalida contra la base
 * (RN-M01-003), sin cache y sin ventana de gracia.
 */
public record AuthorizedContext(
		long organizationId,
		String organizationName,
		long consultorioId,
		String consultorioName) {
}
