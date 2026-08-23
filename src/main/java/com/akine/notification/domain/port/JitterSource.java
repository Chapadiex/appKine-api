package com.akine.notification.domain.port;

/**
 * La fuente de aleatoriedad del backoff, como puerto.
 *
 * <p>Existe por la misma razon que {@link NotificationClock}. {@code RetryBackoffPolicy} se
 * diseño pura a proposito —recibe el jitter por parametro— para que la espera de cada intento
 * sea un valor exacto y verificable en un test. Resolver ese parametro con un
 * {@code ThreadLocalRandom} incrustado en el servicio anula esa decision: el test deja de poder
 * afirmar "el tercer intento espera 64 segundos" y solo puede afirmar un rango, que es
 * justamente lo que la politica pura venia a evitar.
 *
 * <p>Es un puerto propio y no {@code java.util.random.RandomGenerator} para no competir por un
 * bean de un tipo de la JDK que otro modulo podria querer definir con otra semantica.
 */
@FunctionalInterface
public interface JitterSource {

	/**
	 * Un valor en {@code [0.0, 1.0)}, con la misma forma que espera
	 * {@code RetryBackoffPolicy.proximaEspera}.
	 */
	double next();
}
