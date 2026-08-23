package com.akine.platform.infrastructure.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Contador de ventana fija, en memoria del proceso.
 *
 * <h2>Por que en memoria, y hasta donde sirve</h2>
 *
 * <p>Con una sola instancia del backend alcanza y no agrega dependencias en el camino critico
 * del login. <b>Con dos instancias detras de un balanceador el limite se duplica</b>, porque
 * cada proceso cuenta lo suyo: un atacante que reparta los intentos obtiene N veces el cupo.
 * <b>TODO(AKINE-01.03 / etapa de observabilidad e infraestructura):</b> mover el contador a un
 * almacenamiento compartido —Redis o equivalente— cuando el despliegue deje de ser de una sola
 * instancia. Es la misma limitacion, y la misma etapa destino, que {@code SecureLinkVault}.
 *
 * <p>Se eligio ventana fija y no token bucket ni ventana deslizante por lo mismo: la ventana
 * fija se implementa en veinte lineas verificables y su peor caso conocido —hasta el doble del
 * cupo a caballo de dos ventanas— es irrelevante para lo que protege, que es la fuerza bruta
 * sostenida, no una rafaga puntual.
 *
 * <h2>Lo que NO hace, y es lo importante</h2>
 *
 * <p>No sabe nada de cuentas, emails ni de si existen. La clave la elige quien lo usa y en el
 * caso de identidad es {@code ruta + IP}, jamas el email presentado. Un limite por email haria
 * del contador un <b>oraculo de existencia</b>: bastaria mirar si el segundo intento sobre una
 * direccion responde distinto para saber si esa direccion esta registrada, que es exactamente lo
 * que ADR-0018 cierra en el resto del flujo. Por construccion, dos claves distintas no se
 * pueden distinguir por su comportamiento.
 */
class FixedWindowRateLimiter {

	/**
	 * Tope de claves vivas. Superado, se descartan las ventanas ya vencidas.
	 *
	 * <p>Sin este corte, un atacante que rote la IP de origen hace crecer el mapa hasta agotar
	 * la memoria: el rate limiter se convertiria en el vector de denegacion de servicio que
	 * pretende evitar.
	 */
	private static final int MAXIMO_DE_CLAVES = 50_000;

	private final Map<String, AtomicReference<Ventana>> ventanas = new ConcurrentHashMap<>();
	private final Duration duracion;
	private final int maximo;

	FixedWindowRateLimiter(Duration duracion, int maximo) {
		if (duracion == null || duracion.isZero() || duracion.isNegative()) {
			throw new IllegalArgumentException("La ventana del rate limit debe ser positiva");
		}
		if (maximo < 1) {
			throw new IllegalArgumentException(
					"El maximo del rate limit debe ser al menos 1: en cero nadie podria "
							+ "iniciar sesion nunca");
		}
		this.duracion = duracion;
		this.maximo = maximo;
	}

	/**
	 * Consume un intento.
	 *
	 * @return {@code true} si el intento entra en el cupo; {@code false} si hay que rechazarlo
	 */
	boolean permitir(String clave, Instant ahora) {
		if (ventanas.size() > MAXIMO_DE_CLAVES) {
			purgar(ahora);
		}

		AtomicReference<Ventana> referencia =
				ventanas.computeIfAbsent(clave, ignorada -> new AtomicReference<>(null));

		Ventana resultado = referencia.updateAndGet(actual -> {
			if (actual == null || !ahora.isBefore(actual.inicio().plus(duracion))) {
				return new Ventana(ahora, 1);
			}
			// Se sigue contando por encima del maximo a proposito: quien insiste mientras esta
			// limitado no consigue que la ventana se reinicie antes.
			return new Ventana(actual.inicio(), actual.intentos() + 1);
		});

		return resultado.intentos() <= maximo;
	}

	/** Cuantos segundos faltan para que la clave vuelva a tener cupo. Cero si ya lo tiene. */
	long segundosParaReintentar(String clave, Instant ahora) {
		AtomicReference<Ventana> referencia = ventanas.get(clave);
		Ventana actual = referencia == null ? null : referencia.get();
		if (actual == null) {
			return 0;
		}
		Instant fin = actual.inicio().plus(duracion);
		long restante = Duration.between(ahora, fin).toSeconds();
		return Math.max(restante, 0);
	}

	private void purgar(Instant ahora) {
		ventanas.entrySet().removeIf(entrada -> {
			Ventana ventana = entrada.getValue().get();
			return ventana == null || !ahora.isBefore(ventana.inicio().plus(duracion));
		});
	}

	/** Ventana inmutable: se reemplaza entera, asi el conteo es atomico sin bloquear. */
	private record Ventana(Instant inicio, int intentos) {
	}
}
