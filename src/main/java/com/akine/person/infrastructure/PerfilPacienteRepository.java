package com.akine.person.infrastructure;

import com.akine.person.domain.PerfilPaciente;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a los perfiles de paciente. Toda consulta filtra por {@code organizationId}, igual que
 * {@link PersonaRepository} y por el mismo motivo.
 *
 * <p>Las dos consultas piden {@code active = true} y no miran {@code deleted_at}: los dos campos
 * se mueven juntos y {@code ck_perfil_paciente_baja_coherente} lo garantiza a nivel de base, asi
 * que comprobar uno solo no deja ninguna fila afuera.
 */
public interface PerfilPacienteRepository
		extends JpaRepository<PerfilPaciente, Long>, PerfilPacienteRepositoryPort {

	@Override
	@Query("""
			SELECT pp FROM PerfilPaciente pp
			 WHERE pp.organizationId = :organizationId
			   AND pp.personaId = :personaId
			   AND pp.active = true
			""")
	Optional<PerfilPaciente> buscarVigente(
			@Param("organizationId") Long organizationId, @Param("personaId") Long personaId);

	@Override
	@Query("""
			SELECT pp FROM PerfilPaciente pp
			 WHERE pp.organizationId = :organizationId
			   AND pp.personaId IN :personaIds
			   AND pp.active = true
			""")
	List<PerfilPaciente> buscarVigentesDePersonas(
			@Param("organizationId") Long organizationId,
			@Param("personaIds") List<Long> personaIds);
}
