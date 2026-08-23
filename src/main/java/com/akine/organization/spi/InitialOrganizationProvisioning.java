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
	 * Valida lo que este modulo sabe del pedido, <b>sin escribir nada</b>.
	 *
	 * <h2>Por que existe: el oraculo de existencia de cuentas por {@code planCode}</h2>
	 *
	 * <p>El registro self-service tiene dos caminos: si el email esta libre crea la cuenta y
	 * llama a {@link #provision}, y si ya tiene cuenta no crea nada y encola un aviso. Todo lo
	 * que valide este modulo lo valida SOLO el primer camino. Con eso, un {@code planCode}
	 * inexistente respondia <b>404 si el email estaba libre y 202 si ya existia</b>: un unico
	 * request publico, sin autenticacion y sin depender de tiempos, convertido en verificador
	 * de direcciones de correo. Es exactamente lo que ADR-0018 existe para cerrar.
	 *
	 * <p>La solucion no es duplicar la validacion en el camino del duplicado —volveria a
	 * divergir a la primera modificacion— sino <b>sacarla de los dos</b>: el llamador invoca
	 * esto ANTES de mirar el email. Asi un plan invalido responde igual exista o no la cuenta
	 * (el mismo 404) y un plan valido tambien (el mismo 202). Las dos ramas terminan en el
	 * mismo estado y el mismo cuerpo para cualquier combinacion de {@code planCode} y
	 * {@code organizationSlug}.
	 *
	 * <p>El orden importa y es al reves de lo intuitivo: validar DESPUES de mirar el email
	 * —solo cuando hace falta— es lo que produce la fuga, porque quien manda un plan valido ve
	 * un camino y quien manda uno invalido ve dos.
	 *
	 * <p>Es idempotente y no tiene efectos: se puede invocar tantas veces como haga falta.
	 *
	 * <p>Recibe los dos campos sueltos y no un {@link InitialOrganizationCommand} porque en el
	 * momento en que hay que llamarlo <b>todavia no existe la cuenta</b> —justamente, se
	 * valida antes de decidir si se crea— y el comando exige un {@code accountId} valido.
	 * Fabricar uno falso para poder validar seria peor que pasar dos strings.
	 *
	 * @param planCode plan pedido, o {@code null} para el de defecto
	 *                 ({@link InitialOrganizationCommand#PLAN_POR_DEFECTO})
	 * @param organizationSlug slug explicito, o {@code null} si se va a derivar del nombre
	 * @throws com.akine.organization.application.PlanNotFoundException si el plan no existe o
	 *         ya no es contratable (404)
	 * @throws com.akine.organization.domain.exception.OrganizationSlugTakenException si el slug
	 *         explicito ya lo usa otro tenant (409). Solo se comprueba cuando el llamador
	 *         manda uno: cuando se deriva del nombre, la colision se resuelve con sufijo
	 */
	void validateTenantRequest(String planCode, String organizationSlug);

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
	 * @throws OnboardingKeyTakenException si otro hilo registro la misma clave mientras esta
	 *         invocacion trabajaba. <b>La transaccion queda revertida</b> y el llamador tiene
	 *         que resolver fuera de ella: leer el JavaDoc de esa excepcion antes de atraparla
	 * @throws com.akine.organization.domain.exception.OrganizationSlugTakenException si el slug
	 *         pedido ya lo usa otro tenant. Tambien revierte la transaccion
	 */
	ProvisioningResult provision(InitialOrganizationCommand command);
}
