package com.akine.organization.spi;

/**
 * Alta compuesta de una organizacion, invocada por {@code identity} durante el registro
 * self-service (ADR-0008).
 *
 * <p><b>Propagacion transaccional: {@code REQUIRED}, jamas {@code REQUIRES_NEW}.</b> Esto no
 * es un detalle de implementacion, es un requisito de correccion y por eso esta en el
 * contrato y no solo en el servicio.
 *
 * <p>El flujo real es: {@code identity} abre la transaccion, crea la cuenta, llama a este
 * metodo y despues sigue trabajando (credenciales, token de activacion, outbox). Si este
 * metodo abriera su propia transaccion, esa transaccion commitearia sola: un fallo posterior
 * en {@code identity} revertiria la cuenta y dejaria la organizacion, el consultorio y la
 * membership creados y apuntando a una cuenta que no existe. Eso es exactamente lo que
 * ADR-0008 prohibe —"Cuenta + Organizacion + Consultorio + Membership en UNA transaccion"— y
 * un tenant huerfano no se detecta hasta que alguien intenta entrar.
 *
 * <p>Con {@code REQUIRED} el metodo se suma a la transaccion del llamador y las cinco
 * escrituras viven o mueren juntas. Si nadie abrio transaccion, {@code REQUIRED} abre una:
 * el alta sigue siendo atomica cuando se invoca sola (por ejemplo desde un test o un job).
 *
 * <p><b>Idempotencia.</b> {@code idempotencyKey} es la unidad de reintento:
 * <ol>
 *   <li>Se busca el registro por clave. Si existe, se devuelve el resultado original con
 *       {@code created = false}. No se crea un segundo tenant ni se reescribe el primero.</li>
 *   <li>Si no existe, se crea todo y se inserta el registro de idempotencia.</li>
 *   <li>En un reintento <b>concurrente</b> los dos hilos leen "no existe" y los dos insertan:
 *       el segundo viola {@code uk_onboarding_key}, se captura la violacion, se relee por
 *       clave y se devuelve el resultado del ganador. Resolverlo con un SELECT previo y nada
 *       mas seria la misma carrera con otro nombre.</li>
 * </ol>
 *
 * <p>Direccion de la dependencia (T-1): {@code identity -> organization.spi} esta permitido;
 * {@code organization -> identity} esta prohibido sin excepciones. Por eso el comando lleva
 * un {@code accountId} y no una cuenta.
 */
public interface InitialOrganizationProvisioning {

	/**
	 * Crea organizacion + suscripcion + primer consultorio + membership del propietario.
	 *
	 * <p>La membership nace con {@code ORG_ADMIN} e {@code is_founder = true} (T-5). El rol
	 * de seguridad y la condicion de fundador son dimensiones distintas: crear un rol
	 * {@code OWNER} para distinguirlas esta prohibido por RN-M05-006.
	 *
	 * @param command datos del alta, ya validados por su propio constructor
	 * @return ids creados, con {@code created} indicando si esta invocacion los creo
	 * @throws com.akine.organization.application.IdempotencyKeyConflictException si la clave
	 *         ya se uso con un payload distinto
	 */
	ProvisioningResult provision(InitialOrganizationCommand command);
}
