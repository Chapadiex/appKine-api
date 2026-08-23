package com.akine.organization.spi;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Control de limites y funcionalidades del plan contratado (RF-M01-004).
 *
 * <p>Lo consume todo modulo que de de alta un recurso limitado o use una funcionalidad
 * facturable. {@code organization} decide si el alta entra; el modulo consumidor sigue siendo
 * el propietario de su tabla y el unico que puede contarla.
 *
 * <h2>Por que la firma es asi y no la obvia</h2>
 *
 * <p>La firma natural seria {@code evaluateCreation(orgId, limit, long currentUsage)}, con el
 * consumidor contando su recurso y pasando el numero. <b>Esa firma esta prohibida</b> porque
 * produce una carrera que viola el limite en silencio:
 *
 * <pre>
 *   limite = 5, uso real = 4
 *
 *   hilo A: cuenta 4 -----> gate: 4 &lt; 5, pasa -----> INSERT -----> commit
 *   hilo B:      cuenta 4 -----> gate: 4 &lt; 5, pasa -----> INSERT -----> commit
 *
 *   resultado: 6 filas con un limite de 5
 * </pre>
 *
 * <p>Los uniques de la tabla consumidora no salvan el caso: {@code uk_consultorio_org_name}
 * impide dos sedes con el mismo nombre, no impide una CANTIDAD. Un unique restringe valores
 * repetidos; un limite de plan restringe un conteo, y no existe indice que exprese eso.
 *
 * <p>La correccion es serializar por tenant, y el punto de serializacion natural es la fila de
 * la suscripcion —una por organizacion, siempre presente, siempre involucrada en la decision—.
 * Por eso el gate:
 *
 * <ol>
 *   <li>corre DENTRO de la transaccion del alta ({@code MANDATORY}: si no hay transaccion
 *       abierta, falla en vez de tomar un bloqueo que se soltaria al volver);</li>
 *   <li>bloquea la suscripcion con {@code SELECT ... FOR UPDATE};</li>
 *   <li><b>recien entonces</b> invoca el contador que le paso el consumidor;</li>
 *   <li>decide, y si rechaza lanza;</li>
 *   <li>el consumidor inserta y commitea, y el commit libera el bloqueo.</li>
 * </ol>
 *
 * <p>Recibir el conteo como {@code long} haria imposible garantizar el paso 3: el numero ya
 * estaria calculado antes de entrar. Recibirlo como {@link LongSupplier} invierte el orden y
 * lo hace cumplir por construccion — el gate es el unico que puede disparar el conteo, y solo
 * lo dispara con el bloqueo tomado. Si alguien "simplifica" esta firma a un {@code long},
 * vuelve la carrera de arriba.
 *
 * <p>Costo: un bloqueo pesimista sobre UNA fila de UN tenant, con una seccion critica de un
 * conteo indexado. Los tenants no se bloquean entre si.
 *
 * <p>Los rechazos son reglas de negocio, no problemas de permisos: el actor tiene derecho a
 * hacer la operacion, lo que no alcanza es el plan contratado. Por eso son {@code 409}
 * ({@code BUSINESS_RULE_VIOLATION}) y no {@code 403}.
 */
public interface PlanGate {

	/**
	 * Ejecuta un alta limitada de punta a punta: abre la transaccion, evalua el limite y, si
	 * entra, corre la creacion del consumidor. <b>Es la forma recomendada de usar el gate.</b>
	 *
	 * <h2>Por que el gate abre la transaccion y no el consumidor</h2>
	 *
	 * <p>Porque el bloqueo pesimista, solo, no alcanza, y de que alcance depende un atributo
	 * que el gate no puede fijar desde adentro: la <b>isolation</b>.
	 *
	 * <p>En REPEATABLE READ —el default de InnoDB— la primera lectura no bloqueante de la
	 * transaccion fija su snapshot. El {@code SELECT ... FOR UPDATE} posterior serializa el
	 * ACCESO: el segundo hilo espera de verdad. Pero cuando por fin entra y cuenta, el conteo
	 * es una lectura consistente y sigue viendo el snapshot viejo, anterior al commit del
	 * primero. Cuenta de menos, decide que entra, y el limite se viola en silencio. El bloqueo
	 * serializa el acceso, no la VISIBILIDAD.
	 *
	 * <p>Con READ COMMITTED cada sentencia toma su propia vista, asi que el conteo posterior al
	 * bloqueo ve lo que el bloqueo acaba de dejar pasar, y el protocolo funciona como esta
	 * escrito.
	 *
	 * <p>La isolation la fija <b>quien abre la transaccion</b>: anotarla en el metodo del gate
	 * no serviria de nada, porque {@code MANDATORY} se une a una transaccion ya abierta y al
	 * unirse el atributo se ignora. Y subirla para toda la aplicacion es una decision mucho
	 * mas grande que la que este problema justifica. Por eso el gate ofrece este metodo: abre
	 * la transaccion el mismo, con READ COMMITTED, y el alcance del cambio es exactamente el
	 * alta limitada y nada mas.
	 *
	 * @param organizationId      tenant del alta
	 * @param limit               limite a evaluar
	 * @param currentUsageCounter cuenta los recursos ACTIVOS del tenant, dentro de la
	 *                            transaccion y ya con el bloqueo tomado
	 * @param creation            crea el recurso. Corre DENTRO de la misma transaccion y
	 *                            despues de la decision; lo que devuelva se devuelve.
	 *                            <b>No puede delegar la escritura en un metodo
	 *                            {@code REQUIRES_NEW}:</b> eso la sacaria de esta transaccion
	 *                            y por lo tanto de la proteccion del bloqueo, que es lo unico
	 *                            que el gate no puede impedir desde adentro
	 * @throws IllegalStateException si ya hay una transaccion abierta: unirse a ella heredaria
	 *         su isolation y volveria a abrir la fuga que este metodo cierra
	 * @throws com.akine.organization.domain.exception.PlanLimitExceededException si el alta
	 *         excederia el limite (409)
	 */
	<T> T createWithinLimit(
			long organizationId,
			LimitCode limit,
			LongSupplier currentUsageCounter,
			Supplier<T> creation);

	/**
	 * Bloquea la suscripcion, cuenta el uso y decide si un alta mas entra.
	 *
	 * <p><b>Se invoca dentro de la transaccion del alta.</b> La implementacion esta anotada
	 * {@code MANDATORY}: llamarla sin transaccion abierta falla de inmediato en lugar de tomar
	 * un bloqueo inutil que se libera al volver del metodo.
	 *
	 * <p>Casos del limite, que no son intercambiables:
	 * <ul>
	 *   <li>sin fila de limite para ese plan: el limite no aplica, permite;</li>
	 *   <li>{@code limit_value NULL}: ilimitado explicito, permite;</li>
	 *   <li>{@code currentUsage < limit_value}: permite;</li>
	 *   <li>si no: lanza {@code PlanLimitExceededException}.</li>
	 * </ul>
	 *
	 * @param organizationId      tenant del alta
	 * @param limit               limite a evaluar
	 * @param currentUsageCounter cuenta los recursos ACTIVOS del tenant. Lo provee el modulo
	 *                            propietario de la tabla, que es el unico que puede contarla.
	 *                            El gate lo invoca UNA vez, ya con el bloqueo tomado: no debe
	 *                            capturar un conteo hecho antes ni abrir transacciones propias
	 * @return la decision, siempre con {@code allowed = true}
	 * @throws com.akine.organization.domain.exception.PlanLimitExceededException si el alta
	 *         excederia el limite (409)
	 * @throws com.akine.organization.domain.exception.SubscriptionSuspendedException si la
	 *         suscripcion no habilita mutaciones de negocio (409)
	 * @throws com.akine.organization.domain.exception.OrganizationNotFoundException si la
	 *         organizacion no existe o no es alcanzable (404)
	 */
	PlanDecision evaluateCreationAndLock(
			long organizationId, LimitCode limit, LongSupplier currentUsageCounter);

	/**
	 * Exige que el plan vigente incluya la funcionalidad.
	 *
	 * <p>No bloquea nada: una feature es presencia o ausencia de una fila, no un conteo, asi
	 * que no hay carrera que cerrar. Dos hilos que preguntan lo mismo obtienen lo mismo.
	 *
	 * @throws com.akine.organization.domain.exception.FeatureNotAvailableException si el plan
	 *         no la incluye (409)
	 */
	void requireFeature(long organizationId, FeatureCode feature);

	/**
	 * Indica si el tenant admite mutaciones de negocio ahora mismo.
	 *
	 * <p>Es {@code false} con la organizacion dada de baja o con la suscripcion SUSPENDIDA o
	 * CANCELADA. Suspender bloquea, jamas destruye (RN-M01-002): las lecturas y la
	 * administracion siguen funcionando, y esta pregunta no las alcanza.
	 *
	 * <p>Devuelve un booleano y no el estado operativo completo porque el enum
	 * {@code OperationalStatus} vive en {@code domain}, que es privado del modulo: exponerlo
	 * obligaria a cada consumidor a importar {@code organization.domain}, que ArchUnit
	 * rechaza. Quien necesite el estado con nombre lo obtiene por el contrato de tenancy de
	 * {@code platform.spi}.
	 */
	boolean allowsBusinessMutations(long organizationId);
}
