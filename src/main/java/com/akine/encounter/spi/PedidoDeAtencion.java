package com.akine.encounter.spi;

/**
 * El pedido de abrir la atencion clinica de un participante de una clase (AKINE-08.05,
 * RF-M14-012).
 *
 * <p>Lo arma el modulo duenio de la clase despues de validar <b>lo grupal</b>: la clase y la
 * inscripcion son de la sede, la oferta genera registro clinico y la persona <b>estuvo</b>. Lo
 * clinico —derivacion vigente, Caso activo, permiso, relacion asistencial o justificacion— lo
 * valida {@code encounter} con {@code clinical}, y no lo revalida el llamador. Es el reparto que
 * 08.04 dejo fijado: ninguna decision se toma dos veces.
 *
 * @param participacionId {@code asistencia_actividad.id}: el ancla de la derivacion (08.04 §5.1)
 * @param derivacionId    la derivacion elegida, o {@code null} si la participacion tiene una sola
 *                        vigente
 */
public record PedidoDeAtencion(
		ActorDeAtencion actor,
		long claseId,
		long participacionId,
		long personaId,
		Long derivacionId) {
}
