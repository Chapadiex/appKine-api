package com.akine.activity.application;

import com.akine.encounter.spi.AtencionDeParticipante;

import java.util.List;

/**
 * Un participante en el panel de atencion grupal (AKINE-08.05): su asistencia y sus atenciones.
 *
 * @param apellido   apellido del padron, o {@code null} si la ficha no se resolvio. Junto con
 *                   {@code nombre}, lo unico de la persona que viaja: ni documento ni contacto
 * @param nombre     nombre del padron, o {@code null} si la ficha no se resolvio
 * @param estuvo     {@code true} si la asistencia habilita atender ({@code PRESENTE} o
 *                   {@code PRESENTE_TARDE})
 * @param atenciones una por derivacion atendida; vacia si todavia no se abrio ninguna. Mas de una
 *                   solo si la persona esta derivada a dos Casos
 */
public record ParticipanteEnAtencionView(
		long inscripcionId,
		long personaId,
		String apellido,
		String nombre,
		long asistenciaId,
		String resultadoAsistencia,
		boolean estuvo,
		List<AtencionDeParticipante> atenciones) {
}
