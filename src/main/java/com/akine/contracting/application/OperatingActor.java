package com.akine.contracting.application;

/**
 * Quien ejecuta una operacion de {@code contracting}, en la forma que {@code application}
 * entiende.
 *
 * <p>Recibe primitivos porque esta capa no puede conocer HTTP ni la forma del token: traducir "el
 * request en curso" a estos cuatro campos es trabajo de {@code api}.
 *
 * <p><b>Es un primo de los {@code OperatingActor} de {@code person}, {@code offering},
 * {@code resource} y {@code organization}, y no se puede reusar ninguno:</b> viven en la capa
 * {@code application} de otro modulo, que ArchUnit prohibe importar. Promoverlo a un {@code spi}
 * tampoco corresponde —no es un contrato que ese modulo ofrezca— y ponerlo en {@code platform}
 * seria la capa global que AGENT.md §4 regla 3 prohibe. La duplicacion es el precio explicito de
 * la regla de modulos.
 *
 * <p>Los cuatro valores salen de fuentes distintas y la diferencia importa: {@code accountId} del
 * principal autenticado, {@code platformAdmin} de {@code platform_role} revalidado contra la base
 * en este request, y los dos ids del contexto que {@code TenantContextFilter} ya revalido —
 * <b>nunca</b> de un parametro del cliente.
 *
 * @param contextOrganizationId organizacion del contexto validado, o {@code null} si el request no
 *                              trae contexto. {@code null} no es "cualquiera": es 403
 * @param consultorioId         sede del contexto validado, o {@code null}. Sin ella el evaluador
 *                              no puede conceder un permiso de alcance CONSULTORIO, y por eso las
 *                              mutaciones de este modulo la exigen aunque el financiador sea de la
 *                              organizacion. Ver {@code PermissionCodes}
 */
public record OperatingActor(
		long accountId,
		boolean platformAdmin,
		Long contextOrganizationId,
		Long consultorioId) {
}
