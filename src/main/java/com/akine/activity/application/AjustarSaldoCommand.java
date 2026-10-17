package com.akine.activity.application;

/**
 * Ajuste manual del saldo de un pase (RF-M29-005).
 *
 * @param cantidad creditos con signo: positivo suma, negativo resta. Nunca cero
 * @param motivo   obligatorio (CA-M29-005-06)
 * @param version  la version del pase que el cliente leyo
 */
public record AjustarSaldoCommand(int cantidad, String motivo, long version) {
}
