package com.akine.activity.application;

/**
 * Lo que dejo la anulacion de un pase: el pase {@code ANULADO} y su deuda, tambien anulada.
 *
 * @param deuda   la obligacion de la venta despues de anularla; {@code null} si la venta no tenia
 * @param anulado {@code false} si el pase ya estaba anulado y no se escribio nada
 */
public record ResultadoDeAnulacion(PaseView pase, ResultadoDeVenta.Deuda deuda, boolean anulado) {
}
