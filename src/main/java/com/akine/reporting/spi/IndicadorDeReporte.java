package com.akine.reporting.spi;

import java.math.BigDecimal;

/**
 * Un numero del reporte, con todo lo que hace falta para saber que significa.
 *
 * <h2>{@code fuente} y {@code criterioDeFecha} no son decoracion</h2>
 *
 * <p>RF-M23 lo pide con todas las letras: <i>"cada metrica define fuente, zona horaria, moneda,
 * estados incluidos y fecha de corte"</i>. Y la razon practica es mas dura que el requisito:
 * <b>deuda, cobro, caja, presentacion y egreso son cinco conceptos distintos que se muestran como
 * cinco numeros parecidos</b>. Sin la fuente al lado, el primero que vea dos de ellos va a
 * intentar sumarlos, y sumar un cobro con un movimiento de caja cuenta la misma plata dos veces.
 *
 * <h2>{@code BigDecimal}, nunca {@code double}</h2>
 *
 * <p>Regla de AKINE-07.01 y de AGENT.md seccion 5. Un total que se muestra en pantalla es el que
 * el operador le dice al paciente: no existe plata "solo para mostrar". {@code CodingConventionsTest}
 * lo verifica en {@code domain}; aca se sostiene por la misma razon.
 *
 * @param clave           identificador estable; viaja al cliente y renombrarlo rompe contrato
 * @param etiqueta        como se lee en pantalla
 * @param valor           el numero. Nunca {@code null}: un indicador sin dato es cero explicito
 * @param moneda          ISO-4217 para {@link Tipo#DINERO}, {@code null} en los demas
 * @param fuente          de que tabla y modulo sale: {@code "M18 obligacion"}
 * @param criterioDeFecha con que columna se recorto el periodo, y si es instante o fecha local
 */
public record IndicadorDeReporte(
		String clave,
		String etiqueta,
		Tipo tipo,
		BigDecimal valor,
		String moneda,
		String fuente,
		String criterioDeFecha) {

	/** Que clase de numero es. La pantalla formatea segun esto, no adivinando por el valor. */
	public enum Tipo {
		DINERO, CONTEO, PORCENTAJE
	}

	public IndicadorDeReporte {
		if (clave == null || clave.isBlank()) {
			throw new IllegalArgumentException("Un indicador necesita clave");
		}
		if (valor == null) {
			throw new IllegalArgumentException(
					"Un indicador sin valor seria un cero silencioso: usar BigDecimal.ZERO");
		}
	}

	public static IndicadorDeReporte dinero(
			String clave, String etiqueta, BigDecimal valor, String moneda,
			String fuente, String criterioDeFecha) {

		return new IndicadorDeReporte(
				clave, etiqueta, Tipo.DINERO, valor, moneda, fuente, criterioDeFecha);
	}

	public static IndicadorDeReporte contando(
			String clave, String etiqueta, long valor, String fuente, String criterioDeFecha) {

		return new IndicadorDeReporte(
				clave, etiqueta, Tipo.CONTEO, BigDecimal.valueOf(valor), null,
				fuente, criterioDeFecha);
	}

	public static IndicadorDeReporte porcentaje(
			String clave, String etiqueta, BigDecimal valor, String fuente, String criterioDeFecha) {

		return new IndicadorDeReporte(
				clave, etiqueta, Tipo.PORCENTAJE, valor, null, fuente, criterioDeFecha);
	}
}
