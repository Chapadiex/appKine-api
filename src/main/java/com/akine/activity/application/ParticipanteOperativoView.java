package com.akine.activity.application;

/**
 * Una fila del detalle operativo de una clase (RF-M28-009).
 *
 * <p><b>Lo que NO lleva es la mitad del punto.</b> Ni un dato clinico, ni cobertura, ni estado de
 * deuda, ni pase, ni abono. Lo clinico no esta porque un instructor con {@code inscripcion:read} no
 * puede ver a que se atiende nadie (RNF-M28-002, RF-M28-009: "separa indicadores clinicos de datos
 * economicos"). Lo economico no esta porque <b>todavia no existe</b>: el devengo por clase es de
 * 08.06/08.07 y su politica no vive en ninguna tabla.
 *
 * @param asistencia {@code null} si todavia nadie la marco. Es lo que el cierre resuelve
 */
public record ParticipanteOperativoView(
		InscripcionView inscripcion,
		AsistenciaView asistencia,
		String apellido,
		String nombre,
		String tipoDocumento,
		String numeroDocumento) {
}
