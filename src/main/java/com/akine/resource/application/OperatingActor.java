package com.akine.resource.application;

/**
 * Quien ejecuta una operacion sobre espacios, en la forma que {@code application} entiende.
 *
 * <p>Recibe primitivos porque esta capa no puede conocer HTTP ni la forma del token: traducir
 * "el request en curso" a estos cuatro campos es trabajo de {@code api}, y tenerlo en un solo
 * lugar evita que cada endpoint lo resuelva a su manera.
 *
 * <p><b>Es un primo de {@code organization.application.OperatingActor} y no se puede reusar
 * aquel:</b> vive en la capa {@code application} de otro modulo, que ArchUnit prohibe importar.
 * Promoverlo al {@code spi} de {@code organization} tampoco corresponde —no es un contrato que
 * ese modulo ofrezca, es la forma en que cada modulo describe a su propio actor— y ponerlo en
 * {@code platform} seria la capa global que AGENT.md seccion 4 regla 3 prohibe.
 *
 * <p>Lleva un campo mas que el de {@code organization}: alli el
 * {@code contextOrganizationId} viaja como parametro suelto hacia {@code AuthorizationGuard} y
 * el actor solo carga la sede. Aca van juntos, porque las dos comprobaciones que este modulo
 * hace —"la organizacion pedida es la del contexto" y "la sede pedida es la del contexto"— son
 * la misma clase de control y separarlas invita a que alguien haga una y se olvide la otra.
 *
 * <p><b>Los cuatro valores salen de fuentes distintas, y la diferencia importa.</b>
 * {@code accountId} sale del principal autenticado; {@code platformAdmin} de
 * {@code platform_role} revalidado contra la base en este request; y los dos ids del contexto
 * que {@code TenantContextFilter} ya revalido, <b>nunca</b> de un parametro del cliente.
 * Aceptar la sede desde la URL permitiria que un {@code CONSULTORIO_ADMIN} de la sede A se
 * autorizara sobre la B escribiendo otro numero.
 *
 * @param contextOrganizationId organizacion del contexto validado, o {@code null} si el request
 *                              no trae contexto (caso normal de un {@code PLATFORM_ADMIN})
 * @param consultorioId         sede del contexto validado, o {@code null}. Con {@code null} el
 *                              evaluador solo puede conceder permisos de alcance organizacion o
 *                              global
 */
public record OperatingActor(
		long accountId,
		boolean platformAdmin,
		Long contextOrganizationId,
		Long consultorioId) {
}
