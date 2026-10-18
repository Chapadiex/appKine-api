package com.akine.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reparte un importe entre partes con pesos, al centavo, y <b>la suma de las partes es exactamente
 * el importe</b> (DP-39, caja por servicio).
 *
 * <p>Metodo del mayor resto: cada parte recibe el piso de su cuota exacta en centavos, y los
 * centavos que sobran van, de a uno, a las partes con mayor resto. Los empates se resuelven por el
 * orden en que llegaron los pesos, que el llamador fija: el mismo pedido da siempre el mismo
 * reparto, y un reporte que se recalcula no mueve un centavo de una fila a otra.
 *
 * <p>Por que no {@code importe * peso / total} redondeado parte por parte: con tres partes iguales
 * de {@code 100.00} sale {@code 33.33 * 3 = 99.99}, y el centavo que falta es un faltante de caja
 * que no existe.
 *
 * <p>Se trabaja en centavos enteros ({@code long}), no en {@code BigDecimal} con escala: la cuota
 * exacta de cada parte es un racional, y comparar restos enteros es exacto donde comparar
 * decimales redondeados no lo es.
 */
public final class ProrrateoAlCentavo {

	private ProrrateoAlCentavo() {
	}

	/**
	 * @param importe lo que se reparte, con a lo sumo dos decimales; puede ser cero, no negativo
	 * @param pesos   peso de cada parte, en el orden de desempate; ninguno negativo y al menos uno
	 *                positivo. Una parte de peso cero recibe cero
	 * @return la parte de cada clave, con escala 2, en el mismo orden; suma exactamente {@code importe}
	 */
	public static <K> Map<K, BigDecimal> repartir(BigDecimal importe, Map<K, BigDecimal> pesos) {
		if (importe == null || importe.signum() < 0) {
			throw new IllegalArgumentException("Solo se reparte un importe no negativo: " + importe);
		}
		long totalCentavos = centavos(importe);

		List<K> claves = new ArrayList<>(pesos.keySet());
		long[] peso = new long[claves.size()];
		long sumaPesos = 0;
		for (int i = 0; i < claves.size(); i++) {
			peso[i] = centavos(pesos.get(claves.get(i)));
			if (peso[i] < 0) {
				throw new IllegalArgumentException("Un peso negativo no reparte nada: " + claves.get(i));
			}
			sumaPesos = Math.addExact(sumaPesos, peso[i]);
		}
		if (sumaPesos == 0) {
			throw new IllegalArgumentException("Sin ningun peso positivo no hay a quien repartir");
		}

		long[] parte = new long[claves.size()];
		long[] resto = new long[claves.size()];
		long asignado = 0;
		for (int i = 0; i < claves.size(); i++) {
			// importe y pesos caben en DECIMAL(12,2): el producto cabe holgado en un long.
			long producto = Math.multiplyExact(totalCentavos, peso[i]);
			parte[i] = producto / sumaPesos;
			resto[i] = producto % sumaPesos;
			asignado += parte[i];
		}

		List<Integer> porResto = new ArrayList<>();
		for (int i = 0; i < claves.size(); i++) {
			porResto.add(i);
		}
		// Orden estable: a igual resto, conserva el orden de los pesos.
		porResto.sort(Comparator.comparingLong((Integer i) -> resto[i]).reversed());
		for (int k = 0; asignado < totalCentavos; k++) {
			parte[porResto.get(k)]++;
			asignado++;
		}

		Map<K, BigDecimal> resultado = new LinkedHashMap<>();
		for (int i = 0; i < claves.size(); i++) {
			resultado.put(claves.get(i), BigDecimal.valueOf(parte[i], 2));
		}
		return resultado;
	}

	private static long centavos(BigDecimal valor) {
		// setScale sin redondeo: un importe con mas de dos decimales es un error del llamador, y
		// redondearlo en silencio romperia la suma exacta que esta clase promete.
		return valor.setScale(2, RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
	}
}
