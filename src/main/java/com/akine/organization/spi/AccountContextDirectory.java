package com.akine.organization.spi;

import java.util.List;
import java.util.Optional;

/**
 * Contextos de trabajo de una cuenta y seleccion del contexto activo (RF-M01-005, ADR-0009).
 *
 * <p>Es el contrato orientado al flujo de login: "a donde puede entrar esta persona" y "donde
 * quiere entrar ahora". Lo consumen {@code identity} (01.02, para emitir el token acotado al
 * contexto) y permisos (01.03).
 *
 * <p><b>Por que la seleccion se expone por {@code spi} y no como endpoint (D-6).</b> Cambiar
 * de contexto tiene que renovar el token, y el token es de {@code identity}. Si
 * {@code organization} publicara {@code PUT /me/active-context}, el frontend necesitaria dos
 * round-trips —persistir el puntero y despues pedir el token— y entre uno y otro quedaria una
 * ventana con el puntero ya cambiado y el token viejo. Ademas, cuando 01.02 publique
 * {@code POST /api/v1/auth/context}, el endpoint de 01.01 quedaria redundante y habria que
 * retirarlo: un cambio incompatible de contrato a las pocas semanas de publicarlo. Por eso
 * 01.01 publica solo {@code GET /api/v1/me/contexts} y deja {@link #selectContext} aca.
 *
 * <p><b>Nada de lo que devuelve este contrato es una autorizacion.</b> El contexto efectivo de
 * un request es el de ESE request, revalidado contra la base (RN-M01-003). Estos metodos
 * responden preguntas de navegacion, no de permisos.
 */
public interface AccountContextDirectory {

	/**
	 * Contextos a los que la cuenta puede entrar ahora.
	 *
	 * <p>Es el producto de las memberships VIGENTES por los consultorios ACTIVOS que cada una
	 * alcanza: una membership con {@code consultorio_id = null} alcanza toda la organizacion y
	 * produce una fila por sede. Se excluyen las organizaciones dadas de baja y las que tienen
	 * la suscripcion CANCELADA: ofrecer un contexto en el que la proxima operacion va a fallar
	 * es peor que no ofrecerlo.
	 *
	 * <p>Cross-tenant por naturaleza: es la unica consulta del modulo que no filtra por
	 * organizacion, porque la pregunta —"en que organizaciones trabaja esta persona"— no
	 * ocurre dentro de un tenant.
	 */
	List<AuthorizedContext> authorizedContexts(long accountId);

	/** Indica si la cuenta tiene una membership vigente en la organizacion. */
	boolean hasActiveMembership(long accountId, long organizationId);

	/**
	 * Revalida un contexto completo contra la base.
	 *
	 * <p>Responde {@code false} tanto si el contexto no existe como si es de otro tenant: la
	 * distincion filtraria la existencia de organizaciones ajenas.
	 */
	boolean isContextAuthorized(long accountId, long organizationId, long consultorioId);

	/**
	 * Ultimo contexto seleccionado, <b>revalidado</b> (D-7).
	 *
	 * <p>Devuelve {@code Optional.empty()} si el puntero dejo de ser valido —membership
	 * vencida o revocada, consultorio dado de baja, organizacion de baja, suscripcion
	 * cancelada—. Un puntero obsoleto no se devuelve nunca: el consumidor lo usaria para
	 * preseleccionar y el usuario entraria a una pantalla que falla en la primera operacion.
	 * Sin seleccion previa y con seleccion invalida son el mismo caso.
	 */
	Optional<ActiveContext> activeContext(long accountId);

	/**
	 * Fija el contexto activo de la cuenta y devuelve el aceptado.
	 *
	 * <p>Valida primero, persiste despues: si el contexto no esta autorizado lanza y el
	 * puntero queda como estaba. Es idempotente por semantica —reseleccionar lo mismo no
	 * duplica filas— y solo audita {@code CONTEXT_SELECTED} cuando el puntero efectivamente
	 * cambio: un PUT repetido no puede generar ruido en el historial.
	 *
	 * @throws com.akine.organization.domain.exception.ContextNotAuthorizedException si la
	 *         cuenta no puede entrar a ese contexto
	 */
	ActiveContext selectContext(long accountId, long organizationId, long consultorioId);

	/**
	 * Membership de una cuenta en una organizacion, vigente o no.
	 *
	 * <p>Devuelve tambien las no vigentes para que 01.03 pueda explicar por que un acceso
	 * fallo; quien decide sobre permisos usa {@link MembershipSnapshot#validAt}.
	 */
	Optional<MembershipSnapshot> membership(long accountId, long organizationId);
}
