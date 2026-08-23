package com.akine.organization.spi;

import java.util.function.LongSupplier;

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
