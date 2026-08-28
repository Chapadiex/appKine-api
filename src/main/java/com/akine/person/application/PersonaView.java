package com.akine.person.application;

import com.akine.person.domain.PerfilPaciente;
import com.akine.person.domain.Persona;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Proyeccion de lectura de una Persona. Es lo unico que cruza el borde del servicio de
 * aplicacion: la entity nunca sale (AGENT.md seccion 4, regla 6).
 *
 * <h2>{@code esPaciente} es DERIVADO, y esa es toda la gracia</h2>
 *
 * <p>No existe ninguna columna {@code es_paciente}: el valor sale de si hay o no un
 * {@link PerfilPaciente} vigente para esa persona. La vista lo expone porque toda pantalla lo
 * necesita —el listado marca cuales son pacientes, la ficha ofrece o no el boton de activar— y lo
 * expone como un booleano DE SOLO LECTURA. Nadie lo puede mandar de vuelta: no esta en ningun
 * comando. Que la unica forma de volverlo {@code true} sea insertar un perfil por su propia ruta
 * es RF-M07-010 sostenido por la forma de los tipos y no por una advertencia en un comentario.
 *
 * @param estado           DERIVADO de {@code active}, no una columna
 * @param perfilPacienteId id del perfil vigente, o {@code null}. Va junto con {@code esPaciente}
 *                         para que la pantalla no tenga que pedirlo aparte
 */
public record PersonaView(
		long id,
		String tipoDocumento,
		String numeroDocumento,
		String apellido,
		String nombre,
		LocalDate fechaNacimiento,
		String email,
		String telefono,
		String notas,
		boolean esPaciente,
		Long perfilPacienteId,
		Instant perfilActivadoEn,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	/** Sin perfil: la persona existe y no es paciente. Es el estado normal de un alta nueva. */
	public static PersonaView de(Persona persona) {
		return de(persona, null);
	}

	public static PersonaView de(Persona persona, PerfilPaciente perfil) {
		return new PersonaView(
				persona.getId(),
				persona.getTipoDocumento() == null ? null : persona.getTipoDocumento().name(),
				persona.getNumeroDocumento(),
				persona.getApellido(),
				persona.getNombre(),
				persona.getFechaNacimiento(),
				persona.getEmail(),
				persona.getTelefono(),
				persona.getNotas(),
				perfil != null,
				perfil == null ? null : perfil.getId(),
				perfil == null ? null : perfil.getActivadoEn(),
				persona.isActive() ? "ACTIVO" : "INACTIVO",
				persona.getDeletedAt(),
				persona.getDeactivationReason(),
				persona.getVersion());
	}
}
