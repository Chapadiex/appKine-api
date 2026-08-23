package com.akine.identity.domain;

import com.akine.identity.domain.exception.InvalidAccountTransitionException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Transiciones validas del estado de una cuenta (RF-M02-005).
 *
 * <p>Clase PURA: sin Spring, sin base de datos, sin reloj. Toda la regla es la tabla de
 * abajo y por eso se puede recorrer exhaustivamente en un test sin levantar un contexto. Si
 * necesitara una dependencia, la regla estaria mal ubicada.
 *
 * <pre>
 *   (alta)               -> PENDIENTE_ACTIVACION   unica forma de nacer
 *   PENDIENTE_ACTIVACION -> ACTIVA                 la persona confirmo el enlace
 *   PENDIENTE_ACTIVACION -> DESACTIVADA            baja de una invitacion que nunca se uso
 *   ACTIVA               -> BLOQUEADA              suspension administrativa, con motivo
 *   ACTIVA               -> DESACTIVADA            baja logica
 *   BLOQUEADA            -> ACTIVA                 desbloqueo: BLOQUEADA es reversible
 *   BLOQUEADA            -> DESACTIVADA            baja logica de una cuenta suspendida
 *   DESACTIVADA          -> nada                   terminal
 *   X                    -> X                      prohibido: repetir un estado no es
 *                                                  idempotencia, es un segundo efecto
 *                                                  administrativo y una segunda fila de
 *                                                  auditoria que dice algo que no paso
 * </pre>
 *
 * <p>Que {@code BLOQUEADA} sea reversible y {@code DESACTIVADA} terminal es la distincion
 * entera entre las dos acciones de RF-M02-005: si desactivar pudiera deshacerse desde la
 * aplicacion, seria un bloqueo con otro nombre y la baja logica no significaria nada.
 */
public final class AccountStateMachine {

	/** Estado en el que nace toda cuenta, venga del alta self-service o de una invitacion. */
	public static final EstadoCuenta ESTADO_INICIAL = EstadoCuenta.PENDIENTE_ACTIVACION;

	/** Tabla de transiciones. Lo que no esta aca, no se puede. */
	private static final Map<EstadoCuenta, Set<EstadoCuenta>> TRANSICIONES = construirTabla();

	private AccountStateMachine() {
		// Clase de utilidad: toda la regla es estatica y sin estado.
	}

	/**
	 * Indica si la transicion esta permitida.
	 *
	 * @param desde estado actual, o {@code null} para el alta
	 * @param hacia estado destino, obligatorio
	 */
	public static boolean isAllowed(EstadoCuenta desde, EstadoCuenta hacia) {
		if (hacia == null) {
			return false;
		}
		if (desde == null) {
			return hacia == ESTADO_INICIAL;
		}
		return TRANSICIONES.get(desde).contains(hacia);
	}

	/**
	 * Igual que {@link #isAllowed}, pero falla en vez de devolver {@code false}.
	 *
	 * <p>Existe para que el llamador no pueda ignorar el resultado por descuido: la
	 * validacion y la mutacion quedan en la misma linea de codigo.
	 *
	 * @throws InvalidAccountTransitionException si la transicion no esta en la tabla
	 */
	public static void assertTransitionAllowed(EstadoCuenta desde, EstadoCuenta hacia) {
		if (!isAllowed(desde, hacia)) {
			throw new InvalidAccountTransitionException(desde, hacia);
		}
	}

	/**
	 * Estados a los que se puede llegar desde {@code desde}.
	 *
	 * <p>La usa la capa de aplicacion para exponer las acciones posibles sin que la UI
	 * duplique la tabla: la regla vive en el backend (RN-M02-001).
	 */
	public static Set<EstadoCuenta> allowedTargets(EstadoCuenta desde) {
		if (desde == null) {
			return EnumSet.of(ESTADO_INICIAL);
		}
		return TRANSICIONES.get(desde);
	}

	/**
	 * Indica si la transicion exige un motivo declarado por el administrador.
	 *
	 * <p>Bloquear y desactivar le sacan el acceso a una persona: sin motivo, la auditoria no
	 * sirve para responder "por que me quede afuera" seis meses despues.
	 */
	public static boolean requiresReason(EstadoCuenta hacia) {
		return hacia == EstadoCuenta.BLOQUEADA || hacia == EstadoCuenta.DESACTIVADA;
	}

	/**
	 * Indica si el estado corta toda sesion viva al entrar en el.
	 *
	 * <p>Bloqueo y desactivacion revocan los refresh de la cuenta dentro de la misma
	 * transaccion de la transicion. El access token en vuelo sigue siendo criptograficamente
	 * valido hasta su TTL (&le; 10 min): es una ventana aceptada y documentada, no un olvido.
	 */
	public static boolean revokesSessions(EstadoCuenta hacia) {
		return hacia == EstadoCuenta.BLOQUEADA || hacia == EstadoCuenta.DESACTIVADA;
	}

	private static Map<EstadoCuenta, Set<EstadoCuenta>> construirTabla() {
		Map<EstadoCuenta, Set<EstadoCuenta>> tabla = new EnumMap<>(EstadoCuenta.class);
		tabla.put(EstadoCuenta.PENDIENTE_ACTIVACION,
				Collections.unmodifiableSet(EnumSet.of(
						EstadoCuenta.ACTIVA, EstadoCuenta.DESACTIVADA)));
		tabla.put(EstadoCuenta.ACTIVA,
				Collections.unmodifiableSet(EnumSet.of(
						EstadoCuenta.BLOQUEADA, EstadoCuenta.DESACTIVADA)));
		tabla.put(EstadoCuenta.BLOQUEADA,
				Collections.unmodifiableSet(EnumSet.of(
						EstadoCuenta.ACTIVA, EstadoCuenta.DESACTIVADA)));
		tabla.put(EstadoCuenta.DESACTIVADA,
				Collections.unmodifiableSet(EnumSet.noneOf(EstadoCuenta.class)));
		return Collections.unmodifiableMap(tabla);
	}
}
