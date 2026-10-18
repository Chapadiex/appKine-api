package com.akine.activity.application;

import java.util.List;

/**
 * Lo que {@code clinical} sabe de una participacion: si la persona tiene historia abierta y a que
 * Casos fue derivada, vigentes y revertidas, mas nueva primero.
 *
 * @param historiaClinicaId {@code null} si todavia no tiene historia: la derivacion la abre
 */
public record EstadoDeDerivacionView(Long historiaClinicaId, List<DerivacionView> derivaciones) {
}
