package com.akine.platform.spi.transaccion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Trabajo que tiene que correr cuando la transaccion del pedido ya termino —por commit <b>o por
 * rollback</b>— y ya devolvio su conexion al pool.
 *
 * <h2>Por que existe (docs/carga.md §8)</h2>
 *
 * <p>Varios hechos tienen que quedar escritos <b>aunque</b> la operacion que los origina falle: el
 * {@code PERMISSION_DENIED} de un 403, el {@code SUPPORT_ACCESS_USED} de una lectura que termina en
 * 404, la marca {@code NO_DISPONIBLE} de un adjunto cuyo binario se perdio. Hasta el 11/10/2026 se
 * escribian con un {@code REQUIRES_NEW} llamado desde adentro de la transaccion de negocio, y ese
 * {@code REQUIRES_NEW} es el mismo traba del pool que {@link FilaLockPrevia} corrigio para las
 * filas-lock: suspende la transaccion de afuera pero no devuelve su conexion, asi que cada pedido
 * retiene una y pide otra. Con el pool de 10, diez 403 simultaneos lo traban.
 *
 * <p>{@code TransactionSynchronization.afterCompletion} no sirve: corre antes de que Spring limpie
 * los recursos de la transaccion, con la conexion todavia tomada. Lo unico que corre despues es la
 * limpieza misma, y por eso el transaction manager de la aplicacion
 * ({@code platform.infrastructure.config.TransaccionesConfig}) avisa aca cuando una transaccion
 * fisica empieza ({@link #empezo}), cuando la de afuera commiteo ({@link #commiteo}) y cuando
 * termino de limpiarse ({@link #termino}). Cuando la ultima del hilo se limpio, corre lo
 * registrado: sin ninguna conexion tomada, en el mismo hilo, <b>antes</b> de que el resultado o la
 * excepcion salgan del proxy transaccional.
 *
 * <h2>Tres modos, segun lo que cueste perder el hecho</h2>
 *
 * <ul>
 *   <li>{@link #ejecutar}: <b>al mejor esfuerzo</b>. Si falla, se loguea en ERROR y el pedido sigue
 *       como iba. Para los rechazos (403, limite de plan) y la marca {@code NO_DISPONIBLE}: el
 *       pedido ya fallo por su lado.</li>
 *   <li>{@link #ejecutarObligatorio}: si la transaccion <b>commiteo</b> y esto falla, la falla se
 *       relanza: sale del {@code commit} y el llamador recibe la excepcion en vez del resultado. Es
 *       la auditoria del acceso de soporte en una lectura: si no queda escrita, la lectura no se
 *       entrega. Si la transaccion revirtio, el pedido ya falla con su propia excepcion; esa no se
 *       tapa y la falla de aca se loguea.</li>
 *   <li>{@link #ejecutarSiRevierte}: solo si la transaccion revirtio, al mejor esfuerzo. Para el
 *       hecho que en el camino feliz ya se escribio adentro de la transaccion y que el rollback se
 *       llevaria puesto.</li>
 * </ul>
 *
 * <h2>Diferencia con {@link DespuesDeLaTransaccion}</h2>
 *
 * <p>{@link DespuesDeLaTransaccion} es "si la transaccion de negocio commiteo": lo registrado se
 * descarta en el rollback, y solo funciona dentro de la plantilla de {@link FilaLockPrevia}. Esto
 * vale para cualquier transaccion, anotada o de plantilla.
 */
public final class AlTerminarLaTransaccion {

	private static final Logger log = LoggerFactory.getLogger(AlTerminarLaTransaccion.class);

	private static final ThreadLocal<Estado> ESTADO = new ThreadLocal<>();

	private AlTerminarLaTransaccion() {
		// Utilidad.
	}

	/**
	 * Al mejor esfuerzo: corre cuando termine la transaccion de este hilo, como termine, o ahora si
	 * no hay ninguna. Si falla despues de la transaccion, solo se loguea.
	 *
	 * <p>Si hay varias anidadas espera a la ultima: mientras alguna siga abierta, el hilo tiene una
	 * conexion tomada. Sin el transaction manager de la aplicacion —un slice de test con otro— no
	 * hay aviso de cierre y el trabajo corre en el acto, que es la conducta de antes.
	 */
	public static void ejecutar(Runnable trabajo) {
		registrar(trabajo, Modo.MEJOR_ESFUERZO);
	}

	/**
	 * Obligatorio: como {@link #ejecutar}, pero si la transaccion commiteo y el trabajo falla, la
	 * falla se relanza y el pedido no entrega su resultado.
	 */
	public static void ejecutarObligatorio(Runnable trabajo) {
		registrar(trabajo, Modo.OBLIGATORIO);
	}

	/**
	 * Solo si la transaccion de este hilo revierte, al mejor esfuerzo. Sin transaccion seguida por
	 * este aviso no hace nada: el llamador ya escribio el hecho en la transaccion en curso.
	 */
	public static void ejecutarSiRevierte(Runnable trabajo) {
		Estado estado = ESTADO.get();
		if (estado != null && estado.abiertas > 0) {
			estado.pendientes.add(new Pendiente(trabajo, Modo.SI_REVIERTE));
		}
	}

	/** Si este hilo esta dentro de una transaccion que este aviso sigue. */
	public static boolean siguiendoUnaTransaccion() {
		Estado estado = ESTADO.get();
		return estado != null && estado.abiertas > 0;
	}

	/**
	 * Falla si hay una transaccion activa en este hilo.
	 *
	 * <p>Para las escrituras que se hacen en su propia transaccion y no pueden unirse a otra: si
	 * alguien las llamara desde adentro de una, o pedirian una segunda conexion con la primera
	 * tomada o se unirian a una transaccion que despues revierte. Mejor que falle en el acto.
	 */
	public static void exigirSinTransaccion(String que) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(que + " corre fuera de toda transaccion, no adentro de"
					+ " una: anidada retendria la conexion de afuera y trabaria el pool"
					+ " (docs/carga.md §6.1)");
		}
	}

	/** Solo para el transaction manager: empezo una transaccion fisica en este hilo. */
	public static void empezo() {
		Estado estado = ESTADO.get();
		if (estado == null) {
			estado = new Estado();
			ESTADO.set(estado);
		}
		estado.abiertas++;
	}

	/**
	 * Solo para el transaction manager: una transaccion fisica de este hilo commiteo. Cuenta solo la
	 * de afuera; un {@code REQUIRES_NEW} que commitea adentro no dice nada del pedido.
	 */
	public static void commiteo() {
		Estado estado = ESTADO.get();
		if (estado != null && estado.abiertas == 1) {
			estado.commiteo = true;
		}
	}

	/**
	 * Solo para el transaction manager: una transaccion fisica de este hilo termino y ya devolvio
	 * su conexion. Si era la ultima, corre lo registrado.
	 *
	 * @throws RuntimeException la primera falla de un trabajo obligatorio, si la transaccion de
	 *                          afuera commiteo; las demas van como suprimidas
	 */
	public static void termino() {
		Estado estado = ESTADO.get();
		if (estado == null) {
			return;
		}
		estado.abiertas--;
		if (estado.abiertas > 0) {
			return;
		}
		// Se suelta el estado ANTES de correr: lo registrado abre sus propias transacciones, que
		// arman un estado nuevo y lo sueltan al terminar.
		ESTADO.remove();
		RuntimeException aRelanzar = null;
		for (Pendiente pendiente : estado.pendientes) {
			if (pendiente.modo() == Modo.SI_REVIERTE && estado.commiteo) {
				continue;
			}
			try {
				pendiente.trabajo().run();
			} catch (RuntimeException falla) {
				if (pendiente.modo() == Modo.OBLIGATORIO && estado.commiteo) {
					if (aRelanzar == null) {
						aRelanzar = falla;
					} else {
						aRelanzar.addSuppressed(falla);
					}
				} else {
					log.error("Fallo el trabajo registrado para despues de la transaccion ({})",
							estado.commiteo ? "commiteo" : "revirtio", falla);
				}
			}
		}
		if (aRelanzar != null) {
			log.error("Fallo un trabajo obligatorio despues del commit: el pedido no se entrega",
					aRelanzar);
			throw aRelanzar;
		}
	}

	private static void registrar(Runnable trabajo, Modo modo) {
		Estado estado = ESTADO.get();
		if (estado == null || estado.abiertas == 0) {
			trabajo.run();
			return;
		}
		estado.pendientes.add(new Pendiente(trabajo, modo));
	}

	private enum Modo { MEJOR_ESFUERZO, OBLIGATORIO, SI_REVIERTE }

	private record Pendiente(Runnable trabajo, Modo modo) {
	}

	private static final class Estado {

		private int abiertas;
		private boolean commiteo;
		private final List<Pendiente> pendientes = new ArrayList<>();
	}
}
