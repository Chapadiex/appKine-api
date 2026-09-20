package com.akine.reporting.application;

/**
 * Quien pide el reporte, con el contexto que el filtro de tenant ya resolvio.
 *
 * <p>Cada modulo declara el suyo a proposito: un record compartido en {@code platform} obligaria a
 * todos los modulos a depender de el, y {@code AGENT.md} §4 lo prohibe. Ver la misma decision en
 * {@code billing.application.OperatingActor}.
 *
 * <p><b>{@code contextOrganizationId} es de donde sale el tenant del reporte.</b> No hay ningun
 * parametro de la API que lo lleve, y eso es la primera de las cuatro defensas de aislamiento:
 * no se puede pedir el reporte de otra organizacion porque el id no viaja en la request.
 */
public record OperatingActor(
		long accountId,
		boolean platformAdmin,
		Long contextOrganizationId,
		Long consultorioId) {
}
