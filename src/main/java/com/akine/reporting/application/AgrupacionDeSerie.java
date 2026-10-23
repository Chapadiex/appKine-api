package com.akine.reporting.application;

/**
 * Como se agrupan los dias de una serie de reporte (DP-45).
 *
 * <p>{@code SEMANA} es la semana ISO, de lunes a domingo, en la zona de la sede. La primera y la
 * ultima semana se recortan al periodo pedido: un punto nunca cuenta dias fuera de el, y cada
 * punto declara sus dias.
 */
public enum AgrupacionDeSerie {
	DIA,
	SEMANA
}
