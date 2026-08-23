package com.akine.organization.domain;

import com.akine.organization.domain.exception.InvalidSubscriptionTransitionException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Transiciones validas del estado de una suscripcion (RF-M01-003).
 *
 * <p>Es una clase PURA: sin Spring, sin base de datos, sin reloj. Toda la regla es la tabla
 * de abajo, y por eso se puede testear exhaustivamente sin levantar un contexto. Si esta
 * clase necesitara una dependencia, la regla estaria mal ubicada.
 *
 * <pre>
 *   (alta)     -> ACTIVA        unica forma de nacer
 *   ACTIVA     -> SUSPENDIDA    motivo obligatorio (lo exige la capa application)
 *   ACTIVA     -> CANCELADA     motivo obligatorio
 *   SUSPENDIDA -> ACTIVA        restaura la operacion completa
 *   SUSPENDIDA -> CANCELADA     motivo obligatorio
 *   CANCELADA  -> nada          terminal
 *   X          -> X             prohibido: una transicion vacia no es idempotencia, es un
 *                               segundo efecto y una segunda fila de historico
 * </pre>
 *
 * <p>Cambiar de plan NO pasa por aca: no es una transicion de estado sino una operacion
 * aparte, permitida solo con la suscripcion {@link SubscriptionStatus#ACTIVA}.
 */
public final class SubscriptionStateMachine {

	/** Tabla de transiciones. Lo que no esta aca, no se puede. */
	private static final Map<SubscriptionStatus, Set<SubscriptionStatus>> TRANSICIONES =
			construirTabla();

	/** Estado en el que nace toda suscripcion. */
	public static final SubscriptionStatus ESTADO_INICIAL = SubscriptionStatus.ACTIVA;

	private SubscriptionStateMachine() {
		// Clase de utilidad: toda la regla es estatica y sin estado.
	}

	/**
	 * Indica si la transicion esta permitida.
	 *
	 * @param from estado actual, o {@code null} para el alta inicial de la suscripcion
	 * @param to   estado destino, obligatorio
	 */
	public static boolean isAllowed(SubscriptionStatus from, SubscriptionStatus to) {
		if (to == null) {
			return false;
		}
		if (from == null) {
			return to == ESTADO_INICIAL;
		}
		return TRANSICIONES.get(from).contains(to);
	}

	/**
	 * Igual que {@link #isAllowed}, pero falla en vez de devolver {@code false}.
	 *
	 * <p>Existe para que el llamador no pueda ignorar el resultado por descuido: la mutacion
	 * y la validacion quedan en la misma linea de codigo.
	 *
	 * @throws InvalidSubscriptionTransitionException si la transicion no esta en la tabla
	 */
	public static void assertTransitionAllowed(SubscriptionStatus from, SubscriptionStatus to) {
		if (!isAllowed(from, to)) {
			throw new InvalidSubscriptionTransitionException(from, to);
		}
	}

	/**
	 * Estados destino a los que se puede llegar desde {@code from}.
	 *
	 * <p>La usa la capa de aplicacion para exponer las acciones posibles sin duplicar la
	 * tabla de transiciones en la UI (RNF-M01-008: la regla vive en el backend).
	 */
	public static Set<SubscriptionStatus> allowedTargets(SubscriptionStatus from) {
		if (from == null) {
			return EnumSet.of(ESTADO_INICIAL);
		}
		return TRANSICIONES.get(from);
	}

	/**
	 * Indica si la transicion exige un motivo declarado por el actor.
	 *
	 * <p>Suspender y cancelar afectan la operacion de un cliente: sin motivo, el historico no
	 * sirve para responder "por que dejo de funcionar" seis meses despues.
	 */
	public static boolean requiresReason(SubscriptionStatus to) {
		return to == SubscriptionStatus.SUSPENDIDA || to == SubscriptionStatus.CANCELADA;
	}

	private static Map<SubscriptionStatus, Set<SubscriptionStatus>> construirTabla() {
		Map<SubscriptionStatus, Set<SubscriptionStatus>> tabla =
				new EnumMap<>(SubscriptionStatus.class);
		tabla.put(SubscriptionStatus.ACTIVA,
				Collections.unmodifiableSet(EnumSet.of(
						SubscriptionStatus.SUSPENDIDA, SubscriptionStatus.CANCELADA)));
		tabla.put(SubscriptionStatus.SUSPENDIDA,
				Collections.unmodifiableSet(EnumSet.of(
						SubscriptionStatus.ACTIVA, SubscriptionStatus.CANCELADA)));
		tabla.put(SubscriptionStatus.CANCELADA,
				Collections.unmodifiableSet(EnumSet.noneOf(SubscriptionStatus.class)));
		return Collections.unmodifiableMap(tabla);
	}
}
