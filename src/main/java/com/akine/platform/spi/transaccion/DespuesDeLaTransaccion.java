package com.akine.platform.spi.transaccion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Trabajo que tiene que correr cuando la transaccion de negocio ya commiteo <b>y ya devolvio su
 * conexion</b> al pool.
 *
 * <h2>Por que no alcanza con {@code afterCommit} (docs/carga.md §8)</h2>
 *
 * <p>{@code TransactionSynchronization.afterCommit} —y tambien {@code afterCompletion}— corren
 * antes de que Spring limpie los recursos de la transaccion: con JPA el {@code EntityManager}, y
 * con el su conexion, siguen tomados. Un {@code REQUIRES_NEW} desde ahi pide una segunda conexion
 * con la primera retenida, que es exactamente el traba del pool de §6.1: N cierres de sesion con
 * prepago a la vez, con un pool de N, se quedaban esperando y la imputacion moria con un warn.
 *
 * <p>La plantilla de {@link FilaLockPrevia#transaccionDeNegocio} abre un alcance mientras dura su
 * transaccion; lo que se registra aca adentro corre cuando la plantilla ya termino —commit y
 * limpieza—, en el mismo hilo y sin ninguna conexion tomada. Si la transaccion hace rollback, lo
 * registrado se descarta.
 */
public final class DespuesDeLaTransaccion {

	private static final ThreadLocal<Deque<List<Runnable>>> ALCANCES =
			ThreadLocal.withInitial(ArrayDeque::new);

	private DespuesDeLaTransaccion() {
		// Utilidad.
	}

	/**
	 * Registra el trabajo para despues de la transaccion de negocio en curso.
	 *
	 * @return {@code false} si este hilo no esta dentro de una plantilla de negocio: el llamador
	 *         decide que hacer (p. ej. caer a {@code afterCommit})
	 */
	public static boolean registrar(Runnable trabajo) {
		Deque<List<Runnable>> pila = ALCANCES.get();
		if (pila.isEmpty()) {
			return false;
		}
		pila.peek().add(trabajo);
		return true;
	}

	static void abrir() {
		ALCANCES.get().push(new ArrayList<>());
	}

	/** Cierra el alcance abierto por {@link #abrir} y devuelve lo registrado en el. */
	static List<Runnable> cerrar() {
		Deque<List<Runnable>> pila = ALCANCES.get();
		List<Runnable> registrados = pila.pop();
		if (pila.isEmpty()) {
			ALCANCES.remove();
		}
		return registrados;
	}
}
