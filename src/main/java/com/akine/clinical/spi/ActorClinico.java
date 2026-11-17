package com.akine.clinical.spi;

/**
 * Quien pide leer contenido clinico desde otro modulo (M31).
 *
 * <p>Es el mismo cuarteto que el {@code OperatingActor} de cada modulo: cada consumidor traduce el
 * suyo a este, y {@code clinical} lo traduce al propio. Sin organizacion ni sede validadas la
 * lectura se rechaza, igual que en la Historia Clinica.
 */
public record ActorClinico(
		long accountId,
		boolean platformAdmin,
		Long organizationId,
		Long consultorioId) {
}
