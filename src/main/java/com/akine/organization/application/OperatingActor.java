package com.akine.organization.application;

/**
 * Quien ejecuta una operacion administrativa, en la forma que {@code application} entiende.
 *
 * <p>Recibe primitivos porque esta capa no puede conocer HTTP ni la forma del token: traducir
 * "el request en curso" a estos tres campos es trabajo de {@code api}, y tenerlo en un solo
 * lugar evita que cada controller lo resuelva a su manera.
 *
 * <p><b>Los tres valores salen de fuentes distintas, y la diferencia importa.</b>
 * {@code accountId} sale del principal autenticado; {@code platformAdmin} sale de
 * {@code platform_role} revalidado contra la base en este request —desde AKINE-01.03 ya no del
 * claim {@code rol} del token—; y {@code consultorioId} sale del contexto que
 * {@code TenantContextFilter} ya revalido, <b>nunca</b> de un parametro del cliente.
 *
 * <p>{@code consultorioId} puede ser {@code null}: un {@code PLATFORM_ADMIN} opera sin contexto
 * de tenant, y hay operaciones de alcance organizacion que no dependen de una sede. Con
 * {@code null}, el evaluador solo puede conceder permisos de alcance organizacion o global.
 *
 * @param accountId     cuenta autenticada que opera
 * @param platformAdmin administra la plataforma, por encima de cualquier tenant
 * @param consultorioId sede del contexto validado, o {@code null}
 */
public record OperatingActor(long accountId, boolean platformAdmin, Long consultorioId) {
}
