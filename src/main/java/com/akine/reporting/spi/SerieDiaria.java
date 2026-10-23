package com.akine.reporting.spi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * La serie por dia de un indicador de la seccion (DP-45): lo que dibuja el grafico de barras.
 *
 * <h2>Misma fuente y mismo criterio de fecha que el indicador, o no es su serie</h2>
 *
 * <p>{@code clave} es la del {@link IndicadorDeReporte} que la serie abre por dia, y el
 * contribuyente la calcula con <b>el mismo recorte</b> —mismas tablas, mismos estados, misma
 * columna de fecha, mismo recorte por actividad propia—. La suma de todos los puntos es el valor
 * del indicador sobre el mismo periodo; si no lo fuera, el grafico y la tira de numeros de la misma
 * pantalla dirian dos cosas distintas.
 *
 * <p>Solo se publican series de indicadores <b>sumables</b>: dinero y conteos que ocurren en una
 * fecha. Un porcentaje no se suma por dia y un saldo actual no tiene fecha, asi que ninguno de los
 * dos tiene serie.
 *
 * <p>Los puntos van por <b>dia de la sede</b> y solo los dias con valor: {@code reporting} completa
 * los ceros y agrupa por semana.
 */
public record SerieDiaria(
		String clave,
		String etiqueta,
		IndicadorDeReporte.Tipo tipo,
		String moneda,
		String fuente,
		String criterioDeFecha,
		Map<LocalDate, BigDecimal> porDia) {

	public SerieDiaria {
		if (clave == null || clave.isBlank()) {
			throw new IllegalArgumentException("Una serie necesita la clave de su indicador");
		}
		if (tipo == IndicadorDeReporte.Tipo.PORCENTAJE) {
			throw new IllegalArgumentException("Un porcentaje no se suma por dia: no tiene serie");
		}
		porDia = porDia == null ? Map.of() : Map.copyOf(porDia);
	}

	/**
	 * Junta filas {@code [dia, valor]} de una o varias consultas —una por tramo horario— sumando
	 * las que caen el mismo dia. El dia llega como {@code java.sql.Date} o {@link LocalDate}
	 * segun el driver; los dos se escriben {@code yyyy-MM-dd}.
	 */
	public static final class Acumulador {

		private final Map<LocalDate, BigDecimal> porDia = new TreeMap<>();

		public Acumulador sumar(List<Object[]> filas) {
			for (Object[] fila : filas) {
				sumar(fila[0], fila[1]);
			}
			return this;
		}

		public Acumulador sumar(Object dia, Object valor) {
			if (dia == null || valor == null) {
				return this;
			}
			BigDecimal numero = valor instanceof BigDecimal decimal
					? decimal
					: new BigDecimal(valor.toString());
			porDia.merge(LocalDate.parse(dia.toString().substring(0, 10)), numero, BigDecimal::add);
			return this;
		}

		public Map<LocalDate, BigDecimal> porDia() {
			return Map.copyOf(porDia);
		}
	}
}
