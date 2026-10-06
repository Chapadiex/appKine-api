package com.akine.notification.application;

/** Quien opera, armado por {@code NotificationApiActor} desde la sesion y el contexto. */
public record OperatingActor(
		long accountId,
		boolean platformAdmin,
		Long contextOrganizationId) {
}
