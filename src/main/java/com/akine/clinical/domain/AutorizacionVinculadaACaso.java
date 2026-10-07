package com.akine.clinical.domain;

/**
 * Una autorizacion de M17 atada a un item de plan de un caso (RF-M11-007), proyectada para
 * responder "de que caso es esta autorizacion" (RF-M17-007, AKINE C-4).
 */
public record AutorizacionVinculadaACaso(Long autorizacionId, Long casoId) {
}
