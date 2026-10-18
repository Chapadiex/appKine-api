package com.akine.organization.spi;

import java.time.Instant;

/**
 * Lo que otro modulo hace cuando un colaborador pierde alcance en la organizacion (DP-40, U-43).
 *
 * <p>Es la contracara de {@link ColaboradorDesvinculacionProbe}: la sonda <b>informa</b> antes de
 * desvincular; esto <b>reacciona</b> despues, dentro de la misma transaccion. El primer consumidor es
 * {@code activity}: un abono cuya renovacion automatica autorizo alguien que ya no puede vender deja
 * de renovarse solo, porque cada periodo nuevo se vende a nombre de quien la autorizo.
 *
 * <h2>Cuando se llama</h2>
 *
 * <p>{@code MembershipService} la invoca despues de guardar el cambio —con la membership ya
 * revocada, el rol ya cambiado o el permiso adicional ya dado de baja—, de modo que quien reaccione
 * y vuelva a evaluar permisos con {@link PermissionEvaluator} vea el estado nuevo. Se llama en la
 * revocacion, en el cambio de rol o de sede, y en la baja de un permiso adicional: en los cuatro la
 * cuenta puede dejar de tener un permiso que tenia. <b>No</b> se llama al suspender: la suspension
 * es temporal, y el job que renueva vuelve a mirar el permiso antes de cada venta.
 *
 * <h2>Contrato para quien la implemente</h2>
 *
 * <ul>
 *   <li><b>Corre en la transaccion de la desvinculacion</b>: si falla, la desvinculacion no ocurre.
 *       Tiene que ser corta y no tomar locks que la desvinculacion no tome ya.</li>
 *   <li><b>Idempotente</b>: puede llamarse por una causa que no le quito nada a la cuenta (un cambio
 *       de rol a otro que tambien vende). Quien reacciona vuelve a evaluar el permiso; no infiere
 *       nada de la causa.</li>
 *   <li><b>No es un bloqueo.</b> Igual que la sonda: rechazar la desvinculacion porque queda algo
 *       pendiente es una regla nueva que necesita su RF.</li>
 * </ul>
 */
public interface ReaccionALaDesvinculacion {

	/** Que cambio en el vinculo. Informativo: viaja a la auditoria de quien reacciona. */
	enum Causa {
		REVOCACION,
		CAMBIO_DE_ROL,
		CAMBIO_DE_SEDE,
		PERMISO_QUITADO
	}

	/**
	 * El hecho.
	 *
	 * @param accountId     la cuenta que perdio alcance
	 * @param actorCuentaId quien hizo el cambio
	 * @param at            instante del cambio, el mismo que audito {@code organization}
	 */
	record Desvinculacion(
			long organizationId, long membershipId, long accountId, Causa causa, long actorCuentaId, Instant at) {
	}

	void alDesvincular(Desvinculacion desvinculacion);
}
