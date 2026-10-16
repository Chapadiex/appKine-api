package com.akine.activity.application;

/**
 * Un participante dentro de un lote de atencion grupal (AKINE-08.05).
 *
 * @param derivacionId la derivacion elegida, o {@code null} si el participante tiene una sola
 *                     vigente
 */
public record PedidoDeAtencionDeParticipante(long inscripcionId, Long derivacionId) {
}
