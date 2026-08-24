package com.akine.platform.spi.identity;

import java.util.Collection;
import java.util.Map;

/**
 * Resuelve ids de cuenta a nombre y email. <b>Solo lectura, y solo por id.</b>
 *
 * <h2>Por que vive en {@code platform.spi} y no en {@code identity.spi}</h2>
 *
 * <p>El consumidor natural es {@code organization} —su listado de colaboradores tiene un
 * {@code account_id} por fila y nada mas— y la flecha {@code organization -> identity} esta
 * prohibida sin excepciones: {@code identity} ya depende de {@code organization.spi} para
 * provisionar el tenant del alta, asi que la vuelta cierra un ciclo y
 * {@code sin_ciclos_entre_modulos} lo rechaza.
 *
 * <p>La salida es un <b>puerto invertido</b> declarado en el modulo base: {@code platform} no
 * depende de nadie, {@code identity} lo implementa y {@code organization} lo consume. Las dos
 * flechas apuntan a {@code platform.spi} y no hay ciclo. Es exactamente el patron de
 * {@code platform.spi.tenant.MembershipDirectory}, que {@code organization} implementa y
 * {@code platform} consume, en el sentido contrario.
 *
 * <h2>Por que no alcanzaba {@code identity.spi.AccountDirectory}</h2>
 *
 * <p>Por lo anterior —{@code organization} no puede importarlo— y porque resuelve de a una
 * cuenta: usarlo desde un listado de 200 colaboradores serian 200 consultas.
 *
 * <h2>Esto no es un padron de cuentas</h2>
 *
 * <p>No hay busqueda por email ni listado global, y no puede haberlos: eso convertiria el
 * contrato en el oraculo de existencia de cuentas que ADR-0018 y {@code AccountAdminController}
 * existen para no dar. Resolver un id que el llamador ya tiene delante no revela nada nuevo;
 * preguntar por una direccion, si.
 */
public interface AccountIdentityDirectory {

	/**
	 * Resuelve varias cuentas de una sola vez.
	 *
	 * <p><b>La firma es en lote a proposito.</b> Un metodo de a uno invita al N+1 desde el
	 * primer listado que lo use, y despues cuesta mucho mas sacarlo que no haberlo puesto.
	 *
	 * @param accountIds ids a resolver; puede traer repetidos y nulos, que se ignoran
	 * @return mapa id -> identidad. Los ids que no corresponden a ninguna cuenta <b>no
	 *         aparecen</b>: el llamador decide que hacer con la ausencia, que este contrato no
	 *         puede inventar
	 */
	Map<Long, AccountIdentity> identidadesDe(Collection<Long> accountIds);
}
