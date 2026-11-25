package com.akine.billing.domain.port;

import java.math.BigDecimal;

/**
 * Una persona que debe algo como paciente en la organizacion: la suma de los saldos de sus deudas
 * abiertas (no anuladas, saldo positivo) y cuantas son. La parte del financiador no cuenta.
 */
public record DeudorDelPaciente(long personaId, BigDecimal totalAdeudado, long deudas, String moneda) {
}
