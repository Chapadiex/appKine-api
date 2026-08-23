package com.akine.organization.spi;

/**
 * Otro hilo registro antes la misma {@code idempotencyKey} del alta compuesta.
 *
 * <h2>Es una senal de control, no un error</h2>
 *
 * <p>No significa que el pedido haya fracasado: significa que <b>el ganador ya produjo el
 * efecto</b> y que esta invocacion no tiene nada que crear. Se llama "conflicto" solo en el
 * sentido de la restriccion {@code uk_onboarding_key}; hacia el usuario el desenlace es
 * indistinguible del de haber ganado, y tiene que serlo (ADR-0018).
 *
 * <h2>Por que sale del modulo en vez de resolverse adentro</h2>
 *
 * <p>La colision se detecta en el {@code flush} del registro de idempotencia, y una
 * {@code PersistenceException} deja el {@code EntityManager} en estado indefinido: la
 * especificacion de JPA prohibe seguir usandolo. Releer la fila del ganador sobre esa misma
 * sesion —que es lo que hacia el codigo anterior— termina en {@code AssertionFailure} o en
 * "Transaction marked as rollbackOnly", o sea un 500 en un endpoint publico que promete un 202
 * uniforme.
 *
 * <p>El unico movimiento legal es revertir y empezar en una sesion nueva, y esa decision
 * <b>no es de este modulo</b>: {@code provision} corre con {@code Propagation.REQUIRED} dentro
 * de la transaccion que abrio el llamador (ADR-0008 exige que las cinco escrituras vivan o
 * mueran juntas). Quien es dueno del limite transaccional es quien puede abrir uno nuevo, asi
 * que la senal viaja hacia arriba y el llamador decide. Por eso vive en {@code spi}: es parte
 * del contrato, no un detalle interno.
 *
 * <h2>Que tiene que hacer quien la recibe</h2>
 *
 * <ul>
 *   <li>Dar por revertida su transaccion: esta marcada para rollback y no admite mas trabajo.</li>
 *   <li>Responder el mismo desenlace que si el pedido lo hubiera atendido el ganador. Si
 *       necesita los ids creados, volver a invocar {@code provision} con la misma clave en una
 *       transaccion NUEVA: encontrara el registro y hara replay.</li>
 * </ul>
 *
 * <p>Deliberadamente <b>no</b> se traduce a un 409: la clave se reuso para el <i>mismo</i>
 * pedido. El 409 es de {@code IdempotencyKeyConflictException}, que es el caso opuesto —misma
 * clave, contenido distinto— y ese si es un error del cliente.
 */
public class OnboardingKeyTakenException extends RuntimeException {

	private final transient String idempotencyKey;

	public OnboardingKeyTakenException(String idempotencyKey) {
		// Sin causa y sin stack trace: es una carrera esperada, no una condicion excepcional, y
		// llenar el log con su traza no ayuda a nadie a entender nada.
		super("El alta compuesta con esa clave la registro otro hilo", null, false, false);
		this.idempotencyKey = idempotencyKey;
	}

	/** Para el log correlacionado. Nunca sale en una respuesta. */
	public String getIdempotencyKey() {
		return idempotencyKey;
	}
}
