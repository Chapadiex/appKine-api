package com.akine.offering.application;

/**
 * Quien ejecuta una operacion de {@code offering}, en la forma que {@code application} entiende.
 *
 * <p>Recibe primitivos porque esta capa no puede conocer HTTP ni la forma del token: traducir
 * "el request en curso" a estos cuatro campos es trabajo de {@code api} (Tarea 6), y tenerlo en
 * un solo lugar evita que cada endpoint lo resuelva a su manera.
 *
 * <p><b>Es un primo de {@code resource.application.OperatingActor} y de
 * {@code organization.application.OperatingActor}, y no se puede reusar ninguno de los dos:</b>
 * viven en la capa {@code application} de otro modulo, que ArchUnit prohibe importar
 * ({@code modulos_solo_se_alcanzan_por_su_spi}). Promoverlo al {@code spi} de alguno tampoco
 * corresponde —no es un contrato que ese modulo ofrezca, es la forma en que cada modulo describe
 * a su propio actor— y ponerlo en {@code platform} seria la capa global que AGENT.md seccion 4
 * regla 3 prohibe. La duplicacion es el precio explicito de la regla de modulos.
 *
 * <p><b>Los cuatro valores salen de fuentes distintas, y la diferencia importa.</b>
 * {@code accountId} sale del principal autenticado; {@code platformAdmin} de
 * {@code platform_role} revalidado contra la base en este request; y los dos ids del contexto de
 * los que {@code TenantContextFilter} ya revalido, <b>nunca</b> de un parametro del cliente.
 * Aceptar la sede desde la URL permitiria que un {@code CONSULTORIO_ADMIN} de la sede A se
 * autorizara sobre la B escribiendo otro numero.
 *
 * <p>{@link #contextOrganizationId} y {@link #consultorioId} <b>no los usa
 * {@code ServicioService}</b> y no es un descuido: un {@code Servicio} es global y no pertenece a
 * ninguna sede, asi que no hay nada que acotar por contexto. Los lleva este record porque
 * {@code OfertaService} (Tarea 5) comparte el mismo actor y ahi si son la base de toda la
 * autorizacion.
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
