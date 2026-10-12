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
 * empieza ({@link #empezo}) y cuando termino de limpiarse ({@link #termino}). Cuando la ultima
 * transaccion del hilo se limpio, corre lo registrado: sin ninguna conexion tomada, en el mismo
 * hilo, antes de que la excepcion o el resultado salgan del proxy transaccional.
 *
 * <h2>Diferencia con {@link DespuesDeLaTransaccion}</h2>
 *
 * <p>{@link DespuesDeLaTransaccion} es "si la transaccion de negocio commiteo": lo registrado se
 * descarta en el rollback, y solo funciona dentro de la plantilla de {@link FilaLockPrevia}. Esto
 * es "termine como termine", y vale para cualquier transaccion, anotada o de plantilla.
 *
 * <h2>Lo que se pierde respecto del {@code REQUIRES_NEW}</h2>
 *
 * <p>Si lo registrado falla, ya no puede hacer fallar el pedido: la respuesta esta decidida. Se
 * loguea en ERROR y se sigue con lo proximo. Es la misma contrapartida que ya tenia la imputacion
 * de prepago.
 */
public final class AlTerminarLaTransaccion {

	private static final Logger log = LoggerFactory.getLogger(AlTerminarLaTransaccion.class);

	private static final ThreadLocal<Estado> ESTADO = new ThreadLocal<>();

	private AlTerminarLaTransaccion() {
		// Utilidad.
	}

	/**
	 * Corre el trabajo cuando termine la transaccion de este hilo, o ahora si no hay ninguna.
	 *
	 * <p>Si hay varias anidadas —un {@code REQUIRES_NEW} que todavia quede— espera a la ultima:
	 * mientras alguna siga abierta, el hilo tiene una conexion tomada.
	 *
	 * <p>Sin el transaction manager de la aplicacion —un slice de test con otro— no hay aviso de
	 * cierre y el trabajo corre en el acto, que es la conducta de antes.
	 */
	public static void ejecutar(Runnable trabajo) {
		Estado estado = ESTADO.get();
		if (estado == null || estado.abiertas == 0) {
			trabajo.run();
			return;
		}
		estado.pendientes.add(trabajo);
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
	 * Solo para el transaction manager: una transaccion fisica de este hilo termino y ya devolvio
	 * su conexion. Si era la ultima, corre lo registrado.
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
		for (Runnable trabajo : estado.pendientes) {
			try {
				trabajo.run();
			} catch (RuntimeException falla) {
				log.error("Fallo el trabajo registrado para despues de la transaccion", falla);
			}
		}
	}

	private static final class Estado {

		private int abiertas;
		private final List<Runnable> pendientes = new ArrayList<>();
	}
}
