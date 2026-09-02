package com.akine.person.spi;

import java.math.BigDecimal;

/**
 * Un numero del Paciente 360, con su etiqueta ya resuelta por quien lo sabe nombrar.
 *
 * <p><b>El importe es {@link BigDecimal} y no puede ser otra cosa.</b> AGENT.md seccion 5 y la
 * regla que dejo AKINE-07.01: la plata va en {@code DECIMAL} en la base y {@code BigDecimal} en
 * Java, nunca {@code double}. Un resumen no es "solo para mostrar": el numero que la pantalla
 * muestra es el que el operador le dice al paciente, y un centavo perdido en un {@code double} es
 * una discusion en el mostrador.
 *
 * <p>{@code cantidad} e {@code importe} son excluyentes en la practica —un indicador cuenta cosas
 * o suma plata— y los dos son nullable para no obligar a inventar un cero que significa algo
 * distinto de "no aplica".
 *
 * @param clave    identificador estable para la pantalla, por ejemplo {@code turnos-futuros}
 * @param etiqueta texto ya legible; lo escribe el modulo que sabe que esta contando
 * @param moneda   ISO 4217, presente solo cuando hay importe
 */
public record IndicadorDeResumen(
		String clave,
		String etiqueta,
		Long cantidad,
		BigDecimal importe,
		String moneda) {

	public static IndicadorDeResumen contando(String clave, String etiqueta, long cantidad) {
		return new IndicadorDeResumen(clave, etiqueta, cantidad, null, null);
	}

	public static IndicadorDeResumen dinero(
			String clave, String etiqueta, BigDecimal importe, String moneda) {
		return new IndicadorDeResumen(clave, etiqueta, null, importe, moneda);
	}
}
