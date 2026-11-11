package com.akine.knowledge.application;

/**
 * Quien opera, armado por {@code KnowledgeApiActor} desde la sesion y el contexto.
 *
 * @param contextConsultorioId sede del contexto, o {@code null}. La consulta la lleva al permiso:
 *                             sin ella, una membership de sede (CONSULTORIO_ADMIN, o un
 *                             PROFESIONAL vinculado a una sede) no gobierna y recibe 403
 */
public record OperatingActor(
		long accountId,
		boolean platformAdmin,
		Long contextOrganizationId,
		Long contextConsultorioId) {

	/** Sin sede en el contexto. */
	public OperatingActor(long accountId, boolean platformAdmin, Long contextOrganizationId) {
		this(accountId, platformAdmin, contextOrganizationId, null);
	}
}
