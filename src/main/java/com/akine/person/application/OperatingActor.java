package com.akine.person.application;

/**
 * Quien ejecuta una operacion de {@code person}, en la forma que {@code application} entiende.
 *
 * <p>Recibe primitivos porque esta capa no puede conocer HTTP ni la forma del token: traducir "el
 * request en curso" a estos cuatro campos es trabajo de {@code api}.
 *
 * <p><b>Es un primo de los {@code OperatingActor} de {@code resource}, {@code offering} y
 * {@code organization}, y no se puede reusar ninguno:</b> viven en la capa {@code application} de
 * otro modulo, que ArchUnit prohibe importar. La duplicacion es el precio explicito de la regla
 * de modulos, y esta discutida en la cabecera de {@code offering.application.OperatingActor}.
 *
 * <p><b>Los cuatro valores salen de fuentes distintas.</b> {@code accountId} del principal
 * autenticado; {@code platformAdmin} de {@code platform_role} revalidado contra la base en este
 * request; y los dos ids del contexto que {@code TenantContextFilter} ya revalido, <b>nunca</b>
 * de un parametro del cliente. Aceptar la organizacion desde la URL permitiria leer el padron de
 * otro centro escribiendo otro numero.
 *
 * @param contextOrganizationId organizacion del contexto validado, o {@code null} si el request
 *                              no trae contexto. Sin ella no se lee ni se muta nada de este
 *                              modulo: no existe la persona global
 * @param consultorioId         sede del contexto validado, o {@code null}. Las MUTACIONES la
 *                              exigen aunque la persona no pertenezca a ninguna sede — el motivo
 *                              esta en {@code PermissionCodes.PACIENTE_MANAGE} y no es obvio
 */
public record OperatingActor(
		long accountId,
		boolean platformAdmin,
		Long contextOrganizationId,
		Long consultorioId) {
}
