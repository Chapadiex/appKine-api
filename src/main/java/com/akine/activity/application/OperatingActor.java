package com.akine.activity.application;

/**
 * Quien opera, y en que contexto. Es el equivalente de {@code scheduling.application.OperatingActor}
 * para este modulo.
 *
 * <p><b>Cada modulo tiene el suyo</b> y no se comparte uno global: compartirlo obligaria a que
 * viviera en {@code platform}, y cualquier campo que un modulo necesitara apareceria en el
 * actor de todos los demas.
 */
public record OperatingActor(
		long accountId,
		boolean platformAdmin,
		Long contextOrganizationId,
		Long contextConsultorioId) {
}
