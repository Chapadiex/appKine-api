package com.akine.knowledge.application;

/** Quien opera, armado por {@code KnowledgeApiActor} desde la sesion y el contexto. */
public record OperatingActor(
		long accountId,
		boolean platformAdmin,
		Long contextOrganizationId) {
}
