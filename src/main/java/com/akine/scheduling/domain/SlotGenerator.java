package com.akine.scheduling.domain;



import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Corta franjas de atencion en slots reservables. Puro, sin estado y sin dependencias.
 *
 * <h2>De donde arranca la grilla, y por que importa que sea de la franja</h2>
 *
 * <p>Cada franja se corta <b>desde su propio inicio</b>, no desde una grilla global anclada a la
 * medianoche ni a una hora de referencia de la sede. Una franja 09:00–11:30 con oferta de 45
 * minutos da 09:00, 09:45 y 10:30; los ultimos 15 minutos no alcanzan para un slot y se
 * descartan.
 *
 * <p>La alternativa —grilla global— fue descartada: con ella una apertura excepcional a las 09:20
 * perderia sus primeros 25 minutos para alinearse a las 09:45, y el administrador que cargo esa
 * apertura no tendria forma de entender por que. Anclar en la franja hace que <b>lo que el
 * usuario cargo sea exactamente lo que se ofrece</b>.
 *
 * <p>Consecuencia aceptada: dos franjas del mismo dia pueden producir slots que no comparten
 * grilla —09:00 y 09:45 en la manana, 14:10 y 14:55 en la tarde si la tarde arranca 14:10—. Es
 * correcto: son dos tramos de atencion distintos y no hay ninguna regla que los obligue a
 * alinearse entre si.
 *
 * <h2>El criterio de aceptacion es el determinismo</h2>
 *
 * <p>"Para entradas iguales retorna slots deterministas." Esta clase no consulta el reloj, no
 * itera sobre estructuras sin orden y no depende de nada externo: para la misma franja y la misma
 * duracion produce siempre la misma lista, en el mismo orden. Todo lo que pueda variar entre dos
 * corridas —el instante actual, el estado de la base— queda del lado del servicio.
 *
 * <h2>El fin de dia</h2>
 *
 * <p>{@link TramoLocal#FIN_DE_DIA} es {@code LocalTime.MAX} —23:59:59.999999999— y
 * <b>no admite sumas</b>: {@code LocalTime.plus} da la vuelta al reloj en silencio, asi que
 * avanzar el cursor sobre una franja que llega a la medianoche produciria slots de la madrugada
 * del mismo dia. El avance se hace midiendo contra el fin de la franja antes de sumar, y el
 * ultimo slot de una franja que cierra a medianoche termina exactamente en {@code FIN_DE_DIA}.
 */
public final class SlotGenerator {

	private SlotGenerator() {
	}

	/**
	 * Corta una franja en slots consecutivos de {@code duracion}.
	 *
	 * <p>El resto que no completa un slot se descarta: media consulta no es reservable. Una franja
	 * mas corta que la duracion no produce ningun slot, y eso <b>no es un error</b> —es un bloque
	 * de 30 minutos con una oferta de 45— asi que devuelve lista vacia.
	 *
	 * @param duracion duracion de la oferta, estrictamente positiva
	 */
	public static List<TramoLocal> cortar(TramoLocal franja, Duration duracion) {
		if (duracion.isZero() || duracion.isNegative()) {
			throw new IllegalArgumentException("La duracion de un slot es positiva: " + duracion);
		}

		List<TramoLocal> slots = new ArrayList<>();
		LocalTime cursor = franja.desde();

		while (true) {
			// Se mide ANTES de sumar. LocalTime.plus da la vuelta al reloj sin avisar, asi que
			// calcular el fin y despues comparar produciria slots de la madrugada en cuanto la
			// franja llegue cerca de la medianoche.
			long disponible = restanteEn(cursor, franja.hasta());
			if (disponible < duracion.toNanos()) {
				return slots;
			}
			LocalTime fin = avanzar(cursor, duracion);
			slots.add(new TramoLocal(cursor, fin));
			cursor = fin;
		}
	}

	/**
	/**
	 * Avanza el cursor sin dar la vuelta al reloj.
	 *
	 * <p>{@code LocalTime.plus} <b>da la vuelta en silencio</b>: 23:30 mas 30 minutos devuelve
	 * 00:00 del mismo dia, que es ANTERIOR al cursor. Con eso, el ultimo slot de toda franja que
	 * cierre a medianoche sale invertido y {@link TramoLocal} lanza — o peor, si el tipo no
	 * validara, quedaria un slot de menos veinticuatro horas.
	 *
	 * <p>Solo puede desbordar por el borde exacto de la medianoche, porque
	 * {@link #restanteEn} ya garantizo que la duracion entra en lo que queda de la franja. En ese
	 * caso el fin es {@link TramoLocal#FIN_DE_DIA}, que es como se expresan las 24:00.
	 */
	private static LocalTime avanzar(LocalTime cursor, Duration duracion) {
		long fin = cursor.toNanoOfDay() + duracion.toNanos();
		return fin > TramoLocal.FIN_DE_DIA.toNanoOfDay()
				? TramoLocal.FIN_DE_DIA
				: LocalTime.ofNanoOfDay(fin);
	}

	/**
	 * Nanosegundos entre {@code cursor} y {@code hasta}, tolerando el fin de dia.
	 *
	 * <p>{@code FIN_DE_DIA} vale un nanosegundo menos que la medianoche real, asi que restarlo
	 * literal deja fuera el ultimo slot de cualquier franja que cierre a las 24:00 —y nadie lo
	 * nota, porque la diferencia no se ve en ninguna pantalla—. Se le suma ese nanosegundo de
	 * vuelta, que es lo que "hasta la medianoche" significa.
	 */
	private static long restanteEn(LocalTime cursor, LocalTime hasta) {
		long fin = hasta.toNanoOfDay();
		if (TramoLocal.FIN_DE_DIA.equals(hasta)) {
			fin += 1;
		}
		return fin - cursor.toNanoOfDay();
	}
}
