package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Persistencia de {@link HistoriaClinica}.
 *
 * <p>Implementa el puerto de {@code domain} en vez de exponerse: la capa de aplicacion inyecta
 * {@code HistoriaClinicaRepositoryPort} y no sabe que existe Spring Data. Es la regla que 01.01
 * dejo fijada.
 *
 * <p><b>No hay ni un metodo que resuelva por id pelado.</b> Toda consulta lleva
 * {@code organizationId}: un {@code findById} heredado de {@code JpaRepository} usado por descuido
 * en un modulo clinico es una fuga de tenant, y la unica forma segura de que no aparezca es no
 * declarar la variante que lo invite.
 */
public interface HistoriaClinicaRepository
		extends JpaRepository<HistoriaClinica, Long>, HistoriaClinicaRepositoryPort {

	@Override
	Optional<HistoriaClinica> findByIdAndOrganizationId(Long id, Long organizationId);

	@Override
	@Query("""
			SELECT hc FROM HistoriaClinica hc
			 WHERE hc.organizationId = :organizationId
			   AND hc.personaId = :personaId
			   AND hc.active = true
			""")
	Optional<HistoriaClinica> buscarVigentePorPersona(
			@Param("organizationId") Long organizationId, @Param("personaId") Long personaId);
}
