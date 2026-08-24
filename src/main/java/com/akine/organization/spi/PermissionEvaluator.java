package com.akine.organization.spi;

import java.time.Instant;
import java.util.Set;

/**
 * Evaluador de permisos del sistema. Es la autoridad: ningun modulo decide un permiso por su
 * cuenta ni interpreta el {@code roleCode} del token.
 *
 * <h2>El algoritmo</h2>
 *
 * <p>Materializa literalmente la formula de la matriz seccion 3:
 *
 * <pre>
 * permisos(actor, org, consultorio, t) =
 *       base(rol de la membership que cubre el consultorio, t)   // tabla estatica, en codigo
 *     union grants activos de esa membership en t                // membership_grant
 *     union base de plataforma, si hay support_access vigente     // platform_role + support_access
 *     interseccion alcance(membership)
 *     interseccion habilitacion vigente(t)                        // vacio en F1
 * </pre>
 *
 * <h2>Dos cosas que no se pueden cambiar sin romper garantias</h2>
 *
 * <ol>
 *   <li><b>Sin cache.</b> Ni aca ni en {@code MembershipDirectory}. La ventana de revocacion es
 *       cero: una membership revocada deja de habilitar en el request siguiente. Si algun dia
 *       el SLO exige cache, es un ADR nuevo, no una optimizacion silenciosa.</li>
 *   <li><b>Se invoca DENTRO de la transaccion de negocio</b>, no en el filtro. El filtro
 *       resolvio el contexto en su propia transaccion de solo lectura; evaluar ahi dejaria una
 *       ventana del tamaño de un request entre "tenia permiso" y "commiteo". Evaluando adentro,
 *       la ventana es la duracion de una transaccion, y para las mutaciones que ademas tocan
 *       memberships —que evaluan DESPUES de tomar el bloqueo del tenant— es cero.</li>
 * </ol>
 */
public interface PermissionEvaluator {

	/**
	 * Decide un permiso concreto.
	 *
	 * <p>Nunca lanza por un rechazo: devuelve la decision con su motivo. Quien quiera la
	 * version que corta el flujo usa {@link PermissionGuard}.
	 */
	PermissionDecision evaluate(PermissionQuery query);

	/**
	 * Permisos efectivos del actor en un contexto, como codigos textuales.
	 *
	 * <p>Es el insumo del selector de acciones del frontend: el cliente oculta o deshabilita
	 * botones con esto y <b>nunca autoriza con esto</b>. La autorizacion es server-side y ocurre
	 * en cada operacion (RN-M02-001).
	 *
	 * @param consultorioId sede del contexto, o {@code null} para el alcance organizacion
	 */
	Set<String> effectivePermissions(long accountId, long organizationId, Long consultorioId);

	/**
	 * Indica si la cuenta tiene el rol de plataforma vigente en ese instante.
	 *
	 * <p>Sale de {@code platform_role}, revalidado contra la base. <b>No</b> del claim
	 * {@code rol} del token: autorizar por ese claim convierte la ventana de revocacion en el
	 * TTL del token, que es exactamente lo que {@code AccessTokenClaims} documenta que no hay
	 * que hacer.
	 */
	boolean isPlatformAdmin(long accountId, Instant at);

	/**
	 * Indica si la cuenta tiene un acceso de soporte vigente sobre esa organizacion.
	 *
	 * <p>Tener {@code PLATFORM_ADMIN} abre las rutas de plataforma; entrar a los datos de un
	 * tenant exige ademas esto (ADR-0020, matriz seccion 7).
	 */
	boolean hasSupportAccess(long accountId, long organizationId, Instant at);
}
