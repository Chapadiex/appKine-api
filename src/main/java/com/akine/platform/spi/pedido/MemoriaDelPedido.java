package com.akine.platform.spi.pedido;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Memoria de lecturas que vive <b>exactamente un pedido HTTP</b> y nunca uno mas (docs/carga.md
 * §9).
 *
 * <h2>Para que existe</h2>
 *
 * <p>Cada pedido autenticado lee {@code platform_role} y {@code membership} en los filtros de
 * seguridad, y la evaluacion de permisos del servicio vuelve a leer exactamente las mismas filas
 * unos milisegundos despues — a veces dos o tres veces, una por cada permiso que evalua. ADR-0020
 * y T-7 exigen <b>revalidar contra la base en cada pedido</b>, y eso no cambia: lo que se evita es
 * leer lo mismo varias veces <b>dentro</b> del mismo pedido.
 *
 * <h2>Las tres garantias</h2>
 * <ol>
 *   <li><b>Nunca cruza pedidos.</b> El alcance lo abre y lo cierra {@code AlcanceDelPedidoFilter}
 *       en un {@code finally}; los hilos del contenedor se reutilizan y lo que sobreviviera se lo
 *       llevaria el proximo pedido. Fuera de un pedido —un job, el outbox, un test que llama al
 *       servicio— no hay alcance y cada lectura va a la base, como siempre.</li>
 *   <li><b>Nunca dentro de una transaccion de escritura, y nunca despues de un commit de
 *       escritura.</b> Lo que se evalua <b>dentro</b> de una transaccion de lectura-escritura
 *       —p. ej. una mutacion de memberships que evalua despues de tomar el bloqueo del tenant—
 *       lee la base en ese momento, y la memoria entera se descarta. Ademas, cuando commitea
 *       cualquier transaccion de escritura del pedido, el transaction manager llama a
 *       {@link #olvidar()}: una membership creada o revocada en el pedido no deja leer lo de
 *       antes en lo que siga del mismo pedido.</li>
 *   <li><b>Se memorizan filas, no decisiones.</b> Quien usa esta memoria guarda lo que leyo y
 *       sigue evaluando vigencia, alcance y permiso con el instante de cada llamada.</li>
 * </ol>
 *
 * <h2>El limite, dicho entero</h2>
 *
 * <p>La lectura fresca cubre solo lo que se evalua <b>dentro</b> de una transaccion de escritura.
 * Las mutaciones que desde #91 evaluan el permiso <b>antes</b> de abrir su transaccion de negocio
 * —p. ej. {@code CobroService.registrar}, que llama a {@code exigirGestion} y recien despues a
 * {@code transaccionDeNegocio.execute}— deciden con lo que leyo el filtro de contexto unos
 * milisegundos antes, en el mismo pedido. La ventana de revocacion es la de un pedido, apenas
 * mayor que en {@code main} antes de este cambio (donde esa evaluacion releia la base fuera de la
 * transaccion de negocio), y nunca cruza al pedido siguiente.
 */
public final class MemoriaDelPedido {

	private static final ThreadLocal<Map<Object, Object>> ALCANCE = new ThreadLocal<>();

	private MemoriaDelPedido() {
		// Utilidad.
	}

	/**
	 * Devuelve lo que {@code lector} ya leyo para {@code clave} en este pedido, o lo lee ahora.
	 *
	 * <p>{@code clave} tiene que identificar la lectura entera (un record con la consulta y sus
	 * parametros), y lo que devuelve {@code lector} no debe modificarse despues: lo comparten
	 * todas las llamadas del pedido.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T memorizar(Object clave, Supplier<T> lector) {
		Map<Object, Object> memoria = ALCANCE.get();
		if (memoria == null) {
			return lector.get();
		}
		if (enTransaccionDeEscritura()) {
			memoria.clear();
			return lector.get();
		}
		Object guardado = memoria.get(clave);
		if (guardado != null) {
			return (T) guardado;
		}
		T leido = lector.get();
		if (leido != null) {
			memoria.put(clave, leido);
		}
		return leido;
	}

	/**
	 * Abre el alcance del pedido en este hilo. Solo lo llama el filtro que delimita el pedido, y
	 * siempre con su {@link Alcance#close()} en un {@code finally} (try-with-resources).
	 */
	public static Alcance abrir() {
		Map<Object, Object> previo = ALCANCE.get();
		ALCANCE.set(new HashMap<>());
		return () -> {
			if (previo == null) {
				ALCANCE.remove();
			} else {
				ALCANCE.set(previo);
			}
		};
	}

	/**
	 * Descarta todo lo memorizado en el pedido en curso, si hay uno. Lo llama el transaction
	 * manager de la aplicacion al commitear una transaccion de escritura.
	 */
	public static void olvidar() {
		Map<Object, Object> memoria = ALCANCE.get();
		if (memoria != null) {
			memoria.clear();
		}
	}

	private static boolean enTransaccionDeEscritura() {
		return TransactionSynchronizationManager.isActualTransactionActive()
				&& !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
	}

	/** El alcance abierto; cerrarlo descarta todo lo memorizado en el. */
	@FunctionalInterface
	public interface Alcance extends AutoCloseable {

		@Override
		void close();
	}
}
