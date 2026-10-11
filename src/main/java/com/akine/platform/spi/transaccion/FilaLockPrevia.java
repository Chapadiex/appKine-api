package com.akine.platform.spi.transaccion;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.BooleanSupplier;

/**
 * Asegura una fila-lock <b>antes</b> de que empiece la transaccion de negocio que la va a bloquear.
 *
 * <h2>Por que existe: el pool de conexiones se trababa (G-10, docs/carga.md §6.1)</h2>
 *
 * <p>La regla del proyecto dice que toda fila-lock se crea en una transaccion aparte, con
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}: crearla perezosamente dentro de la transaccion que la
 * bloquea produce deadlock en InnoDB. Hasta el 11/10/2026 esa transaccion aparte era un
 * {@code @Transactional(propagation = REQUIRES_NEW)} llamado <b>desde adentro</b> de la de negocio.
 * {@code REQUIRES_NEW} suspende la transaccion de afuera pero <b>no devuelve su conexion</b>: el
 * pedido retiene una y pide otra. Con el pool de 10 de Hikari, diez reservas o cobros simultaneos
 * toman las diez y esperan la segunda que nadie va a devolver. La prueba de carga lo midio: el
 * backend se trababa a los 20 segundos con menos de 100 usuarios y quedaba degradado hasta
 * reiniciarlo. Agrandar el pool no lo arregla: el traba vuelve con tantas escrituras simultaneas
 * como conexiones haya.
 *
 * <p>La salida es el orden: el iniciador corre <b>sin</b> transaccion abierta —usa su conexion, la
 * commitea y la devuelve— y recien despues el servicio abre la suya. Nunca hay dos conexiones por
 * pedido. Este helper hace cumplir ese orden: si alguien lo llama con una transaccion activa y la
 * fila hay que crearla —el unico caso en que haria falta una segunda conexion—, <b>falla</b> en vez
 * de reintroducir el traba en silencio. Si la fila ya existe no hace falta ninguna conexion mas, y
 * no hay nada que impedir.
 *
 * <h2>La fila que ya existe no se toca</h2>
 *
 * <p>Antes de insertar se pregunta, con una lectura sin lock, si la fila ya esta. Es el caso del
 * 99,9 % de los pedidos: la fila se crea una vez por sede, persona o historia y despues solo se
 * bloquea. El {@code ON DUPLICATE KEY UPDATE} sobre una fila existente toma lock exclusivo, asi
 * que sin esta pregunta el iniciador esperaba a la transaccion que en ese momento tenia la fila
 * —en el cobro, ~340 ms de promedio con carga (§6.2)— para no crear nada. Si la lectura llega
 * tarde y otra transaccion la crea en el medio, el {@code INSERT} idempotente resuelve igual.
 */
public final class FilaLockPrevia {

	private final String filaLock;
	private final TransactionTemplate transaccion;

	/**
	 * @param filaLock           nombre de la tabla, para el mensaje de error
	 * @param transactionManager el de la aplicacion; la transaccion que abre es la UNICA del pedido
	 *                           en ese momento
	 */
	public FilaLockPrevia(String filaLock, PlatformTransactionManager transactionManager) {
		this.filaLock = filaLock;
		this.transaccion = new TransactionTemplate(transactionManager);
	}

	/**
	 * Crea la fila si falta, en su propia transaccion, y la commitea.
	 *
	 * @param existe lectura SIN lock de la fila
	 * @param crear  el {@code INSERT ... ON DUPLICATE KEY UPDATE}, idempotente
	 * @throws IllegalStateException si la fila falta y hay una transaccion activa: crearla pediria
	 *                               una segunda conexion mientras la de afuera sigue tomada
	 */
	public void asegurar(BooleanSupplier existe, Runnable crear) {
		if (existe.getAsBoolean()) {
			return;
		}
		exigirFueraDeTransaccion(filaLock);
		transaccion.executeWithoutResult(estado -> crear.run());
	}

	/**
	 * La transaccion de negocio que corre DESPUES del iniciador.
	 *
	 * <p>Reemplaza al {@code @Transactional} del metodo de servicio, que ya no puede abrirse antes
	 * de que el iniciador termine: el metodo publico queda sin transaccion, asegura la fila y
	 * recien ahi ejecuta el cuerpo con esta plantilla. La aislacion es la que declaraba la anotacion.
	 */
	public static TransactionTemplate transaccionDeNegocio(
			PlatformTransactionManager transactionManager, Isolation aislacion) {
		TransactionTemplate plantilla = new TransactionTemplate(transactionManager);
		plantilla.setIsolationLevel(aislacion.value());
		return plantilla;
	}

	/**
	 * Falla si hay una transaccion activa en este hilo.
	 *
	 * <p>Tambien la usan los servicios que preparan algo fuera de su transaccion y no son
	 * iniciadores.
	 */
	public static void exigirFueraDeTransaccion(String que) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("La fila-lock " + que + " se asegura ANTES de abrir la"
					+ " transaccion de negocio, no adentro: una transaccion anidada retiene la conexion"
					+ " de afuera y traba el pool (docs/carga.md §6.1)");
		}
	}
}
