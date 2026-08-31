package com.akine.encounter.application;

/**
 * Quien atiende, con el contexto que el filtro de tenant ya resolvio.
 *
 * <p>Cada modulo declara el suyo: un record compartido en {@code platform} obligaria a todos los
 * modulos a depender de el, y {@code AGENT.md} §4 lo prohibe.
 */
public record OperatingActor(
		long accountId,
		boolean platformAdmin,
		Long contextOrganizationId,
		Long consultorioId) {
}
