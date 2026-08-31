package com.akine.person.spi;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Lo que otro modulo necesita saber de una persona del padron, sin poder tocar su entity.
 *
 * <h2>Por que trae el perfil de paciente y no solo la identidad</h2>
 *
 * <p>Porque la pregunta que los modulos clinicos hacen no es "existe esta persona" sino
 * <b>"es paciente"</b>, y V27 dejo fijado que esas son dos cosas distintas: la identidad vive en
 * {@code persona} y el perfil clinico en {@code perfil_paciente}. Un consumidor que recibiera
 * solo la identidad tendria que preguntar dos veces, y —peor— podria olvidarse de la segunda,
 * que es exactamente el atajo que RF-M07-010 prohibe.
 *
 * <p><b>No viaja nada que el consumidor no pueda justificar.</b> Nombre, apellido, documento y
 * fecha de nacimiento son los datos con los que se identifica a un paciente en pantalla;
 * observaciones administrativas, correo y telefono no estan porque ningun modulo clinico los
 * necesita para decidir nada, y RN-M09-003 le prohibe ademas copiarlos en su propio esquema.
 *
 * @param esPacienteVigente {@code true} si la persona tiene un {@code perfil_paciente} vigente en
 *                          la organizacion. Es la respuesta a RN-M07-005 ya calculada:
 *                          recalcularla del lado del consumidor duplicaria la regla
 * @param activa            {@code true} mientras la ficha del padron no este dada de baja. Viaja
 *                          aunque sea {@code false} porque el consumidor necesita distinguir "esa
 *                          persona no es de tu organizacion" de "esa ficha esta dada de baja": lo
 *                          primero se trata como inexistente y lo segundo como conflicto
 */
public record PacienteSnapshot(
		long personaId,
		long organizationId,
		String apellido,
		String nombre,
		String tipoDocumento,
		String numeroDocumento,
		LocalDate fechaNacimiento,
		boolean activa,
		boolean esPacienteVigente,
		Long perfilPacienteId,
		Instant perfilActivadoEn) {

	/** Apellido y nombre para mostrar, en el orden en que el padron los lista. */
	public String nombreCompleto() {
		return apellido + ", " + nombre;
	}
}
