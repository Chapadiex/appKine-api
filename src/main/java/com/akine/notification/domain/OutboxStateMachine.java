package com.akine.notification.domain;

import com.akine.notification.domain.exception.InvalidOutboxTransitionException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Transiciones validas del ciclo de entrega de una notificacion (RF-M26-004).
 *
 * <p>Clase PURA, mismo criterio que {@code SubscriptionStateMachine} de {@code organization}:
 * sin Spring, sin base, sin reloj. Toda la regla es la tabla de abajo, y por eso se puede
 * probar exhaustivamente —las 36 combinaciones— sin levantar un contexto.
 *
 * <pre>
 *   (alta)       -> PENDIENTE      unica forma de nacer
 *   PENDIENTE    -> PROCESANDO     el worker la reclama con FOR UPDATE SKIP LOCKED
 *   REINTENTABLE -> PROCESANDO     idem, cuando vencio el backoff
 *   PROCESANDO   -> ENVIADA        entregada al canal
 *   PROCESANDO   -> REINTENTABLE   fallo transitorio, quedan intentos
 *   PROCESANDO   -> AGOTADA        fallo transitorio, se acabaron los intentos
 *   PROCESANDO   -> FALLIDA        fallo permanente: reintentar no cambiaria el resultado
 *   FALLIDA      -> REINTENTABLE   SOLO por reintento administrativo explicito
 *   AGOTADA      -> REINTENTABLE   idem; reinicia el contador de intentos
 *   ENVIADA      -> nada           terminal
 *   X            -> X              prohibido: no es idempotencia, es un worker en loop
 * </pre>
 *
 * <p><b>Por que PROCESANDO no vuelve sola a PENDIENTE.</b> Una fila que quedo PROCESANDO
 * porque el worker murio se recupera por el lease ({@code procesandoDesde} vencido) y va a
 * REINTENTABLE, no a PENDIENTE: asi conserva el contador de intentos y no puede quedar
 * girando para siempre sin agotarse nunca.
 */
public final class OutboxStateMachine {

	/** Tabla de transiciones. Lo que no esta aca, no se puede. */
	private static final Map<OutboxStatus, Set<OutboxStatus>> TRANSICIONES = construirTabla();

	private OutboxStateMachine() {
		// Clase de utilidad: toda la regla es estatica y sin estado.
	}

	/**
	 * Indica si la transicion esta permitida.
	 *
	 * @param desde estado actual, o {@code null} para el alta
	 * @param hacia estado destino, obligatorio
	 */
	public static boolean isAllowed(OutboxStatus desde, OutboxStatus hacia) {
		if (hacia == null) {
			return false;
		}
		if (desde == null) {
			return hacia == OutboxStatus.ESTADO_INICIAL;
		}
		return TRANSICIONES.get(desde).contains(hacia);
	}

	/**
	 * Igual que {@link #isAllowed}, pero falla en vez de devolver {@code false}.
	 *
	 * <p>Existe para que la validacion y la mutacion queden en la misma linea y nadie pueda
	 * ignorar el resultado por descuido.
	 */
	public static void assertTransitionAllowed(OutboxStatus desde, OutboxStatus hacia) {
		if (!isAllowed(desde, hacia)) {
			throw new InvalidOutboxTransitionException(desde, hacia);
		}
	}

	/** Estados alcanzables desde {@code desde}. */
	public static Set<OutboxStatus> allowedTargets(OutboxStatus desde) {
		if (desde == null) {
			return EnumSet.of(OutboxStatus.ESTADO_INICIAL);
		}
		return TRANSICIONES.get(desde);
	}

	/** Indica si el estado ya no cambia por si solo (solo por accion administrativa). */
	public static boolean esTerminal(OutboxStatus estado) {
		return estado == OutboxStatus.ENVIADA
				|| estado == OutboxStatus.FALLIDA
				|| estado == OutboxStatus.AGOTADA;
	}

	private static Map<OutboxStatus, Set<OutboxStatus>> construirTabla() {
		Map<OutboxStatus, Set<OutboxStatus>> tabla = new EnumMap<>(OutboxStatus.class);
		tabla.put(OutboxStatus.PENDIENTE, inmutable(OutboxStatus.PROCESANDO));
		tabla.put(OutboxStatus.PROCESANDO, inmutable(
				OutboxStatus.ENVIADA,
				OutboxStatus.REINTENTABLE,
				OutboxStatus.FALLIDA,
				OutboxStatus.AGOTADA));
		tabla.put(OutboxStatus.REINTENTABLE, inmutable(OutboxStatus.PROCESANDO));
		tabla.put(OutboxStatus.ENVIADA, inmutable());
		tabla.put(OutboxStatus.FALLIDA, inmutable(OutboxStatus.REINTENTABLE));
		tabla.put(OutboxStatus.AGOTADA, inmutable(OutboxStatus.REINTENTABLE));
		return Collections.unmodifiableMap(tabla);
	}

	private static Set<OutboxStatus> inmutable(OutboxStatus... estados) {
		Set<OutboxStatus> set = EnumSet.noneOf(OutboxStatus.class);
		Collections.addAll(set, estados);
		return Collections.unmodifiableSet(set);
	}
}
