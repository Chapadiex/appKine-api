package com.akine.contracting.application;

import java.util.List;

/**
 * Resultado de adoptar un financiador del catalogo global (DP-43).
 *
 * @param creado {@code true} si esta llamada creo la copia; {@code false} si el centro ya tenia
 *               una vigente y se devuelve esa, sin tocar nada (idempotencia)
 */
public record AdopcionView(FinanciadorView financiador, List<PlanCoberturaView> planes, boolean creado) {
}
