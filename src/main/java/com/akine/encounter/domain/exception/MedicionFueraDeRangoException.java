package com.akine.encounter.domain.exception;

import java.math.BigDecimal;

/**
 * El valor cae fuera del rango que la definicion declara (<b>400</b>).
 *
 * <p><b>400 y no 409.</b> Un EVA de 12 en una escala de 0 a 10 es un problema del CUERPO enviado,
 * no del estado del servidor: reintentar el mismo valor falla igual. Mismo reparto que
 * {@code EvaluacionIncoherenteException} en 06.02.
 *
 * <p><b>El rango se evalua AL REGISTRAR, contra la version vigente en ese momento, y nunca al
 * leer.</b> Si manana el catalogo lo estrecha, las mediciones viejas no se vuelven invalidas:
 * fueron validas cuando se tomaron, y la version contra la que se validaron queda copiada en la
 * fila. Revalidar al leer convertiria un cambio administrativo en una correccion retroactiva de
 * historia clinica.
 *
 * <p>Lleva el rango porque un "fuera de rango" sin numeros es inaccionable: la pantalla tiene que
 * poder decir entre que y que.
 */
public class MedicionFueraDeRangoException extends RuntimeException {

	private final String codigo;
	private final BigDecimal valor;
	private final BigDecimal minimo;
	private final BigDecimal maximo;

	public MedicionFueraDeRangoException(
			String codigo, BigDecimal valor, BigDecimal minimo, BigDecimal maximo) {

		super("El valor " + valor + " esta fuera del rango admitido por la medida " + codigo
				+ " (" + (minimo == null ? "sin minimo" : minimo) + " a "
				+ (maximo == null ? "sin maximo" : maximo) + ")");
		this.codigo = codigo;
		this.valor = valor;
		this.minimo = minimo;
		this.maximo = maximo;
	}

	public String getCodigo() {
		return codigo;
	}

	public BigDecimal getValor() {
		return valor;
	}

	public BigDecimal getMinimo() {
		return minimo;
	}

	public BigDecimal getMaximo() {
		return maximo;
	}
}
