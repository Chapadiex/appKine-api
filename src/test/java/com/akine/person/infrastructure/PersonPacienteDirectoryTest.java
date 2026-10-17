package com.akine.person.infrastructure;

import com.akine.person.domain.PerfilPaciente;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDocumento;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.person.spi.PacienteSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Lo que {@code person} le dice a los demas modulos sobre una persona: <b>una Persona no es un
 * Paciente</b>, y {@code esPacienteVigente} sale de que exista un perfil vigente, no de la fila de
 * la persona.
 */
@DisplayName("PersonPacienteDirectory")
class PersonPacienteDirectoryTest {

	private static final long ORG = 10L;
	private static final Instant ACTIVADO = Instant.parse("2026-09-01T12:00:00Z");

	private final PersonaRepositoryPort personas = mock(PersonaRepositoryPort.class);
	private final PerfilPacienteRepositoryPort perfiles = mock(PerfilPacienteRepositoryPort.class);
	private final PersonPacienteDirectory directorio = new PersonPacienteDirectory(personas, perfiles);

	@Test
	@DisplayName("una persona sin perfil vigente viaja como NO paciente, sin id de perfil")
	void persona_sin_perfil() {
		given(personas.findByIdAndOrganizationId(1L, ORG)).willReturn(Optional.of(persona(1L, null)));
		given(perfiles.buscarVigente(ORG, 1L)).willReturn(Optional.empty());

		PacienteSnapshot snapshot = directorio.find(ORG, 1L).orElseThrow();

		assertThat(snapshot.esPacienteVigente()).isFalse();
		assertThat(snapshot.perfilPacienteId()).isNull();
		assertThat(snapshot.perfilActivadoEn()).isNull();
		assertThat(snapshot.tipoDocumento()).as("un contacto puede no tener documento").isNull();
	}

	@Test
	@DisplayName("el lote deduplica, ignora nulos y marca paciente solo a quien tiene perfil")
	void lote() {
		given(perfiles.buscarVigentesDePersonas(ORG, List.of(1L, 2L)))
				.willReturn(List.of(perfil(2L, 70L)));
		given(personas.findAllByIdInAndOrganizationId(List.of(1L, 2L), ORG))
				.willReturn(List.of(persona(1L, TipoDocumento.DNI), persona(2L, TipoDocumento.DNI)));

		Map<Long, PacienteSnapshot> porId =
				directorio.findAll(ORG, Arrays.asList(1L, 2L, 1L, null, 2L));

		assertThat(porId).containsOnlyKeys(1L, 2L);
		assertThat(porId.get(1L).esPacienteVigente()).isFalse();
		assertThat(porId.get(2L).esPacienteVigente()).isTrue();
		assertThat(porId.get(2L).perfilPacienteId()).isEqualTo(70L);
		assertThat(porId.get(2L).perfilActivadoEn()).isEqualTo(ACTIVADO);
		assertThat(porId.get(2L).tipoDocumento()).isEqualTo("DNI");
	}

	@Test
	@DisplayName("un lote vacio o nulo no consulta la base")
	void lote_vacio() {
		assertThat(directorio.findAll(ORG, List.of())).isEmpty();
		assertThat(directorio.findAll(ORG, null)).isEmpty();
		verify(personas, never()).findAllByIdInAndOrganizationId(any(), anyLong());
	}

	private static Persona persona(long id, TipoDocumento tipo) {
		Persona persona = new Persona(ORG, tipo, tipo == null ? null : "3000000" + id, "Sintetica",
				"Paciente " + id, null, null, null, null);
		ReflectionTestUtils.setField(persona, "id", id);
		return persona;
	}

	private static PerfilPaciente perfil(long personaId, long id) {
		PerfilPaciente perfil = new PerfilPaciente(ORG, personaId, ACTIVADO, 40L, null);
		ReflectionTestUtils.setField(perfil, "id", id);
		return perfil;
	}
}
