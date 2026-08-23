package com.akine.notification.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Politica de reintentos del worker: cuantas veces y con cuanta espera.
 *
 * <p>La spec NO fija cantidad ni backoff (digest seccion 7): lo unico normativo es que se
 * pueda reprocesar sin duplicar efectos de negocio. La politica es entonces decision de
 * diseño, y esta es la elegida: <b>exponencial acotada</b>, 1 / 5 / 15 / 60 / 180 minutos,
 * con jitter de +-20 %.
 *
 * <ul>
 *   <li><b>Acotada, no infinita.</b> Un backoff que dobla para siempre termina reintentando
 *       cada varios dias una notificacion que ya no le importa a nadie, y mantiene viva una
 *       fila que deberia estar cerrada. El techo de 180 minutos deja el total en ~4,3 h.</li>
 *   <li><b>Con jitter.</b> Sin el, mil notificaciones encoladas durante una caida del SMTP
 *       vuelven todas juntas en el mismo tick cuando el servicio se recupera, y lo tumban de
 *       nuevo. El jitter las desparrama.</li>
 *   <li><b>El total importa contra el TTL del token.</b> ~4,3 h entra comodo en los 7 dias de
 *       un token de activacion. Un reset (TTL corto) puede agotarse antes de entregarse: es
 *       aceptable, la persona pide otro. Documentado a proposito.</li>
 * </ul>
 *
 * <p>Clase PURA: sin Spring, sin reloj, sin random propio. El jitter entra por parametro para
 * que el comportamiento sea reproducible en los tests; quien llama decide de donde sale.
 */
public final class RetryBackoffPolicy {

	/** Escalones por defecto, en minutos. */
	private static final List<Duration> ESCALONES_POR_DEFECTO = List.of(
			Duration.ofMinutes(1),
			Duration.ofMinutes(5),
			Duration.ofMinutes(15),
			Duration.ofMinutes(60),
			Duration.ofMinutes(180));

	/**
	 * Amplitud del jitter: +-20 % del escalon.
	 *
	 * <p>Se declara como entero, y no como {@code double}, porque la regla de arquitectura
	 * prohibe campos de punto flotante en {@code domain}. La regla nacio pensando en importes
	 * -donde el error de redondeo termina descuadrando la caja- y aca se trata de milisegundos
	 * de espera, pero se respeta igual: el porcentaje se expresa perfectamente con un entero y
	 * la aritmetica del calculo no cambia. Adaptar el codigo salio mas barato que discutir la
	 * regla, y deja una excepcion menos que explicar.
	 */
	private static final int AMPLITUD_JITTER_PORCENTAJE = 20;

	private final List<Duration> escalones;
	private final int maxIntentos;

	public RetryBackoffPolicy(List<Duration> escalones, int maxIntentos) {
		if (escalones == null || escalones.isEmpty()) {
			throw new IllegalArgumentException("La politica de reintentos necesita al menos un escalon");
		}
		if (maxIntentos < 1) {
			throw new IllegalArgumentException("maxIntentos debe ser al menos 1");
		}
		this.escalones = List.copyOf(escalones);
		this.maxIntentos = maxIntentos;
	}

	/** Politica por defecto: 1, 5, 15, 60, 180 minutos y 5 intentos. */
	public static RetryBackoffPolicy porDefecto() {
		return new RetryBackoffPolicy(ESCALONES_POR_DEFECTO, ESCALONES_POR_DEFECTO.size());
	}

	public int maxIntentos() {
		return maxIntentos;
	}

	public List<Duration> escalones() {
		return escalones;
	}

	/**
	 * Espera antes del proximo intento, o {@link Optional#empty()} si ya no quedan.
	 *
	 * <p>Vacio significa AGOTADA: es la unica fuente de verdad sobre el agotamiento, para que
	 * la condicion no quede duplicada —y desincronizada— entre el worker y la politica.
	 *
	 * @param intentosRealizados intentos ya consumidos, incluido el que acaba de fallar
	 * @param jitter             valor en {@code [0,1)}; 0,5 es "sin desvio"
	 */
	public Optional<Duration> proximaEspera(int intentosRealizados, double jitter) {
		if (intentosRealizados >= maxIntentos) {
			return Optional.empty();
		}
		int indice = Math.min(Math.max(intentosRealizados, 1), escalones.size()) - 1;
		Duration base = escalones.get(indice);
		double factor = 1 + (AMPLITUD_JITTER_PORCENTAJE / 100d) * (2 * acotar(jitter) - 1);
		long millis = Math.max(1, Math.round(base.toMillis() * factor));
		return Optional.of(Duration.ofMillis(millis));
	}

	/** Indica si, tras {@code intentosRealizados}, la notificacion queda agotada. */
	public boolean agotada(int intentosRealizados) {
		return proximaEspera(intentosRealizados, 0.5).isEmpty();
	}

	private static double acotar(double jitter) {
		if (jitter < 0) {
			return 0;
		}
		return Math.min(jitter, 1);
	}
}
