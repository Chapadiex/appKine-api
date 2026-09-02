package com.akine.person.infrastructure;

import com.akine.person.domain.PerfilPaciente;
import com.akine.person.domain.Persona;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implementacion de {@link PacienteDirectory}: el borde por donde {@code person} responde a los
 * modulos clinicos.
 *
 * <p><b>Nunca devuelve entities</b>, solo el record del {@code spi}. Devolver la {@link Persona}
 * o el {@link PerfilPaciente} le daria al consumidor una entity gestionada, con la que podria
 * escribir en tablas que no son suyas — y ademas los mapeos JPA de este modulo dejarian de ser
 * privados.
 *
 * <p>{@code readOnly}: son consultas puras. Se unen a la transaccion del llamador cuando hay una.
 */
@Component
public class PersonPacienteDirectory implements PacienteDirectory {

	private final PersonaRepositoryPort personas;
	private final PerfilPacienteRepositoryPort perfiles;

	public PersonPacienteDirectory(
			PersonaRepositoryPort personas, PerfilPacienteRepositoryPort perfiles) {
		this.personas = personas;
		this.perfiles = perfiles;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PacienteSnapshot> find(long organizationId, long personaId) {
		return personas.findByIdAndOrganizationId(personaId, organizationId)
				.map(persona -> instantanea(
						persona, perfiles.buscarVigente(organizationId, personaId).orElse(null)));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, PacienteSnapshot> findAll(
			long organizationId, Collection<Long> personaIds) {

		if (personaIds == null || personaIds.isEmpty()) {
			return Map.of();
		}
		// Se deduplica antes de consultar: la recepcion de un dia puede traer varios turnos del
		// mismo paciente, y pedirlo N veces a la base para armar un mapa que lo va a tener una
		// sola vez es trabajo que no le sirve a nadie.
		List<Long> ids = personaIds.stream().filter(Objects::nonNull).distinct().toList();

		// Dos consultas y no una por persona: la de perfiles ya existia en batch desde 03.01.
		Map<Long, PerfilPaciente> perfilesPorPersona =
				perfiles.buscarVigentesDePersonas(organizationId, ids).stream()
						.collect(Collectors.toMap(PerfilPaciente::getPersonaId, perfil -> perfil,
								(primero, segundo) -> primero));

		return personas.findAllByIdInAndOrganizationId(ids, organizationId).stream()
				.collect(Collectors.toMap(
						Persona::getId,
						persona -> instantanea(persona, perfilesPorPersona.get(persona.getId()))));
	}

	private PacienteSnapshot instantanea(Persona persona, PerfilPaciente perfil) {
		return new PacienteSnapshot(
				persona.getId(),
				persona.getOrganizationId(),
				persona.getApellido(),
				persona.getNombre(),
				persona.getTipoDocumento() == null ? null : persona.getTipoDocumento().name(),
				persona.getNumeroDocumento(),
				persona.getFechaNacimiento(),
				persona.isActive(),
				perfil != null,
				perfil == null ? null : perfil.getId(),
				perfil == null ? null : perfil.getActivadoEn());
	}
}
