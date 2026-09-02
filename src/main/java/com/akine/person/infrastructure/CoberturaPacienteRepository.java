package com.akine.person.infrastructure;

import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a las coberturas de un paciente (M08).
 *
 * <p>Las tres consultas llevan {@code organizationId} <b>y</b> {@code personaId}, y ninguna de las
 * dos columnas es redundante: sin la primera una cobertura de otro tenant resolveria, y sin la
 * segunda una cobertura de otro paciente respondería a una ruta que no le corresponde. Es la misma
 * invariante que declara la cabecera de {@code PersonRepositoryPorts}, y aca vale doble: una
 * cobertura dice de que obra social es un paciente identificado.
 */
public interface CoberturaPacienteRepository
		extends JpaRepository<CoberturaPaciente, Long>, CoberturaPacienteRepositoryPort {

	@Override
	@Query("""
			SELECT c FROM CoberturaPaciente c
			 WHERE c.id = :id
			   AND c.organizationId = :organizationId
			   AND c.personaId = :personaId
			""")
	Optional<CoberturaPaciente> findByIdAndOrganizationIdAndPersonaId(
			@Param("id") Long id,
			@Param("organizationId") Long organizationId,
			@Param("personaId") Long personaId);

	@Override
	@Query("""
			SELECT c FROM CoberturaPaciente c
			 WHERE c.organizationId = :organizationId
			   AND c.personaId = :personaId
			 ORDER BY c.vigenciaDesde DESC, c.id DESC
			""")
	List<CoberturaPaciente> historial(
			@Param("organizationId") Long organizationId, @Param("personaId") Long personaId);

	@Override
	@Query("""
			SELECT c FROM CoberturaPaciente c
			 WHERE c.organizationId = :organizationId
			   AND c.personaId = :personaId
			   AND c.active = true
			 ORDER BY c.vigenciaDesde DESC, c.id DESC
			""")
	List<CoberturaPaciente> activasDe(
			@Param("organizationId") Long organizationId, @Param("personaId") Long personaId);
}
