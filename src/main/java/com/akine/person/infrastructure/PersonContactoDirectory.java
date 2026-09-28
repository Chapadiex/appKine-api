package com.akine.person.infrastructure;

import com.akine.person.domain.Persona;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.person.spi.ContactoDePersona;
import com.akine.person.spi.ContactoDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Implementacion de {@link ContactoDirectory}. Adaptador delgado, mismo patron que
 * {@code PersonPacienteDirectory}: traduce a un record del {@code spi} y nada mas.
 *
 * <p><b>Devuelve el contacto de fichas dadas de baja tambien.</b> No es un descuido: si una clase
 * se cancela, hay que avisarle a todos los que estaban anotados, incluida la persona cuya ficha se
 * dio de baja ayer. Filtrar por {@code active} aca dejaria a alguien esperando en la puerta.
 */
@Component
public class PersonContactoDirectory implements ContactoDirectory {

	private final PersonaRepositoryPort personas;

	public PersonContactoDirectory(PersonaRepositoryPort personas) {
		this.personas = personas;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, ContactoDePersona> findAll(long organizationId, Collection<Long> personaIds) {
		if (personaIds == null || personaIds.isEmpty()) {
			return Map.of();
		}
		List<Long> ids = personaIds.stream().filter(Objects::nonNull).distinct().toList();

		return personas.findAllByIdInAndOrganizationId(ids, organizationId).stream()
				.collect(Collectors.toMap(
						Persona::getId,
						persona -> new ContactoDePersona(
								persona.getId(), persona.getEmail(), persona.getNombre())));
	}
}
