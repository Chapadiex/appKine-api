package com.akine.person.infrastructure;

import com.akine.person.domain.OrdenMedica;
import com.akine.person.domain.port.PersonRepositoryPorts.OrdenMedicaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Ordenes medicas de un paciente (M17, AKINE-03.06).
 *
 * <p>Las tres consultas llevan {@code organizationId} <b>y</b> {@code personaId}. Sin la primera
 * una orden ajena resolveria; sin la segunda, la orden de otro paciente respondería a una ruta que
 * no le corresponde, que es una respuesta que miente sobre de quien es.
 *
 * <p>Ninguna pagina en la base, igual que las coberturas: las ordenes de una persona son unidades
 * y no miles. El padron si se pagina, porque ahi el volumen es otro.
 */
public interface OrdenMedicaRepository
		extends JpaRepository<OrdenMedica, Long>, OrdenMedicaRepositoryPort {

	@Override
	@Query("""
			SELECT o FROM OrdenMedica o
			 WHERE o.id = :id
			   AND o.organizationId = :organizationId
			   AND o.personaId = :personaId
			""")
	Optional<OrdenMedica> findByIdAndOrganizationIdAndPersonaId(
			@Param("id") Long id,
			@Param("organizationId") Long organizationId,
			@Param("personaId") Long personaId);

	@Override
	@Query("""
			SELECT o FROM OrdenMedica o
			 WHERE o.organizationId = :organizationId
			   AND o.personaId = :personaId
			 ORDER BY o.vigenciaDesde DESC, o.id DESC
			""")
	List<OrdenMedica> historial(
			@Param("organizationId") Long organizationId, @Param("personaId") Long personaId);

	@Override
	@Query("""
			SELECT o FROM OrdenMedica o
			 WHERE o.organizationId = :organizationId
			   AND o.personaId = :personaId
			   AND o.active = true
			 ORDER BY o.vigenciaDesde DESC, o.id DESC
			""")
	List<OrdenMedica> activasDe(
			@Param("organizationId") Long organizationId, @Param("personaId") Long personaId);
}
