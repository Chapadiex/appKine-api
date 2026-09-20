package com.akine.person.application;

/**
 * Lo que hace falta para revertir un consumo (RF-M17-005).
 *
 * <p>{@code movimientoId} y no "el ultimo": revertir el consumo equivocado es peor que no revertir
 * ninguno, y "el ultimo" deja de significar lo mismo en cuanto dos cierres del mismo dia se
 * ordenan distinto de lo que el operador recuerda.
 *
 * <p>{@code motivo} es <b>obligatorio</b> y esa es la regla de la etapa. Sin el, quien audite no
 * puede distinguir un error de carga de un fraude. Falta de motivo es <b>400</b>, no 409: no hay
 * ningun estado del sistema que impida la operacion, falta un dato del pedido.
 */
public record ReversionCommand(long movimientoId, String motivo) {
}
