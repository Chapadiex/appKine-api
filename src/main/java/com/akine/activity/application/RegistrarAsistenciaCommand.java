package com.akine.activity.application;

import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;

/**
 * Lo que hay que decir para marcar a alguien (RF-M28-007).
 *
 * <p><b>No lleva clave de idempotencia, y no es un olvido.</b> La clave natural del hecho es
 * {@code (clase, persona)} y ya vive en un unique: un segundo pedido con el mismo resultado
 * devuelve la fila sin escribir, y con otro resultado es una correccion. Una clave habria dejado
 * el agujero de que dos claves distintas produzcan dos asistencias para la misma persona.
 *
 * @param motivo obligatorio <b>solo para corregir</b>. Registrar por primera vez no necesita
 *               explicacion; cambiar un hecho ya afirmado si
 */
public record RegistrarAsistenciaCommand(
		long inscripcionId,
		ResultadoAsistencia resultado,
		OrigenAsistencia origen,
		String observaciones,
		String motivo) {
}
